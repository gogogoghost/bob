package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.MessageRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface MessageRepository {
    suspend fun listMessages(topicId: String? = null, agentId: String? = null): AnResult<List<ChatMessage>>
    suspend fun saveMessage(topicId: String, message: ChatMessage): AnResult<Unit>
}

@Singleton
class MessageRepositoryImpl @Inject constructor(private val dao: LocalDao) : MessageRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun listMessages(topicId: String?, agentId: String?): AnResult<List<ChatMessage>> = try {
        AnResult.Ok(dao.messages(topicId, agentId).map { json.decodeFromString<ChatMessage>(it.payload) })
    } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_DATA", e.message ?: "Unable to read messages", e)) }
    override suspend fun saveMessage(topicId: String, message: ChatMessage): AnResult<Unit> = try {
        val normalized = message.copy(createdAt = message.createdAt ?: System.currentTimeMillis())
        val topic = dao.topic(topicId) ?: error("Topic not found")
        val createdAt = normalized.createdAt ?: System.currentTimeMillis()
        val id = normalized.id.ifBlank { java.util.UUID.randomUUID().toString() }
        dao.putMessageAndTouchTopic(
            MessageRow(id, topicId, topic.agentId, createdAt, json.encodeToString(normalized.copy(id = id))),
            updatedAt = maxOf(topic.updatedAt, createdAt),
        )
        AnResult.Ok(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_DATA", e.message ?: "Unable to save message", e)) }
}
