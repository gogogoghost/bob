package cc.jaxy.anlobehub.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.agent.AgentRepository
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.core.data.chat.ChatAttachment
import cc.jaxy.anlobehub.core.data.chat.ChatCompletionRepository
import cc.jaxy.anlobehub.core.data.chat.ChatMessage
import cc.jaxy.anlobehub.core.data.chat.ChatCompletion
import cc.jaxy.anlobehub.core.data.chat.ChatCompletionStatus
import cc.jaxy.anlobehub.core.data.chat.ChatResponseBuffer
import cc.jaxy.anlobehub.core.data.chat.ChatStreamException
import cc.jaxy.anlobehub.core.data.chat.ChatTopic
import cc.jaxy.anlobehub.core.data.chat.ModelRepository
import cc.jaxy.anlobehub.core.data.chat.MessageRepository
import cc.jaxy.anlobehub.core.data.chat.ReasoningConfig
import cc.jaxy.anlobehub.core.data.chat.TopicRepository
import cc.jaxy.anlobehub.core.data.chat.WebCitation
import cc.jaxy.anlobehub.core.data.chat.WebSearchActivity
import cc.jaxy.anlobehub.core.data.chat.supportsWebSearch
import cc.jaxy.anlobehub.core.data.preferences.WebSearchPreferences
import cc.jaxy.anlobehub.core.data.preferences.WebSearchSettings
import cc.jaxy.anlobehub.core.data.search.WebSearchMode
import cc.jaxy.anlobehub.core.data.search.WebSearchPolicy
import cc.jaxy.anlobehub.core.data.search.WebSearchProvider
import cc.jaxy.anlobehub.core.data.search.WebSearchProviderRepository
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.files.UploadedFile
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val topicRepository: TopicRepository,
    private val modelRepository: ModelRepository,
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val completionRepository: ChatCompletionRepository,
    private val searchPreferences: WebSearchPreferences,
    private val searchProviders: WebSearchProviderRepository,
) : ViewModel() {
    val agentId: String = savedStateHandle.get<String>("agentId") ?: ""
    val agentTitle: String? = savedStateHandle.get<String>("agentTitle")?.takeIf(String::isNotBlank)
    private val initialTopicId = savedStateHandle.get<String>("topicId")?.takeIf(String::isNotBlank)

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val messagesLoading: Boolean = false,
        val streaming: Boolean = false,
        val streamingText: String = "",
        val streamingReasoning: String = "",
        val streamingReasoningSecs: Double? = null,
        val error: UiText? = null,
        val topics: List<ChatTopic> = emptyList(),
        val topicsLoading: Boolean = false,
        val activeTopicId: String? = null,
        val models: List<AIModel> = emptyList(),
        val activeModel: AIModel? = null,
        val modelsLoading: Boolean = false,
        val agentAvatar: String? = null,
        val openingMessage: String? = null,
        val openingQuestions: List<String> = emptyList(),
        val pendingFiles: List<UploadedFile> = emptyList(),
        val uploading: Boolean = false,
        val reasoningConfig: ReasoningConfig? = null,
        val reasoningLoading: Boolean = false,
        val reasoningSaving: Boolean = false,
        val reasoningSavedTick: Int = 0,
        val webSearchAvailable: Boolean = false,
        val webSearchLoading: Boolean = true,
        val webSearchReady: Boolean = false,
        val webSearchPolicy: WebSearchPolicy = WebSearchPolicy(),
        val webSearchOverride: WebSearchPolicy? = null,
        val purePrompt: Boolean = false,
        val webSearchProviderName: String? = null,
        val streamingSearchActivity: WebSearchActivity? = null,
        val streamingCitations: List<WebCitation> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var temperature: Double? = null
    private var systemPrompt = ""
    private var purePrompt = false
    private var streamJob: Job? = null
    private var activeResponse: ChatResponseBuffer? = null
    private var generation = 0L
    private var globalSearchSettings: WebSearchSettings? = null
    private var globalSearchLoading = true
    private var agentSearchLoaded = false
    private var agentSearchLoading = true
    private var searchPreferencesJob: Job? = null
    private var searchProvidersJob: Job? = null
    private var availableSearchProviders: List<WebSearchProvider>? = null
    private var searchProvidersLoading = true

    init { load() }

    private fun observeSearchPreferences() {
        if (searchPreferencesJob?.isActive == true) return
        globalSearchLoading = true
        publishSearchPolicy()
        searchPreferencesJob = viewModelScope.launch {
            searchPreferences.webSearchSettings.catch { error ->
                if (error is CancellationException) throw error
                globalSearchSettings = null
                globalSearchLoading = false
                publishSearchPolicy()
                _uiState.value = _uiState.value.copy(error = UiText.Res(R.string.chat_web_search_preferences_error))
            }.collect { settings ->
                globalSearchSettings = settings
                globalSearchLoading = false
                publishSearchPolicy()
            }
        }
    }
    private fun observeSearchProviders() {
        if (searchProvidersJob?.isActive == true) return
        searchProvidersLoading = true
        publishSearchPolicy()
        searchProvidersJob = viewModelScope.launch {
            searchProviders.providers.catch { error ->
                if (error is CancellationException) throw error
                availableSearchProviders = null
                searchProvidersLoading = false
                publishSearchPolicy()
                if (_uiState.value.webSearchPolicy.mode == WebSearchMode.PROVIDER) {
                    _uiState.value = _uiState.value.copy(error = UiText.Res(R.string.chat_web_search_preferences_error))
                }
            }.collect { providers ->
                availableSearchProviders = providers
                searchProvidersLoading = false
                publishSearchPolicy()
            }
        }
    }


    private fun publishSearchPolicy() {
        val state = _uiState.value
        val settings = globalSearchSettings
        // Pure prompt: the agent opted out of every app-added prompt. Search
        // namespaces collapse to OFF (silently, not as an error).
        val policy = if (purePrompt) WebSearchPolicy() else state.webSearchOverride ?: settings?.policy ?: WebSearchPolicy()
        val external = policy.mode == WebSearchMode.PROVIDER
        val selected = availableSearchProviders?.firstOrNull { it.id == policy.providerId }
        val ready = settings != null && agentSearchLoaded && (!external || availableSearchProviders != null)
        _uiState.value = state.copy(
            webSearchPolicy = policy,
            webSearchAvailable = ready && when (policy.mode) {
                WebSearchMode.OFF -> true
                WebSearchMode.NATIVE -> state.activeModel?.let { it.chatSupported && supportsWebSearch(it.providerId, it.id) } == true
                WebSearchMode.PROVIDER -> selected?.enabled == true && selected.endpoint.isNotBlank()
            },
            webSearchReady = ready,
            webSearchLoading = globalSearchLoading || agentSearchLoading || (external && searchProvidersLoading),
            webSearchProviderName = selected?.name ?: policy.providerId,
        )
    }

    fun load() {
        observeSearchPreferences()
        observeSearchProviders()
        agentSearchLoaded = effectiveAgentId() == null
        agentSearchLoading = !agentSearchLoaded
        publishSearchPolicy()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(modelsLoading = true)
            val modelsDeferred = async { modelRepository.listModels() }
            val configDeferred = async { effectiveAgentId()?.let { agentRepository.getConfig(it) } }
            val models = (modelsDeferred.await() as? AnResult.Ok)?.value.orEmpty()
            val configResult = configDeferred.await()
            if (configResult is AnResult.Err) {
                agentSearchLoading = false
                publishSearchPolicy()
                _uiState.value = _uiState.value.copy(models = models, modelsLoading = false,
                    error = configResult.error.toUiText())
                return@launch
            }
            val config = (configResult as? AnResult.Ok)?.value
            agentSearchLoaded = true
            agentSearchLoading = false
            systemPrompt = config?.systemRole.orEmpty()
            purePrompt = config?.purePrompt == true
            val needTopics = initialTopicId != null || _uiState.value.activeTopicId != null
            val topics = if (needTopics) (topicRepository.listTopics(agentId = effectiveAgentId()) as? AnResult.Ok)?.value.orEmpty() else emptyList()
            val topicId = _uiState.value.activeTopicId?.takeIf { id -> topics.any { it.id == id } }
                ?: initialTopicId?.takeIf { id -> topics.any { it.id == id } }
            temperature = config?.temperature
            val chatModels = models.filter { it.chatSupported && it.enabled == true && it.providerEnabled }
            val model = _uiState.value.activeModel?.takeIf { chosen ->
                chosen.chatSupported && chosen.enabled == true && chosen.providerEnabled &&
                    chatModels.any { it.id == chosen.id && it.providerId == chosen.providerId }
            }
                ?: config?.let { cfg ->
                    cfg.model?.let { id ->
                        chatModels.firstOrNull { it.id == id && (cfg.provider.isNullOrBlank() || it.providerId == cfg.provider) }
                    }
                }
                ?: chatModels.firstOrNull()
            _uiState.value = _uiState.value.copy(topics = topics, activeTopicId = topicId, models = models,
                activeModel = model, modelsLoading = false, agentAvatar = config?.avatar?.takeIf(String::isNotBlank),
                webSearchOverride = config?.webSearch,
                purePrompt = config?.purePrompt == true,
                openingMessage = config?.openingMessage?.takeIf(String::isNotBlank),
                openingQuestions = config?.openingQuestions.orEmpty().filter(String::isNotBlank))
            publishSearchPolicy()
            loadMessages()
        }
    }

    fun loadTopics() {
        if (_uiState.value.topicsLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(topicsLoading = true)
            when (val result = topicRepository.listTopics(agentId = effectiveAgentId())) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(topics = result.value, topicsLoading = false)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(topicsLoading = false, error = result.error.toUiText())
            }
        }
    }

    fun newConversation() {
        if (_uiState.value.streaming) stop()
        _uiState.value = _uiState.value.copy(activeTopicId = null, messages = emptyList(), error = null)
    }
    fun selectTopic(id: String?) {
        if (_uiState.value.activeTopicId == id) return
        if (_uiState.value.streaming) stop()
        _uiState.value = _uiState.value.copy(activeTopicId = id, messages = emptyList(), messagesLoading = true, error = null)
        viewModelScope.launch { loadMessages() }
    }
    fun selectModel(model: AIModel) {
        _uiState.value = _uiState.value.copy(activeModel = model)
        publishSearchPolicy()
    }


    fun send(text: String, fileIds: List<String> = emptyList()) {
        val state = _uiState.value
        if (text.isBlank() || state.streaming || streamJob?.isActive == true) return
        if (state.webSearchLoading || !state.webSearchReady) {
            _uiState.value = state.copy(error = UiText.Res(if (state.webSearchLoading)
                R.string.chat_web_search_loading else R.string.chat_web_search_preferences_error))
            return
        }
        val model = state.activeModel
        if (model == null || model.providerId.isNullOrBlank() || model.id.isBlank()) {
            _uiState.value = state.copy(error = UiText.Res(R.string.chat_model_required))
            return
        }
        if (state.webSearchPolicy.mode != WebSearchMode.OFF && !state.webSearchAvailable) {
            _uiState.value = state.copy(error = UiText.Res(if (state.webSearchPolicy.mode == WebSearchMode.NATIVE)
                R.string.chat_web_search_native_unavailable else R.string.chat_web_search_provider_unavailable))
            return
        }
        val turn = ++generation
        // Reserve the turn before any suspend call, including topic creation and user-message persistence.
        val searchPolicy = state.webSearchPolicy
        _uiState.value = state.copy(streaming = true, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null,
            streamingSearchActivity = null, streamingCitations = emptyList(), error = null)
        streamJob = viewModelScope.launch { runSend(text, fileIds, model, turn, searchPolicy) }
    }

    fun attach(bytes: ByteArray, name: String, mime: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(uploading = true, error = null)
            when (val result = fileRepository.uploadFile(name, mime, bytes)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(pendingFiles = _uiState.value.pendingFiles + result.value, uploading = false)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(uploading = false, error = result.error.toUiText())
            }
        }
    }
    fun removePendingFile(file: UploadedFile) { _uiState.value = _uiState.value.copy(pendingFiles = _uiState.value.pendingFiles - file) }

    /** Re-runs the latest user prompt on the current topic; failed/interrupted turns stay in history. */
    fun retry() {
        val state = _uiState.value
        if (state.streaming || streamJob?.isActive == true) return
        val prompt = state.messages.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        if (prompt.isBlank()) {
            _uiState.value = state.copy(error = UiText.Res(R.string.chat_retry_nothing))
            return
        }
        send(prompt)
    }

    fun stop() {
        val state = _uiState.value
        if (!state.streaming) return
        val response = activeResponse
        response?.finish(ChatCompletion(ChatCompletionStatus.STOPPED))
        val assistant = response?.snapshot()
        ++generation // An old request must not change a newly selected topic or a newer generation.
        val job = streamJob
        streamJob = null
        activeResponse = null
        _uiState.value = state.copy(
            messages = if (assistant != null && response?.topicId == state.activeTopicId)
                state.messages + assistant else state.messages,
            streaming = false, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null,
            streamingSearchActivity = null, streamingCitations = emptyList(),
        )
        job?.cancel()
        // The owning coroutine persists its complete buffer in NonCancellable, even after navigation.
    }
    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun loadReasoning(model: AIModel) {
        if (_uiState.value.reasoningLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reasoningLoading = true)
            when (val r = modelRepository.getReasoningConfig(model.id, model.providerId.orEmpty())) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(reasoningConfig = r.value, reasoningLoading = false)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(reasoningLoading = false, error = r.error.toUiText())
            }
        }
    }
    fun saveReasoning(model: AIModel, key: String, value: String?) {
        if (_uiState.value.reasoningSaving) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reasoningSaving = true)
            val current = _uiState.value.reasoningConfig ?: ReasoningConfig()
            val merged = current.raw.toMutableMap().apply { if (value.isNullOrBlank()) remove(key) else put(key, value) }
            when (val r = modelRepository.updateReasoningConfig(model.id, model.providerId.orEmpty(), merged)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(reasoningConfig = current.copy(
                    effort = if (key == "effort") value else current.effort,
                    reasoningEffort = if (key == "reasoningEffort") value else current.reasoningEffort,
                    thinkingLevel = if (key == "thinkingLevel") value else current.thinkingLevel, raw = merged),
                    reasoningSaving = false, reasoningSavedTick = _uiState.value.reasoningSavedTick + 1)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(reasoningSaving = false, error = r.error.toUiText())
            }
        }
    }

    private suspend fun loadMessages() {
        val topic = _uiState.value.activeTopicId
        if (topic == null) { _uiState.value = _uiState.value.copy(messages = emptyList(), messagesLoading = false); return }
        _uiState.value = _uiState.value.copy(messagesLoading = true)
        when (val r = messageRepository.listMessages(topicId = topic, agentId = effectiveAgentId())) {
            is AnResult.Ok -> _uiState.value = _uiState.value.copy(messages = r.value, messagesLoading = false, error = null)
            is AnResult.Err -> _uiState.value = _uiState.value.copy(messagesLoading = false, error = r.error.toUiText())
        }
    }

    private suspend fun runSend(text: String, fileIds: List<String>, model: AIModel, turn: Long, searchPolicy: WebSearchPolicy) {
        val initial = _uiState.value
        val files = initial.pendingFiles.filter { it.id in fileIds }
        val attachments = files.map { ChatAttachment(it.id, it.name, it.mime, it.url.orEmpty()) }
        var topicId = initial.activeTopicId
        var response: ChatResponseBuffer? = null
        var failureDetail: String? = null
        var uiError: UiText? = null
        try {
            if (topicId == null) {
                when (val created = topicRepository.createTopic(text.trim().take(80), agentId = effectiveAgentId())) {
                    is AnResult.Ok -> topicId = created.value
                    is AnResult.Err -> { uiError = created.error.toUiText(); return }
                }
            }
            if (turn != generation) return
            _uiState.value = _uiState.value.copy(activeTopicId = topicId)
            val user = ChatMessage(id = "user-${System.currentTimeMillis()}", role = "user", content = text,
                createdAt = System.currentTimeMillis(), attachments = attachments)
            when (val saved = messageRepository.saveMessage(topicId, user)) {
                is AnResult.Err -> { uiError = saved.error.toUiText(); return }
                is AnResult.Ok -> Unit
            }
            if (turn != generation) return
            val history = initial.messages + user
            _uiState.value = _uiState.value.copy(messages = history, pendingFiles = initial.pendingFiles - files)
            val providerId = requireNotNull(model.providerId)
            val buffer = ChatResponseBuffer(topicId, providerId, model.id)
            response = buffer
            activeResponse = buffer
            var lastEmit = 0L
            completionRepository.stream(providerId, model.id, systemPrompt, history, temperature,
                sessionId = topicId, searchPolicy = searchPolicy, purePrompt = purePrompt).collect { chunk ->
                buffer.append(chunk)
                val now = System.currentTimeMillis()
                if (turn == generation && (now - lastEmit >= 80 || chunk.searchActivity != null || chunk.citations.isNotEmpty())) {
                    lastEmit = now
                    _uiState.value = _uiState.value.copy(streamingText = buffer.text, streamingReasoning = buffer.reasoning,
                        streamingReasoningSecs = buffer.reasoningDurationSeconds(),
                        streamingSearchActivity = buffer.searchActivity, streamingCitations = buffer.citations)
                }
            }
            // The repository must explicitly confirm completion; a bare Flow EOF is not success.
            if (buffer.completion == null) buffer.finish(ChatCompletion(ChatCompletionStatus.INTERRUPTED))
        } catch (e: CancellationException) {
            // stop() already recorded STOPPED: never downgrade an explicit user stop to INTERRUPTED.
            if (response?.completion == null) response?.finish(ChatCompletion(ChatCompletionStatus.INTERRUPTED))
            throw e
        } catch (e: Exception) {
            val buffer = response
            if (buffer == null) {
                uiError = UiText.rawOrNull(e.message) ?: UiText.Res(R.string.chat_stream_interrupted)
            } else {
                val completion = (e as? ChatStreamException)?.completion ?: ChatCompletion(
                    if (e is IOException && buffer.hasContent) ChatCompletionStatus.INTERRUPTED else ChatCompletionStatus.FAILED,
                )
                buffer.finish(completion)
                if (buffer.completion?.status != ChatCompletionStatus.STOPPED && e !is ChatStreamException) failureDetail = e.message
                if (completion.status == ChatCompletionStatus.FAILED) failureDetail = e.message
            }
        } finally {
            val buffer = response
            val assistant = buffer?.snapshot(failureDetail)
            val saved = if (buffer != null && assistant != null) withContext(NonCancellable) {
                messageRepository.saveMessage(buffer.topicId, assistant)
            } else null
            if (turn == generation) {
                val state = _uiState.value
                _uiState.value = state.copy(
                    messages = if (assistant != null && state.activeTopicId == buffer?.topicId) state.messages + assistant else state.messages,
                    streaming = false, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null,
                    streamingSearchActivity = null, streamingCitations = emptyList(),
                    error = if (saved is AnResult.Err) saved.error.toUiText() else uiError,
                )
                activeResponse = null
                streamJob = null
                if (assistant != null) loadTopics()
            }
        }
    }

    private fun effectiveAgentId(): String? = agentId.takeIf(String::isNotBlank)
}
