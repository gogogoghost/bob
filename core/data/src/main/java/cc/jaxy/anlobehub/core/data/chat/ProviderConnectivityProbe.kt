package cc.jaxy.anlobehub.core.data.chat

import java.util.UUID
import okhttp3.OkHttpClient

/** Connectivity means the chosen model can answer, not merely that /models exists. */
internal class ProviderConnectivityProbe(private val client: OkHttpClient = OkHttpClient()) {
    private val requests = ProviderChatRequests(client = client)
    suspend fun check(providerId: String, config: Map<String, String>, modelId: String) {
        require(modelId.isNotBlank()) { "Choose a model to check connectivity" }
        val request = requests.prepare(
            providerId = providerId,
            source = config["__source"] ?: "builtin",
            config = config,
            modelId = modelId,
            systemPrompt = "",
            history = listOf(ChatMessage(role = "user", content = "Hi")),
            temperature = null,
            sessionId = UUID.randomUUID().toString(),
        )
        executeProviderRequest(client, request) { chunk ->
            chunk.completion?.takeIf { it.status != ChatCompletionStatus.COMPLETED }?.let { result ->
                throw ChatStreamException(result, "Provider check did not complete: ${result.finishReason ?: result.status}")
            }
        }
    }
}
