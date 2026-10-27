package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.CatalogSeeder
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.ModelRow
import cc.jaxy.bob.core.data.local.ReasoningRow
import cc.jaxy.bob.core.data.model.finalizeModelMetadata
import cc.jaxy.bob.core.data.model.inferredModelMetadata
import cc.jaxy.bob.core.data.model.mergeModelMetadata
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@kotlinx.serialization.Serializable
data class ReasoningConfig(val effort: String? = null, val reasoningEffort: String? = null, val thinkingLevel: String? = null, val raw: Map<String, String> = emptyMap())
interface ModelRepository {
    suspend fun listModels(): AnResult<List<AIModel>>
    /** Enabled chat models for the conversation model picker. */
    suspend fun listChatModels(): AnResult<List<AIModel>>
    suspend fun listProviderModels(providerId: String): AnResult<List<AIModel>>
    suspend fun toggleModel(modelId: String, providerId: String, enabled: Boolean): AnResult<Unit>
    suspend fun getReasoningConfig(modelId: String, providerId: String): AnResult<ReasoningConfig>
    suspend fun updateReasoningConfig(modelId: String, providerId: String, value: Map<String, String>): AnResult<Unit>
}

@Singleton
class ModelRepositoryImpl @Inject constructor(private val dao: LocalDao, private val seeder: CatalogSeeder) : ModelRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ModelRow.asModel(providerName: String?, providerEnabled: Boolean): AIModel {
        val storedMetadata = runCatching { json.decodeFromString<JsonObject>(this.metadata) }
            .getOrDefault(JsonObject(emptyMap()))
        // Providers often return only IDs. Fill what is missing from the bundled
        // catalogue and conservative ID inference so existing fetched rows show
        // family/abilities/reasoning controls without requiring a re-fetch.
        val inferred = inferredModelMetadata(id)
        val enriched = finalizeModelMetadata(
            mergeModelMetadata(
                storedMetadata,
                runCatching { seeder.referenceModelMetadata(id, providerId) }.getOrNull(),
                inferred,
            ),
            stored = storedMetadata,
            inferred = inferred,
        )
        val catalogMetadata = runCatching {
            json.decodeFromString<ModelCatalogMetadata>(enriched.toString())
        }.getOrDefault(ModelCatalogMetadata())
        val resolvedDisplayName = displayName.takeIf { it.isNotBlank() && it != id }
            ?: (enriched["displayName"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: id
        return AIModel(
            id = id,
            displayName = resolvedDisplayName,
            providerId = providerId,
            providerName = providerName,
            enabled = enabled,
            providerEnabled = providerEnabled,
            type = catalogMetadata.type,
            description = catalogMetadata.description,
            contextWindowTokens = catalogMetadata.contextWindowTokens,
            maxOutput = catalogMetadata.maxOutput,
            abilities = catalogMetadata.abilities,
            family = catalogMetadata.family,
            generation = catalogMetadata.generation,
            knowledgeCutoff = catalogMetadata.knowledgeCutoff,
            chatSupported = catalogMetadata.chatSupported,
        )
    }

    override suspend fun listModels(): AnResult<List<AIModel>> = attempt {
        seeder.ensureSeeded()
        val providers = dao.providers().associateBy { it.id }
        val rows = dao.models()
        // Metadata enrichment (JSON, catalogue lookup, ID parsing) must never
        // run on the main dispatcher: the bundled catalogue has ~1.9k models
        // and this is loaded when a chat screen opens.
        withContext(Dispatchers.Default) {
            rows.map { row ->
                val provider = providers[row.providerId]
                row.asModel(provider?.name, provider?.enabled == true)
            }
        }
    }

    override suspend fun listChatModels(): AnResult<List<AIModel>> = attempt {
        seeder.ensureSeeded()
        val providers = dao.providers().associateBy { it.id }
        val rows = dao.enabledModels()
        // The chat screen only needs selectable models; avoid decoding and
        // enriching the full ~1.9k catalogue entry list on every open.
        withContext(Dispatchers.Default) {
            rows.mapNotNull { row ->
                val provider = providers[row.providerId]?.takeIf { it.enabled } ?: return@mapNotNull null
                row.asModel(provider.name, true).takeIf { it.chatSupported }
            }
        }
    }

    override suspend fun listProviderModels(providerId: String): AnResult<List<AIModel>> = attempt {
        seeder.ensureSeeded()
        val provider = dao.provider(providerId)
        val rows = dao.modelsFor(providerId)
        withContext(Dispatchers.Default) {
            rows.map { it.asModel(provider?.name, provider?.enabled == true) }
        }
    }


    override suspend fun toggleModel(modelId: String, providerId: String, enabled: Boolean): AnResult<Unit> = attempt {
        val old = dao.model(providerId, modelId) ?: error("Model not found")
        val metadata = runCatching {
            json.decodeFromString<ModelCatalogMetadata>(old.metadata)
        }.getOrDefault(ModelCatalogMetadata())
        require(!enabled || metadata.chatSupported) { "This model has no local chat adapter" }
        dao.putModel(old.copy(enabled = enabled))
    }
    override suspend fun getReasoningConfig(modelId: String, providerId: String): AnResult<ReasoningConfig> = attempt {
        val stored = dao.reasoning(providerId, modelId)?.value ?: return@attempt ReasoningConfig()
        json.decodeFromString<ReasoningConfig>(stored)
    }
    override suspend fun updateReasoningConfig(modelId: String, providerId: String, value: Map<String, String>): AnResult<Unit> = attempt {
        val config = ReasoningConfig(value["effort"], value["reasoningEffort"], value["thinkingLevel"], value)
        dao.putReasoning(ReasoningRow(providerId, modelId, json.encodeToString(config)))
    }
}
private suspend fun <T> attempt(block: suspend () -> T): AnResult<T> = try { AnResult.Ok(block()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_DATA", e.message ?: "Local data operation failed", e)) }
