package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ChatTopic(
    val id: String = "",
    val title: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val agentId: String? = null,
)

@Serializable
data class ChatAttachment(
    val id: String = "",
    val name: String = "",
    val mimeType: String = "application/octet-stream",
    val uri: String = "",
)

@Serializable
data class ChatMessage(
    val id: String = "",
    val role: String = "assistant",
    val content: String = "",
    val reasoning: String? = null,
    val reasoningDuration: Double? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val model: String? = null,
    val provider: String? = null,
    val error: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    /** Opaque Responses reasoning items; replay only to the same provider and model. */
    val providerContext: List<JsonObject> = emptyList(),
    /** Null for messages created before completion tracking was introduced. */
    val completion: ChatCompletion? = null,
    val searchActivity: WebSearchActivity? = null,
    val citations: List<WebCitation> = emptyList(),
)

@Serializable
data class ModelAbilities(
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val functionCall: Boolean = false,
    val files: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
    val imageOutput: Boolean = false,
    val search: Boolean = false,
    val structuredOutput: Boolean = false,
)

@Serializable
data class AIModel(
    val id: String = "",
    val displayName: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val enabled: Boolean? = null,
    val providerEnabled: Boolean = true,
    val type: String? = null,
    val description: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutput: Int? = null,
    val abilities: ModelAbilities = ModelAbilities(),
    val family: String? = null,
    val generation: String? = null,
    val knowledgeCutoff: String? = null,
    val chatSupported: Boolean = type.isNullOrBlank() || type.equals("chat", ignoreCase = true),
)

@Serializable
data class ModelCatalogMetadata(
    val type: String? = null,
    val description: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutput: Int? = null,
    val abilities: ModelAbilities = ModelAbilities(),
    val family: String? = null,
    val generation: String? = null,
    val knowledgeCutoff: String? = null,
    val chatSupported: Boolean = type.isNullOrBlank() || type.equals("chat", ignoreCase = true),
)

data class TopicPage(val items: List<ChatTopic> = emptyList(), val total: Int? = null)

