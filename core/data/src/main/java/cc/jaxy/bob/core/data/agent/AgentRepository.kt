package cc.jaxy.bob.core.data.agent

import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.AgentRow
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Serializable
data class Agent(val id: String = "", val title: String? = null, val name: String? = null, val description: String? = null, val avatar: String? = null, val backgroundColor: String? = null) {
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: title?.takeIf(String::isNotBlank) ?: id
}
@Serializable
data class AgentConfig(val title: String? = null, val description: String? = null, val systemRole: String? = null, val model: String? = null, val provider: String? = null, val temperature: Double? = null, val avatar: String? = null, val backgroundColor: String? = null, val openingMessage: String? = null, val openingQuestions: List<String> = emptyList(), val webSearch: WebSearchPolicy? = null, val purePrompt: Boolean = false)

interface AgentRepository {
    suspend fun listAgents(keyword: String? = null): AnResult<List<Agent>>
    suspend fun getConfig(agentId: String): AnResult<AgentConfig>
    suspend fun create(title: String, systemRole: String? = null, model: String? = null, provider: String? = null, description: String? = null, avatar: String? = null, openingMessage: String? = null, openingQuestions: List<String> = emptyList(), webSearch: WebSearchPolicy? = null, purePrompt: Boolean = false): AnResult<String>
    suspend fun updateConfig(agentId: String, fields: Map<String, JsonElement?>): AnResult<Unit>
    suspend fun remove(agentId: String): AnResult<Unit>
    suspend fun duplicate(agentId: String): AnResult<String>
}

@Singleton
class AgentRepositoryImpl @Inject constructor(private val dao: LocalDao, private val searchProviders: WebSearchProviderRepository) : AgentRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class StoredAgent(val agent: Agent, val config: AgentConfig)

    /** Rewrite only the migrated field, retaining unknown fields in existing records. */
    private suspend fun read(row: AgentRow): JsonObject {
        val root = json.parseToJsonElement(row.payload).jsonObject
        val config = root.getValue("config").jsonObject
        if ("webSearchEnabled" !in config && "webSearch" in config) return root
        val legacy = config["webSearchEnabled"]
        val enabled = when (legacy) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                require(!legacy.isString && legacy.booleanOrNull != null) { "webSearchEnabled must be a boolean or null" }
                legacy.booleanOrNull
            }
            else -> error("webSearchEnabled must be a boolean or null")
        }
        val policy = if ("webSearch" in config) config.getValue("webSearch") else {
            val choice = when (enabled) {
                null -> null
                false -> WebSearchPolicy()
                true -> if (searchProviders.providers.first().any { it.id == "searxng" && it.endpoint.isNotBlank() }) {
                    WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
                } else WebSearchPolicy(WebSearchMode.NATIVE)
            }
            choice?.let { json.encodeToJsonElement(it) } ?: JsonNull
        }
        val migrated = JsonObject(config - "webSearchEnabled" + ("webSearch" to policy))
        val next = JsonObject(root + ("config" to migrated))
        // Validate before persisting, including canonical policy constraints.
        json.decodeFromJsonElement<StoredAgent>(next)
        dao.putAgent(AgentRow(row.id, next.toString()))
        return next
    }

    override suspend fun listAgents(keyword: String?): AnResult<List<Agent>> = runCatching {
        dao.agents().map { json.decodeFromJsonElement<StoredAgent>(read(it)).agent }
            .filter { keyword.isNullOrBlank() || it.displayName.contains(keyword, true) || it.description.orEmpty().contains(keyword, true) }
            .sortedBy { it.displayName.lowercase() }
    }.toResult()
    override suspend fun getConfig(agentId: String): AnResult<AgentConfig> = runCatching {
        json.decodeFromJsonElement<StoredAgent>(read(dao.agent(agentId) ?: error("Agent not found"))).config
    }.toResult()
    override suspend fun create(title: String, systemRole: String?, model: String?, provider: String?, description: String?, avatar: String?, openingMessage: String?, openingQuestions: List<String>, webSearch: WebSearchPolicy?, purePrompt: Boolean): AnResult<String> {
        if (title.isBlank()) return AnResult.Err(AnError("INVALID_INPUT", "title is required"))
        val id = UUID.randomUUID().toString()
        val config = AgentConfig(title, description, systemRole, model, provider, avatar = avatar, openingMessage = openingMessage, openingQuestions = openingQuestions.filter(String::isNotBlank), webSearch = webSearch, purePrompt = purePrompt)
        val agent = Agent(id, title = title, description = description, avatar = avatar)
        return runCatching { dao.putAgent(AgentRow(id, json.encodeToString(StoredAgent(agent, config)))); id }.toResult()
    }
    override suspend fun updateConfig(agentId: String, fields: Map<String, JsonElement?>): AnResult<Unit> = runCatching {
        require("webSearchEnabled" !in fields) { "Use webSearch policy" }
        val root = read(dao.agent(agentId) ?: error("Agent not found"))
        val old = root.getValue("config").jsonObject
        val config = JsonObject(old + fields.mapValues { it.value ?: JsonNull })
        val next = json.decodeFromJsonElement<AgentConfig>(config)
        val stored = json.decodeFromJsonElement<StoredAgent>(root)
        val agent = stored.agent.copy(title = next.title, description = next.description, avatar = next.avatar, backgroundColor = next.backgroundColor)
        dao.putAgent(AgentRow(agentId, JsonObject(root + mapOf("agent" to json.encodeToJsonElement(agent), "config" to config)).toString()))
    }.toResult()
    override suspend fun remove(agentId: String): AnResult<Unit> = runCatching { dao.removeAgent(agentId) }.toResult()
    override suspend fun duplicate(agentId: String): AnResult<String> = runCatching {
        val root = read(dao.agent(agentId) ?: error("Agent not found"))
        val source = json.decodeFromJsonElement<StoredAgent>(root)
        val id = UUID.randomUUID().toString()
        val title = "${source.config.title ?: source.agent.displayName} (copy)"
        val config = JsonObject(root.getValue("config").jsonObject + ("title" to JsonPrimitive(title)))
        val agent = json.encodeToJsonElement(source.agent.copy(id = id, title = title))
        dao.putAgent(AgentRow(id, JsonObject(root + mapOf("agent" to agent, "config" to config)).toString()))
        id
    }.toResult()
}

private fun <T> Result<T>.toResult(): AnResult<T> = fold({ AnResult.Ok(it) }, { if (it is kotlinx.coroutines.CancellationException) throw it else AnResult.Err(AnError("LOCAL_DATA", it.message ?: "Local data operation failed", it)) })
