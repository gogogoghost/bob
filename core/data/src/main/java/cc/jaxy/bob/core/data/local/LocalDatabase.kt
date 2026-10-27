package cc.jaxy.bob.core.data.local
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "agents")
data class AgentRow(@androidx.room.PrimaryKey val id: String, val payload: String)
@Entity(tableName = "topics", indices = [androidx.room.Index("agentId")])
data class TopicRow(@androidx.room.PrimaryKey val id: String, val title: String?, val createdAt: Long, val updatedAt: Long, val agentId: String?)
@Entity(tableName = "messages", indices = [androidx.room.Index("topicId"), androidx.room.Index("agentId")])
data class MessageRow(@androidx.room.PrimaryKey val id: String, val topicId: String, val agentId: String?, val createdAt: Long, val payload: String)
@Entity(tableName = "providers")
data class ProviderRow(@androidx.room.PrimaryKey val id: String, val name: String, val source: String, val enabled: Boolean, val config: String)
@Entity(tableName = "models", primaryKeys = ["providerId", "id"])
data class ModelRow(val id: String, val providerId: String, val displayName: String, val enabled: Boolean, val metadata: String = "{}")
@Entity(tableName = "reasoning", primaryKeys = ["providerId", "modelId"])
data class ReasoningRow(val providerId: String, val modelId: String, val value: String)

@Dao
interface LocalDao {
    @Query("SELECT * FROM agents ORDER BY id") suspend fun agents(): List<AgentRow>
    @Query("SELECT * FROM agents WHERE id=:id") suspend fun agent(id: String): AgentRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAgent(row: AgentRow)
    @Query("DELETE FROM agents WHERE id=:id") suspend fun deleteAgent(id: String)
    @Query("SELECT * FROM topics WHERE (:agentId IS NULL OR agentId=:agentId) ORDER BY updatedAt DESC") suspend fun topics(agentId: String?): List<TopicRow>
    @Query("SELECT * FROM topics WHERE id=:id") suspend fun topic(id: String): TopicRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTopic(row: TopicRow)
    @Query("UPDATE topics SET updatedAt=:updatedAt WHERE id=:topicId") suspend fun touchTopic(topicId: String, updatedAt: Long)
    @Transaction
    suspend fun putMessageAndTouchTopic(row: MessageRow, updatedAt: Long) {
        putMessage(row)
        touchTopic(row.topicId, updatedAt)
    }
    @Query("DELETE FROM topics WHERE id=:id") suspend fun deleteTopic(id: String)
    @Query("DELETE FROM topics WHERE agentId=:agentId") suspend fun deleteAgentTopics(agentId: String)
    @Query("DELETE FROM messages WHERE agentId=:agentId") suspend fun deleteAgentMessages(agentId: String)
    @Transaction
    suspend fun removeAgent(agentId: String) {
        deleteAgentMessages(agentId)
        deleteAgentTopics(agentId)
        deleteAgent(agentId)
    }
    @Query("SELECT * FROM messages WHERE (:topicId IS NULL OR topicId=:topicId) AND (:agentId IS NULL OR agentId=:agentId) ORDER BY createdAt, id") suspend fun messages(topicId: String?, agentId: String?): List<MessageRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMessage(row: MessageRow)
    @Query("DELETE FROM messages WHERE topicId=:topicId") suspend fun deleteTopicMessages(topicId: String)
    @Query("SELECT * FROM providers ORDER BY name") suspend fun providers(): List<ProviderRow>
    @Transaction
    suspend fun deleteTopicWithMessages(topicId: String) {
        deleteTopicMessages(topicId)
        deleteTopic(topicId)
    }
    @Query("SELECT * FROM providers WHERE id=:id") suspend fun provider(id: String): ProviderRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProvider(row: ProviderRow)
    @Query("SELECT * FROM models ORDER BY displayName") suspend fun models(): List<ModelRow>
    @Query("SELECT * FROM models WHERE providerId=:providerId ORDER BY displayName") suspend fun modelsFor(providerId: String): List<ModelRow>
    @Query("SELECT * FROM models WHERE enabled = 1 ORDER BY displayName") suspend fun enabledModels(): List<ModelRow>
    @Query("SELECT * FROM models WHERE providerId=:providerId AND id=:id") suspend fun model(providerId: String, id: String): ModelRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putModel(row: ModelRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putModels(rows: List<ModelRow>)
    @Query("SELECT * FROM reasoning WHERE providerId=:providerId AND modelId=:modelId") suspend fun reasoning(providerId: String, modelId: String): ReasoningRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReasoning(row: ReasoningRow)
}

@Database(entities = [AgentRow::class, TopicRow::class, MessageRow::class, ProviderRow::class, ModelRow::class, ReasoningRow::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() { abstract fun dao(): LocalDao }
