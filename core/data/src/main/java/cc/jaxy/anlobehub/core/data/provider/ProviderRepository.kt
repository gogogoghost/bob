package cc.jaxy.anlobehub.core.data.provider

import cc.jaxy.anlobehub.core.common.result.AnError
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.common.util.FieldType
import cc.jaxy.anlobehub.core.common.util.buildModelsUrl
import cc.jaxy.anlobehub.core.common.util.providerConfigSpec
import cc.jaxy.anlobehub.core.common.util.providerAdapterType
import cc.jaxy.anlobehub.core.common.util.providerDefaultEndpoint
import cc.jaxy.anlobehub.core.data.chat.CloudProviderRequests
import cc.jaxy.anlobehub.core.data.chat.ProviderConnectivityProbe
import cc.jaxy.anlobehub.core.common.util.specialFieldsFor
import cc.jaxy.anlobehub.core.data.local.CatalogSeeder
import cc.jaxy.anlobehub.core.data.local.ACTIVATION_DEFAULTS_VERSION
import cc.jaxy.anlobehub.core.data.local.ACTIVATION_DEFAULTS_VERSION_KEY
import cc.jaxy.anlobehub.core.data.local.LocalDao
import cc.jaxy.anlobehub.core.data.local.ModelRow
import cc.jaxy.anlobehub.core.data.local.ProviderRow
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AiProvider(
    val id: String = "",
    val name: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val source: String? = null,
    val chatSupported: Boolean = true,
)
data class ProviderDetail(
    val id: String = "",
    val name: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val source: String? = null,
    val checkModel: String? = null,
    val keyVaults: Map<String, String> = emptyMap(),
    val websiteUrl: String? = null,
    val apiKeyUrl: String? = null,
    val modelsUrl: String? = null,
    val endpointPlaceholder: String? = null,
    val showApiKey: Boolean? = null,
    val authType: String? = null,
    val catalogProtocol: String? = null,
    val chatSupported: Boolean = true,
    val showChecker: Boolean = true,
    val showModelFetcher: Boolean = false,
    val modelCount: Int = 0,
) {
    val apiKey: String? get() = keyVaults["apiKey"]
    val baseURL: String? get() = keyVaults["baseURL"]
}
data class CheckResult(val ok: Boolean = false, val error: String? = null)

private val NO_MODEL_LISTING = setOf(
    "azure", "azureai", "bailiancodingplan", "glmcodingplan", "kimicodingplan",
    "minimaxcodingplan", "volcenginecodingplan", "chatgpt", "perplexity", "jina", "spark",
)
private val OAUTH_PROVIDERS = setOf("chatgpt", "githubcopilot", "supergrok")
private val DISCOVERY_ADAPTER_TYPES = setOf("openai", "router", "anthropic", "google", "ollama", "githubcopilot", "supergrok", "straico")
private val CHAT_MODEL_TYPES = setOf("chat", "text", "language", "model", "llm", "t2t")
private val GOOGLE_CHAT_METHODS = setOf("generateContent", "streamGenerateContent")
private val VERSION_PATH = Regex("/v\\d+[a-z]*$")
private const val GENERIC_COMPAT_ENDPOINT_PLACEHOLDER = "https://your-proxy-url.com/v1"

 

interface ProviderRepository {
    suspend fun listProviders(): AnResult<List<AiProvider>>
    suspend fun toggleProvider(id: String, enabled: Boolean): AnResult<Unit>
    suspend fun getDetail(id: String): AnResult<ProviderDetail>
    suspend fun updateConfig(id: String, keyVaults: Map<String, String?> = emptyMap(), checkModel: String? = null): AnResult<Unit>
    suspend fun checkConnectivity(id: String, model: String? = null): AnResult<CheckResult>
    suspend fun fetchRemoteModels(providerId: String, baseURL: String, apiKey: String): AnResult<List<String>>
    suspend fun createCustom(name: String, baseURL: String): AnResult<String>
}

