package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.data.files.FileRepository
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.provider.ProviderOAuthRepository
import cc.jaxy.bob.core.data.provider.ProviderSecretStore
import cc.jaxy.bob.core.data.search.WebSearchClient
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class ChatStreamChunk(
    val textDelta: String = "",
    val reasoningDelta: String? = null,
    val providerContext: List<JsonObject> = emptyList(),
    val completion: ChatCompletion? = null,
    val searchActivity: WebSearchActivity? = null,
    val citations: List<WebCitation> = emptyList(),
    /** Complete model-requested calls; partial streaming fragments never surface here. */
    val toolCalls: List<ChatToolCall> = emptyList(),
)
interface ChatCompletionRepository {
    fun stream(
        providerId: String,
        modelId: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double? = null,
        sessionId: String? = null,
        searchPolicy: WebSearchPolicy = WebSearchPolicy(),
        /** Pure prompt: skip every app-added prompt (external search + its instructions). */
        purePrompt: Boolean = false,
    ): Flow<ChatStreamChunk>
}

@Singleton
class DirectChatCompletionRepository @Inject constructor(
    private val dao: LocalDao,
    private val secrets: ProviderSecretStore,
    private val files: FileRepository,
    private val searchProviders: WebSearchProviderRepository,
) : ChatCompletionRepository {
    private val http = OkHttpClientProvider.client
    private val json = Json { ignoreUnknownKeys = true }
    private val oauth = ProviderOAuthRepository(secrets)
    private val requests = ProviderChatRequests(files, http)
    private val webSearch = WebSearchClient(http)
    override fun stream(
        providerId: String,
        modelId: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double?,
        sessionId: String?,
        searchPolicy: WebSearchPolicy,
        purePrompt: Boolean,
    ): Flow<ChatStreamChunk> = channelFlow {
        try {
            require(modelId.isNotBlank()) { "Model is required" }
            val provider = dao.provider(providerId) ?: error("Provider not found")
            check(provider.enabled) { "Provider is disabled" }
            val model = dao.model(providerId, modelId) ?: error("Model not found")
            check(model.enabled) { "Model is disabled" }
            val metadata = json.decodeFromString<ModelCatalogMetadata>(model.metadata)
            require(metadata.type.isNullOrBlank() || metadata.type.equals("chat", ignoreCase = true)) { "This model does not support chat" }
            // Pure prompt: no app-added search at all. External selection behaves
            // as OFF (no request, no injection, no error); native tool stays off.
            val effectivePolicy = if (purePrompt) WebSearchPolicy() else searchPolicy
            val nativeSearch = effectivePolicy.mode == WebSearchMode.NATIVE
            val externalSearch = effectivePolicy.mode == WebSearchMode.PROVIDER
            require(!nativeSearch || (provider.source == "builtin" && supportsWebSearch(providerId, modelId))) {
                "The selected model does not support native web search. Choose a supported model or an external search provider in the Agent settings."
            }
            var config = json.decodeFromString<Map<String, String>>(provider.config) + secrets.read(providerId) + ("__providerName" to provider.name)
            if (provider.source != "custom" && providerId in setOf("chatgpt", "githubcopilot", "supergrok")) {
                config = config + oauth.credentials(providerId)
            }
            val reasoning = dao.reasoning(providerId, modelId)?.value?.let { json.decodeFromString<ReasoningConfig>(it).raw }.orEmpty()
            val searchTarget = if (externalSearch) {
                val candidate = searchProviders.providers.first().firstOrNull { it.id == effectivePolicy.providerId }
                    ?: throw ChatStreamException(
                        ChatCompletion(ChatCompletionStatus.FAILED, "web_search_unavailable"),
                        "The selected search provider was removed. Choose another provider in the Agent settings.",
                    )
                if (!candidate.enabled) throw ChatStreamException(
                    ChatCompletion(ChatCompletionStatus.FAILED, "web_search_unavailable"),
                    "The selected search provider is disabled. Enable it in Web search settings.",
                )
                SearchTarget(candidate, searchProviders.apiKey(candidate.id))
            } else null
            var requestSystemPrompt = systemPrompt
            if (externalSearch) requestSystemPrompt = withWebSearchInstruction(systemPrompt)
            // One generation may contain several model-decided search rounds; each round sees the
            // previous tool results, but only the final assistant answer is exposed to the UI.
            var conversation = history
            var searchRounds = 0
            var finalCompletion: ChatCompletion? = null
            while (true) {
                var toolCalls = emptyList<ChatToolCall>()
                val roundText = StringBuilder()
                val roundContext = mutableListOf<JsonObject>()
                var roundCompletion: ChatCompletion? = null
                val request = requests.prepare(
                    providerId, provider.source, config, modelId, requestSystemPrompt, conversation, temperature,
                    sessionId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(), reasoning, metadata.maxOutput,
                    webSearchEnabled = nativeSearch,
                    toolSearch = externalSearch,
                )
                executeProviderRequest(http, request) { chunk ->
                    chunk.completion?.let { roundCompletion = it }
                    if (chunk.toolCalls.isNotEmpty()) toolCalls = toolCalls + chunk.toolCalls
                    roundText.append(chunk.textDelta)
                    roundContext += chunk.providerContext
                    val display = chunk.copy(completion = null, providerContext = emptyList(), toolCalls = emptyList())
                    if (display.textDelta.isNotEmpty() || !display.reasoningDelta.isNullOrEmpty() ||
                        display.searchActivity != null || display.citations.isNotEmpty()) send(display)
                }
                val completion = roundCompletion
                    ?: throw ChatStreamException(ChatCompletion(ChatCompletionStatus.INTERRUPTED), "Provider stream ended without a completion signal")
                if (!externalSearch || toolCalls.isEmpty()) {
                    if (externalSearch && completion.status == ChatCompletionStatus.TOOL_CALL_REQUIRED) {
                        throw ChatStreamException(ChatCompletion(ChatCompletionStatus.FAILED, "tool_unavailable"),
                            "The model requested a tool that is not available.")
                    }
                    if (roundContext.isNotEmpty()) send(ChatStreamChunk(providerContext = roundContext.toList()))
                    finalCompletion = completion
                    break
                }
                if (completion.status != ChatCompletionStatus.COMPLETED && completion.status != ChatCompletionStatus.TOOL_CALL_REQUIRED) {
                    throw ChatStreamException(completion, "The provider stopped before completing the web search tool call")
                }
                searchRounds++
                if (searchRounds > MAX_WEB_SEARCH_ROUNDS) {
                    throw ChatStreamException(ChatCompletion(ChatCompletionStatus.FAILED, "web_search_limit"),
                        "The model requested too many web searches in one answer.")
                }
                conversation = conversation + ChatMessage(
                    role = "assistant",
                    content = roundText.toString(),
                    provider = providerId,
                    model = modelId,
                    providerContext = roundContext.toList(),
                    toolCalls = toolCalls,
                )
                val target = checkNotNull(searchTarget)
                val identity = Triple(target.provider.id, target.provider.name, target.provider.builtin)
                for (call in toolCalls) {
                    if (call.name != WEB_SEARCH_TOOL_NAME) {
                        conversation += ChatMessage(
                            role = "tool",
                            content = webSearchToolError("Unknown tool \"${call.name}\". Only $WEB_SEARCH_TOOL_NAME is available."),
                            toolCallId = call.id,
                        )
                        continue
                    }
                    val query = parseWebSearchQuery(call.arguments)
                    if (query == null) {
                        conversation += ChatMessage(
                            role = "tool",
                            content = webSearchToolError("The web_search tool requires a non-empty query string."),
                            toolCallId = call.id,
                        )
                        continue
                    }
                    send(ChatStreamChunk(searchActivity = WebSearchActivity(WebSearchStage.SEARCHING, listOf(query),
                        providerId = identity.first, providerName = identity.second, providerBuiltin = identity.third)))
                    val results = try {
                        webSearch.search(target.provider, target.key, query)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        send(ChatStreamChunk(searchActivity = WebSearchActivity(WebSearchStage.FAILED, listOf(query), error.message,
                            providerId = identity.first, providerName = identity.second, providerBuiltin = identity.third)))
                        throw ChatStreamException(ChatCompletion(ChatCompletionStatus.FAILED, "web_search_failed"),
                            error.message ?: "Web search failed", error)
                    }
                    val citations = results.map { WebCitation(url = it.url, title = it.title) }
                    send(ChatStreamChunk(searchActivity = WebSearchActivity(WebSearchStage.COMPLETED, listOf(query), null,
                        providerId = identity.first, providerName = identity.second, providerBuiltin = identity.third), citations = citations))
                    conversation += ChatMessage(
                        role = "tool",
                        content = webSearchToolResult(query, results),
                        toolCallId = call.id,
                    )
                }
            }
            send(ChatStreamChunk(completion = checkNotNull(finalCompletion)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Throwing from the producer cancels its buffer; closing drains queued deltas before the error.
            close(error)
        }
    }
}

private object OkHttpClientProvider {
    val client = okhttp3.OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
}

private data class SearchTarget(val provider: WebSearchProvider, val key: String?)

/** Bounded so a model cannot spend an unbounded number of paid searches in one turn. */
private const val MAX_WEB_SEARCH_ROUNDS = 5
