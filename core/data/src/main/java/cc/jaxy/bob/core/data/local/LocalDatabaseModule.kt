package cc.jaxy.bob.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import cc.jaxy.bob.core.common.util.providerAdapterType
import cc.jaxy.bob.core.common.util.providerDefaultEndpoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val ACTIVATION_DEFAULTS_VERSION_KEY = "__localActivationDefaultsVersion"
internal const val ACTIVATION_DEFAULTS_VERSION = "1"

@Serializable
private data class BundledCatalog(
    val source: JsonObject = JsonObject(emptyMap()),
    val providers: List<BundledProvider> = emptyList(),
    val models: List<JsonObject> = emptyList(),
)

@Serializable
private data class BundledProvider(
    val id: String,
    val name: String,
    val description: String? = null,
    val url: String? = null,
    val apiKeyUrl: String? = null,
    val modelsUrl: String? = null,
    val checkModel: String? = null,
    val proxyPlaceholder: String? = null,
    val showApiKey: Boolean = true,
    val authType: String? = null,
    val protocol: String? = null,
    val showChecker: Boolean = true,
    val showModelFetcher: Boolean = false,
    val chatSupported: Boolean = false,
    val adapterType: String? = null,
    val modelCount: Int = 0,
)

private const val CATALOG_ASSET = "provider_catalog.json"

@Module
@InstallIn(SingletonComponent::class)
object LocalDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LocalDatabase {
        lateinit var database: LocalDatabase
        database = Room.databaseBuilder(context, LocalDatabase::class.java, "local_agent.db")
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        CatalogSeeder(database.dao()) { loadCatalog(context) }.ensureSeeded()
                    }
                }
            })
            .build()
        return database
    }

    @Provides
    fun localDao(database: LocalDatabase): LocalDao = database.dao()

    @Provides
    @Singleton
    fun seedCatalog(
        dao: LocalDao,
        @ApplicationContext context: Context,
    ): CatalogSeeder = CatalogSeeder(dao) { loadCatalog(context) }

    private fun loadCatalog(context: Context): String =
        context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
}