@Singleton
class ProviderRepositoryImpl @Inject constructor(private val dao: LocalDao, private val secrets: ProviderSecretStore, private val seeder: CatalogSeeder) : ProviderRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val cloud = CloudProviderRequests(client)
    private val oauth = ProviderOAuthRepository(secrets)
    private val probe = ProviderConnectivityProbe(client)
    private fun adapter(id: String, source: String, config: Map<String, String>): String? =
        if (source == "custom") config["__sdkType"] ?: "openai"
        else providerAdapterType(id, config["__catalogProtocol"], config["__sdkType"])
    private fun supportsChat(row: ProviderRow, config: Map<String, String>): Boolean =
        adapter(row.id, row.source, config) != null
    private suspend fun invocationConfig(row: ProviderRow): Map<String, String> {
        val stored = json.decodeFromString<Map<String, String>>(row.config)
        val merged = (stored + secrets.read(row.id)).toMutableMap()
        if (merged["baseURL"].isNullOrBlank() && row.source != "custom") {
            providerDefaultEndpoint(row.id)?.let { merged["baseURL"] = it }
        }
        merged["__source"] = row.source
        adapter(row.id, row.source, stored)?.let { merged["__sdkType"] = it }
        if (row.source != "custom" && row.id in OAUTH_PROVIDERS) merged.putAll(oauth.credentials(row.id))
        return merged
    }
    private suspend fun seed() = seeder.ensureSeeded()
    private suspend fun <T> credentialAction(id: String, operation: suspend () -> T): AnResult<T> = action {
        if (id in OAUTH_PROVIDERS) oauth.withCredentialLock(id, operation) else operation()
    }
    private fun secretKeys(id: String, source: String): Set<String> = buildSet {
        val spec = providerConfigSpec(id, source)
        add("apiKey")
        spec.extraFields.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
        spec.authModes.flatMap { it.fields }.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
        specialFieldsFor(id)?.let { fields ->
            fields.apiKeyItems.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
            fields.authModes.flatMap { it.fields }.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
        }
        add("accessKeyId")
        add("username")
        addAll(setOf("oauthAccessToken", "oauthRefreshToken", "oauthTokenExpiresAt", "oauthAccountId",
            "oauthUsername", "oauthBearerToken", "oauthBearerTokenExpiresAt"))
    }
    override suspend fun listProviders(): AnResult<List<AiProvider>> = action {
        seed()
        dao.providers().mapNotNull { row ->
            if (row.id == "lobehub") return@mapNotNull null
            val config = json.decodeFromString<Map<String, String>>(row.config)
            AiProvider(
                id = row.id,
                name = row.name,
                description = config["__catalogDescription"]?.takeIf(String::isNotBlank),
                enabled = row.enabled,
                source = row.source,
                chatSupported = supportsChat(row, config),
            )
        }
    }
    override suspend fun toggleProvider(id: String, enabled: Boolean): AnResult<Unit> = action { seed(); val row = dao.provider(id) ?: error("Provider not found"); dao.putProvider(row.copy(enabled = enabled)) }
    override suspend fun getDetail(id: String): AnResult<ProviderDetail> = credentialAction(id) {
        seed(); val row = dao.provider(id) ?: error("Provider not found")
        val stored = json.decodeFromString<Map<String, String>>(row.config)
        val encrypted = secrets.read(id).toMutableMap()
        val sensitive = secretKeys(id, row.source)
        val legacySecrets = stored.filter { (key, value) -> key in sensitive && value.isNotBlank() }
        val plain = stored.toMutableMap()
        val hadLegacyFetchSetting = plain.remove("__fetchOnClient") != null
        val hadLegacyResponseSetting = plain.remove("__enableResponseApi") != null
        if (legacySecrets.isNotEmpty()) {
            legacySecrets.forEach { (key, value) -> encrypted.putIfAbsent(key, value) }
            secrets.write(id, encrypted)
            legacySecrets.keys.forEach(plain::remove)
        }
        if (legacySecrets.isNotEmpty() || hadLegacyFetchSetting || hadLegacyResponseSetting) {
            dao.putProvider(row.copy(config = json.encodeToString(plain)))
        }
        val config = plain.filterKeys { !it.startsWith("__") && !it.startsWith("oauth") } +
            encrypted.filterKeys { !it.startsWith("oauth") }
        val spec = providerConfigSpec(id, row.source)
        ProviderDetail(
            id = id,
            name = row.name,
            description = stored["__catalogDescription"]?.takeIf(String::isNotBlank),
            enabled = row.enabled,
            source = row.source,
            checkModel = stored["__checkModel"]?.takeIf(String::isNotBlank)
                ?: stored["__catalogCheckModel"]?.takeIf(String::isNotBlank)
                ?: spec.checkModel,
            keyVaults = config,
            websiteUrl = stored["__catalogUrl"]?.takeIf(String::isNotBlank),
            apiKeyUrl = stored["__catalogApiKeyUrl"]?.takeIf(String::isNotBlank),
            modelsUrl = stored["__catalogModelsUrl"]?.takeIf(String::isNotBlank),
            endpointPlaceholder = stored["__catalogProxyPlaceholder"]?.takeIf(String::isNotBlank)
                ?: spec.endpoint?.placeholder
                ?: GENERIC_COMPAT_ENDPOINT_PLACEHOLDER.takeIf { supportsChat(row, stored) },
            showApiKey = stored["__catalogShowApiKey"]?.toBoolean(),
            authType = stored["__catalogAuthType"]?.takeIf(String::isNotBlank),
            catalogProtocol = stored["__catalogProtocol"]?.takeIf(String::isNotBlank),
            chatSupported = supportsChat(row, stored),
            showChecker = supportsChat(row, stored) && (stored["__catalogShowChecker"]?.toBoolean() ?: spec.showChecker),
            showModelFetcher = supportsChat(row, stored) && (row.source == "custom" || id !in NO_MODEL_LISTING),
            modelCount = stored["__catalogModelCount"]?.toIntOrNull() ?: 0,
        )
    }
    override suspend fun updateConfig(id: String, keyVaults: Map<String, String?>, checkModel: String?): AnResult<Unit> = credentialAction(id) {
        seed(); val row = dao.provider(id) ?: error("Provider not found")
        val plain = json.decodeFromString<Map<String, String>>(row.config).toMutableMap().apply {
            remove("__fetchOnClient")
            remove("__enableResponseApi")
        }
        val encrypted = secrets.read(id).toMutableMap(); val sensitive = secretKeys(id, row.source)
        keyVaults.forEach { (key, value) ->
            if (key.startsWith("oauth")) return@forEach
            if (value.isNullOrBlank()) {
                plain.remove(key)
                encrypted.remove(key)
            } else if (key in sensitive) {
                plain.remove(key)
                encrypted[key] = value
            } else {
                plain[key] = value
            }
        }
        if (checkModel != null) plain["__checkModel"] = checkModel
        secrets.write(id, encrypted)
        dao.putProvider(row.copy(config = json.encodeToString(plain)))
    }
    override suspend fun checkConnectivity(id: String, model: String?): AnResult<CheckResult> = try {
        seed()
        val row = dao.provider(id) ?: error("Provider not found")
        val config = invocationConfig(row)
        require(supportsChat(row, config)) { "This provider does not support chat" }
        val selectedModel = model?.takeIf(String::isNotBlank)
            ?: config["__checkModel"]?.takeIf(String::isNotBlank)
            ?: config["__catalogCheckModel"]?.takeIf(String::isNotBlank)
            ?: providerConfigSpec(id, row.source).checkModel
            ?: error("Select a model to check connectivity")
        probe.check(id, config, selectedModel)
        AnResult.Ok(CheckResult(true))
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        AnResult.Ok(CheckResult(false, e.message ?: "Unable to connect to provider"))
    }
    override suspend fun fetchRemoteModels(providerId: String, baseURL: String, apiKey: String): AnResult<List<String>> = try {
        seed()
        val row = dao.provider(providerId) ?: error("Provider not found")
        val config = invocationConfig(row).toMutableMap()
        if (baseURL.isNotBlank()) config["baseURL"] = baseURL.trim().trimEnd('/')
        if (apiKey.isNotBlank()) config["apiKey"] = apiKey
        val sdkType = adapter(providerId, row.source, config)
        require(sdkType != null) { "This provider does not support chat" }
        require(row.source == "custom" || providerId !in NO_MODEL_LISTING) {
            "This provider does not expose a model-list API; choose a catalog model and check connectivity instead"
        }
        val cloudModels = if (row.source == "custom") null else cloud.modelIds(providerId, config)
        val models = cloudModels ?: run {
            require(sdkType in DISCOVERY_ADAPTER_TYPES) {
                "Model listing is unavailable with these credentials; choose a catalog model and check connectivity instead"
            }
            discoverModelIds(providerId, sdkType, config, row.source == "custom")
        }
        val distinctModels = models.distinct()
        distinctModels.forEach { id ->
            val old = dao.model(providerId, id)
            val metadata = old?.metadata?.takeIf { it != "{}" }?.let {
                runCatching { json.decodeFromString<JsonObject>(it) }.getOrNull()
            } ?: JsonObject(mapOf("type" to JsonPrimitive("chat")))
            val stampedMetadata = JsonObject(
                metadata + ("chatSupported" to JsonPrimitive(
                    (metadata["type"] as? JsonPrimitive)?.contentOrNull.let { it == null || it == "chat" },
                )) + (ACTIVATION_DEFAULTS_VERSION_KEY to JsonPrimitive(ACTIVATION_DEFAULTS_VERSION)),
            )
            dao.putModel(
                ModelRow(
                    id = id,
                    providerId = providerId,
                    displayName = old?.displayName ?: id,
                    enabled = old?.enabled ?: false,
                    metadata = json.encodeToString(stampedMetadata),
                ),
            )
        }
        AnResult.Ok(distinctModels)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("PROVIDER", e.message ?: "Unable to fetch models", e)) }
    private suspend fun discoverModelIds(
        providerId: String,
        sdkType: String,
        config: Map<String, String>,
        custom: Boolean,
    ): List<String> {
        val base = config["baseURL"]?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
            ?: error("Configure the provider endpoint first")
        val key = config["apiKey"].orEmpty()
        val id = if (custom) "" else providerId
        val root = base.replace(VERSION_PATH, "")
        val url = when {
            id == "aihubmix" -> "$root/api/v1/models"
            id == "zenmux" -> root.removeSuffix("/api") + "/api/v1/models"
            id == "newapi" -> "$root/v1/models"
            id == "togetherai" -> "$root/api/models"
            id == "nebius" -> "$base/models?verbose=true"
            id == "cohere" -> base.removeSuffix("/compatibility/v1").removeSuffix("/v1").let {
                if (it == "https://api.cohere.ai") "https://api.cohere.com/v1/models" else "$it/v1/models"
            }
            id == "straico" -> "$base/models"
            sdkType == "anthropic" -> (if (base.endsWith("/v1")) base else "$base/v1") + "/models"
            sdkType == "google" -> (if (VERSION_PATH.containsMatchIn(base)) base else "$base/v1beta") + "/models"
            sdkType == "ollama" -> base.removeSuffix("/v1") + "/api/tags"
            else -> buildModelsUrl(base)
        }
        val builder = Request.Builder().url(url).get()
        when (sdkType) {
            "anthropic" -> builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            "google" -> if (key.isNotBlank()) builder.header("x-goog-api-key", key)
            else -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        }
        when (id) {
            "aihubmix" -> builder.header("APP-Code", "LobeHub")
            "ppio" -> builder.header("X-API-Source", "lobechat")
            "githubcopilot" -> builder.header("Editor-Version", "LobeChat/1.0")
                .header("Editor-Plugin-Version", "LobeChat/1.0")
                .header("Copilot-Integration-Id", "vscode-chat").header("Accept", "application/json")
            "newapi" -> builder.header("X-Client", "LobeHub")
            "xiaomimimo" -> builder.removeHeader("Authorization").header("api-key", key)
        }
        val result = mutableListOf<String>()
        val seenPages = mutableSetOf<String>()
        var request = builder.build()
        while (seenPages.add(request.url.toString())) {
            val element = json.parseToJsonElement(fetchBody(request))
            val page = element as? JsonObject
            val items = when {
                sdkType == "google" || sdkType == "ollama" -> page?.get("models") as? JsonArray
                id == "cohere" -> (page?.get("body") as? JsonObject)?.get("models") as? JsonArray
                    ?: page?.get("models") as? JsonArray
                id == "straico" -> page?.get("data") as? JsonArray ?: page?.get("models") as? JsonArray
                    ?: (page?.get("data") as? JsonObject)?.get("models") as? JsonArray
                    ?: (element as? JsonArray)
                id == "githubcopilot" -> page?.get("models") as? JsonArray ?: page?.get("data") as? JsonArray
                element is JsonArray -> element
                else -> page?.get("data") as? JsonArray
            }
            require(items != null) { "Provider returned an unsupported model-list response" }
            items.forEach { item ->
                val model = item as? JsonObject ?: return@forEach
                val type = ((model["model_type"] ?: model["types"] ?: model["type"]) as? JsonPrimitive)?.contentOrNull
                if (type != null && type !in CHAT_MODEL_TYPES) return@forEach
                val output = model["output_modalities"]
                if (output is JsonArray && output.none { (it as? JsonPrimitive)?.contentOrNull == "text" }) return@forEach
                if (output is JsonPrimitive && output.contentOrNull?.split(',')?.none { it.trim() == "text" } == true) return@forEach
                if (sdkType == "google") {
                    val methods = model["supportedGenerationMethods"] as? JsonArray
                    if (methods != null && methods.none { (it as? JsonPrimitive)?.contentOrNull in GOOGLE_CHAT_METHODS }) return@forEach
                }
                val modelId = when {
                    sdkType == "google" -> (model["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")
                    sdkType == "ollama" || id == "cohere" -> (model["name"] as? JsonPrimitive)?.contentOrNull
                    id == "baichuan" -> (model["model"] as? JsonPrimitive)?.contentOrNull
                    id == "aihubmix" -> (model["model_id"] as? JsonPrimitive)?.contentOrNull
                        ?: (model["id"] as? JsonPrimitive)?.contentOrNull
                    id == "githubcopilot" -> (model["id"] as? JsonPrimitive)?.contentOrNull
                        ?: (model["name"] as? JsonPrimitive)?.contentOrNull
                    else -> (model["id"] as? JsonPrimitive)?.contentOrNull
                }
                if ((id == "opencodecodingplan" || id == "opencodezen") && modelId?.startsWith("jev-") == true) return@forEach
                modelId?.takeIf(String::isNotBlank)?.let(result::add)
            }
            val next = when (sdkType) {
                "google" -> (page?.get("nextPageToken") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                    request.url.newBuilder().setQueryParameter("pageToken", it).build()
                }
                "anthropic" -> if ((page?.get("has_more") as? JsonPrimitive)?.booleanOrNull == true) {
                    (page?.get("last_id") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        request.url.newBuilder().setQueryParameter("after_id", it).build()
                    }
                } else null
                else -> null
            } ?: break
            request = request.newBuilder().url(next).build()
        }
        return result.distinct()
    }

    private suspend fun fetchBody(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw IOException("Provider returned HTTP ${it.code}: ${text.take(500)}")
                        text
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    override suspend fun createCustom(name: String, baseURL: String): AnResult<String> = action {
        seed(); require(name.isNotBlank() && baseURL.isNotBlank()) { "Name and base URL are required" }
        val id = "custom-${UUID.randomUUID()}"
        dao.putProvider(
            ProviderRow(
                id = id,
                name = name.trim(),
                source = "custom",
                enabled = false,
                config = json.encodeToString(
                    mapOf(
                        "baseURL" to baseURL.trimEnd('/'),
                        "__sdkType" to "openai",
                        ACTIVATION_DEFAULTS_VERSION_KEY to ACTIVATION_DEFAULTS_VERSION,
                    ),
                ),
            ),
        )
        id
    }
}
private suspend fun <T> action(block: suspend () -> T): AnResult<T> = try { AnResult.Ok(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_PROVIDER", e.message ?: "Provider operation failed", e)) }
