package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.CatalogSeeder
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.ModelRow
import cc.jaxy.bob.core.data.local.ReasoningRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
data class ReasoningConfig(val effort: String? = null, val reasoningEffort: String? = null, val thinkingLevel: String? = null, val raw: Map<String, String> = emptyMap())
interface ModelRepository {
    suspend fun listModels(): AnResult<List<AIModel>>
    suspend fun listProviderModels(providerId: String): AnResult<List<AIModel>>
    suspend fun toggleModel(modelId: String, providerId: String, enabled: Boolean): AnResult<Unit>
    suspend fun getReasoningConfig(modelId: String, providerId: String): AnResult<ReasoningConfig>
    suspend fun updateReasoningConfig(modelId: String, providerId: String, value: Map<String, String>): AnResult<Unit>
}

@Singleton
class ModelRepositoryImpl @Inject constructor(private val dao: LocalDao, private val seeder: CatalogSeeder) : ModelRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ModelRow.asModel(providerName: String?, providerEnabled: Boolean): AIModel {
        val catalogMetadata = runCatching {
            json.decodeFromString<ModelCatalogMetadata>(this.metadata)
        }.getOrDefault(ModelCatalogMetadata())
        return AIModel(
            id = id,
            displayName = displayName,
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
        dao.models().map { row ->
            val provider = providers[row.providerId]
            row.asModel(provider?.name, provider?.enabled == true)
        }
    }

    override suspend fun listProviderModels(providerId: String): AnResult<List<AIModel>> = attempt {
        seeder.ensureSeeded()
        val provider = dao.provider(providerId)
        dao.modelsFor(providerId).map { it.asModel(provider?.name, provider?.enabled == true) }
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