@Singleton
class CatalogSeeder(
    private val dao: LocalDao,
    private val loadCatalog: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val bundledCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        json.decodeFromString<BundledCatalog>(loadCatalog())
    }

    @Volatile
    private var seeded = false

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        seedMutex.withLock {
            if (seeded) return@withLock

            val catalog = bundledCatalog
            val supplementalModels = supplementalKimiModels(catalog)
            val revision = catalog.source.string("commit") ?: "unknown"
            val existingProviders = dao.providers()
            val oldProviders = existingProviders.associateBy { it.id }
            val oldProviderConfigs = existingProviders.associate { row ->
                row.id to runCatching { json.decodeFromString<Map<String, String>>(row.config) }
                    .getOrDefault(emptyMap())
            }
            val existingModels = dao.models()
            val originalModels = existingModels.associateBy { it.providerId to it.id }

            val providers = catalog.providers.mapNotNull { provider ->
                if (provider.id == "lobehub") return@mapNotNull null
                val old = oldProviders[provider.id]
                val config = oldProviderConfigs[provider.id].orEmpty().toMutableMap()
                if (old?.source == "custom") {
                    config[ACTIVATION_DEFAULTS_VERSION_KEY] = ACTIVATION_DEFAULTS_VERSION
                    return@mapNotNull old.copy(
                        enabled = old.enabled,
                        config = json.encodeToString(config),
                    )
                }

                if (config["baseURL"].isNullOrBlank()) {
                    providerDefaultEndpoint(provider.id)?.let { config["baseURL"] = it }
                }
                val oldEndpoint = config["baseURL"]?.trimEnd('/')
                val migrateEndpoint = when (provider.id) {
                    "opencodecodingplan" -> oldEndpoint == "https://opencode.ai/zen/go/v1"
                    "opencodezen" -> oldEndpoint == "https://opencode.ai/zen/v1"
                    "straico" -> oldEndpoint == "https://api.straico.com/v0" || oldEndpoint == "https://api.straico.com/v1"
                    else -> false
                }
                if (migrateEndpoint) {
                    providerDefaultEndpoint(provider.id)?.let { config["baseURL"] = it }
                }
                config["__catalogRevision"] = revision
                config["__catalogDescription"] = provider.description.orEmpty()
                config["__catalogUrl"] = provider.url.orEmpty()
                config["__catalogApiKeyUrl"] = provider.apiKeyUrl.orEmpty()
                config["__catalogModelsUrl"] = provider.modelsUrl.orEmpty()
                config["__catalogProxyPlaceholder"] = provider.proxyPlaceholder.orEmpty()
                config["__catalogShowApiKey"] = provider.showApiKey.toString()
                config["__catalogAuthType"] = provider.authType.orEmpty()
                config["__catalogProtocol"] = provider.protocol.orEmpty()
                config["__catalogShowChecker"] = provider.showChecker.toString()
                config["__catalogShowModelFetcher"] = provider.showModelFetcher.toString()
                config["__catalogChatSupported"] = (localAdapterType(provider) != null).toString()
                config["__catalogModelCount"] = (provider.modelCount + if (provider.id == "kimicodingplan") supplementalModels.size else 0).toString()
                config["__catalogCheckModel"] = if (provider.id == "kimicodingplan") "kimi-for-coding" else provider.checkModel.orEmpty()
                config[ACTIVATION_DEFAULTS_VERSION_KEY] = ACTIVATION_DEFAULTS_VERSION
                localAdapterType(provider)?.let { config["__sdkType"] = it } ?: config.remove("__sdkType")

                ProviderRow(
                    id = provider.id,
                    name = provider.name,
                    source = "builtin",
                    enabled = old?.enabled ?: false,
                    config = json.encodeToString(config),
                )
            }
            for (provider in providers) {
                if (oldProviders[provider.id] != provider) dao.putProvider(provider)
            }
            val seededProviderIds = providers.mapTo(mutableSetOf()) { it.id }
            for (old in existingProviders) {
                if (old.source != "custom" || old.id in seededProviderIds) continue
                val config = oldProviderConfigs[old.id].orEmpty().toMutableMap()
                config[ACTIVATION_DEFAULTS_VERSION_KEY] = ACTIVATION_DEFAULTS_VERSION
                val updated = old.copy(
                    enabled = old.enabled,
                    config = json.encodeToString(config),
                )
                if (updated != old) dao.putProvider(updated)
            }

            val normalizedModels = existingModels.map { row ->
                val metadata = runCatching { json.decodeFromString<JsonObject>(row.metadata) }
                    .getOrDefault(JsonObject(emptyMap()))
                val activationVersion = metadata.string(ACTIVATION_DEFAULTS_VERSION_KEY)
                val updatedMetadata = if (activationVersion == ACTIVATION_DEFAULTS_VERSION) {
                    row.metadata
                } else {
                    json.encodeToString(
                        JsonObject(metadata + (ACTIVATION_DEFAULTS_VERSION_KEY to JsonPrimitive(ACTIVATION_DEFAULTS_VERSION))),
                    )
                }
                row.copy(
                    enabled = row.enabled,
                    metadata = updatedMetadata,
                )
            }
            val oldModels = normalizedModels.associateBy { it.providerId to it.id }
            val providerSupport = catalog.providers.associate { it.id to (localAdapterType(it) != null) }
            val models = (catalog.models + supplementalModels).mapNotNull { model ->
                val id = model.string("id") ?: return@mapNotNull null
                val providerId = model.string("providerId") ?: return@mapNotNull null
                if (providerId == "lobehub") return@mapNotNull null
                val chatSupported = providerSupport[providerId] == true && model.string("type") == "chat"
                val old = oldModels[providerId to id]
                val metadata = JsonObject(
                    model +
                        ("chatSupported" to JsonPrimitive(chatSupported)) +
                        ("catalogRevision" to JsonPrimitive(revision)) +
                        (ACTIVATION_DEFAULTS_VERSION_KEY to JsonPrimitive(ACTIVATION_DEFAULTS_VERSION)),
                )
                ModelRow(
                    id = id,
                    providerId = providerId,
                    displayName = model.string("displayName")?.takeIf(String::isNotBlank) ?: id,
                    enabled = if (chatSupported) old?.enabled ?: false else false,
                    metadata = json.encodeToString(metadata),
                )
            }
            val catalogModelKeys = models.mapTo(mutableSetOf()) { it.providerId to it.id }
            val updatedModels = models + normalizedModels.filter { (it.providerId to it.id) !in catalogModelKeys }
            val changedModels = updatedModels.filter { originalModels[it.providerId to it.id] != it }
            if (changedModels.isNotEmpty()) dao.putModels(changedModels)
            seeded = true
        }
    }

    private fun localAdapterType(provider: BundledProvider): String? =
        providerAdapterType(provider.id, provider.protocol, provider.adapterType)

    private fun supplementalKimiModels(catalog: BundledCatalog): List<JsonObject> {
        if (catalog.providers.none { it.id == "kimicodingplan" }) return emptyList()
        val existing = catalog.models.asSequence().filter { it.string("providerId") == "kimicodingplan" }
            .mapNotNull { it.string("id") }.toHashSet()
        // Current accepted IDs: https://www.kimi.com/code/docs/en/ (legacy bindings stay intact).
        return listOf("k3", "k3-256k", "kimi-for-coding", "kimi-for-coding-highspeed")
            .filterNot { it in existing }.map { id ->
                JsonObject(mapOf(
                    "id" to JsonPrimitive(id), "providerId" to JsonPrimitive("kimicodingplan"),
                    "displayName" to JsonPrimitive(id), "type" to JsonPrimitive("chat"),
                ))
            }
    }

    private companion object {
        val seedMutex = Mutex()
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

