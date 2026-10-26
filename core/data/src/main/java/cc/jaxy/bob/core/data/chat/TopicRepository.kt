package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.TopicRow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface TopicRepository {
    suspend fun listTopics(agentId: String? = null): AnResult<List<ChatTopic>>
    suspend fun pageTopics(page: Int, pageSize: Int, agentId: String? = null): AnResult<TopicPage>
    suspend fun createTopic(title: String, agentId: String? = null): AnResult<String>
    suspend fun deleteTopic(id: String): AnResult<Unit>
}

@Singleton
class TopicRepositoryImpl @Inject constructor(private val dao: LocalDao) : TopicRepository {
    override suspend fun listTopics(agentId: String?): AnResult<List<ChatTopic>> = wrap { dao.topics(agentId).map { ChatTopic(it.id, it.title, it.createdAt, it.updatedAt, it.agentId) } }
    override suspend fun pageTopics(page: Int, pageSize: Int, agentId: String?): AnResult<TopicPage> = wrap {
        require(page >= 0 && pageSize > 0) { "page must be nonnegative and pageSize must be positive" }
        val all = dao.topics(agentId)
        val start = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
        TopicPage(all.drop(start).take(pageSize).map { ChatTopic(it.id, it.title, it.createdAt, it.updatedAt, it.agentId) }, all.size)
    }
    override suspend fun createTopic(title: String, agentId: String?): AnResult<String> {
        if (title.isBlank()) return AnResult.Err(AnError("INVALID_INPUT", "empty title"))
        return wrap { val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString(); dao.putTopic(TopicRow(id, title, now, now, agentId)); id }
    }
    override suspend fun deleteTopic(id: String): AnResult<Unit> = wrap { dao.deleteTopicWithMessages(id) }
}
private suspend fun <T> wrap(block: suspend () -> T): AnResult<T> = try { AnResult.Ok(block()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_DATA", e.message ?: "Local operation failed", e)) }
