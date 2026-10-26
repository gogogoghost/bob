package cc.jaxy.anlobehub.core.data.chat

import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** On-device cloud transports. Credentials are supplied by ProviderSecretStore, never persisted here. */
internal class CloudProviderRequests(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private var cachedToken: VertexToken? = null

    suspend fun prepare(providerId: String, config: Map<String, String>, modelId: String, body: JsonObject): PreparedChatRequest? = when (providerId.lowercase()) {
        "azure" -> azure(config, modelId, body)
        "azureai" -> {
            val url = append(config.required("baseURL"), "chat/completions").newBuilder()
                .setQueryParameter("api-version", config.value("apiVersion") ?: "2024-05-01-preview").build()
            PreparedChatRequest(post(url, cloudReasoningBody(body, modelId, true)).header("api-key", config.required("apiKey")).build(), "openai")
        }
        "github" -> {
            val base = githubBase(config)
            PreparedChatRequest(post(append(base, "chat/completions"), cloudReasoningBody(body, modelId, false))
                .header("Authorization", "Bearer ${config.required("apiKey")}")
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").build(), "openai")
        }
        "huggingface" -> PreparedChatRequest(post(append(config.value("baseURL") ?: "https://router.huggingface.co/v1", "chat/completions"), body)
            .header("Authorization", "Bearer ${config.required("apiKey")}").build(), "openai")
        "cloudflare" -> PreparedChatRequest(post(append(cloudflareRoot(config), "v1/chat/completions"), body)
            .apply { config.value("apiKey")?.let { header("Authorization", "Bearer $it") } }.build(), "openai")
        "bedrock" -> {
            val region = config.value("region") ?: "us-east-1"
            val url = append(config.value("baseURL") ?: awsEndpoint("bedrock-runtime", region), "model")
                .newBuilder().addPathSegment(modelId).addPathSegment("converse-stream").build()
            val payload = cloudBedrockBody(body, modelId).toString()
            val request = Request.Builder().url(url).post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/vnd.amazon.eventstream")
            PreparedChatRequest(awsAuthorize(request, payload, config, region, bearer = true), "bedrock")
        }
        "vertexai" -> vertex(config, modelId, body)
        else -> null
    }

    /** null means this credential mode has no supported chat-model discovery API. */
    suspend fun modelIds(providerId: String, config: Map<String, String>): List<String>? = when (providerId.lowercase()) {
        "github" -> {
            val base = githubBase(config).toHttpUrl()
            val path = base.encodedPath.trimEnd('/').removeSuffix("/inference") + "/catalog/models"
            val request = Request.Builder().url(base.newBuilder().encodedPath(path).build())
                .header("Accept", "application/vnd.github+json")
                .apply { config.value("apiKey")?.let { header("Authorization", "Bearer $it") } }.build()
            array(executeJson(request)).filter { model ->
                val outputs = model["supported_output_modalities"] as? JsonArray
                outputs == null || outputs.any { (it as? JsonPrimitive)?.content == "text" }
            }.mapNotNull { it.cloudText("id") }.distinct()
        }
        "huggingface" -> {
            val request = Request.Builder().url(append(config.value("baseURL") ?: "https://router.huggingface.co/v1", "models"))
                .apply { config.value("apiKey")?.let { header("Authorization", "Bearer $it") } }.build()
            array((executeJson(request) as? JsonObject)?.get("data")).filter { model ->
                val architecture = model["architecture"] as? JsonObject
                val outputs = architecture?.get("output_modalities") as? JsonArray
                outputs == null || outputs.any { (it as? JsonPrimitive)?.content == "text" }
            }.mapNotNull { it.cloudText("id") }.distinct()
        }
        "cloudflare" -> cloudflareModels(config)
        "bedrock" -> if (usesAwsCredentials(config)) bedrockModels(config) else null
        "vertexai" -> if (serviceAccount(config) != null) vertexModels(config) else null
        // Azure's model catalog is not a deployment list; ARM needs subscription/resource-group credentials.
        "azure", "azureai" -> null
        else -> null
    }

    private fun azure(config: Map<String, String>, modelId: String, body: JsonObject): PreparedChatRequest {
        val base = config.required("baseURL").toHttpUrl()
        val deployment = config.value("deploymentName") ?: modelId
        val responses = usesResponses(modelId)
        val suffix = if (responses) "responses" else "chat/completions"
        val path = base.encodedPath.trimEnd('/')
        val v1 = path.endsWith("/openai/v1")
        val openAiIndex = Regex("/openai(?:/|$)").find(path)?.range?.first
        val openAiRoot = base.newBuilder().encodedPath(if (openAiIndex == null) "$path/openai" else path.substring(0, openAiIndex) + "/openai").build()
        val url = when {
            v1 -> append(base.toString(), suffix)
            responses -> append(openAiRoot.toString(), "v1/responses")
            path.contains("/openai/deployments/") -> append(base.toString(), suffix)
            else -> append(openAiRoot.toString(), "deployments").newBuilder().addPathSegment(deployment).addPathSegments(suffix).build()
        }.newBuilder().apply {
            if (!v1 && !responses) setQueryParameter("api-version", config.value("apiVersion") ?: "2024-10-21")
        }.build()
        val payload = if (responses) cloudResponsesBody(body, deployment) else {
            cloudReasoningBody(body, modelId, true).let { JsonObject(it + ("model" to JsonPrimitive(deployment))) }
        }
        return PreparedChatRequest(post(url, payload).header("api-key", config.required("apiKey")).build(), if (responses) "responses" else "openai")
    }

    private suspend fun vertex(config: Map<String, String>, modelId: String, body: JsonObject): PreparedChatRequest {
        val account = serviceAccount(config)
        val claude = modelId.substringAfterLast('/').startsWith("claude-") || modelId.contains("publishers/anthropic/")
        require(!claude || account != null) { "Claude on Vertex AI requires service-account OAuth, a project, and an enabled Anthropic model; Express API keys support Google models only" }
        val location = config.value("region") ?: "global"
        val base = config.value("baseURL") ?: if (location == "global" || account == null) "https://aiplatform.googleapis.com" else "https://$location-aiplatform.googleapis.com"
        val publisher = if (claude) "anthropic" else "google"
        val method = if (claude) "streamRawPredict" else "streamGenerateContent"
        val resource = if (modelId.startsWith("projects/") || modelId.startsWith("publishers/")) modelId else "publishers/$publisher/models/$modelId"
        val path = if (account == null || resource.startsWith("projects/")) resource else {
            val project = config.value("projectId") ?: config.value("project") ?: account.cloudText("project_id")
            require(!project.isNullOrBlank()) { "Vertex service-account credentials require project_id or projectId" }
            "projects/$project/locations/$location/$resource"
        }
        val url = appendVersioned(base, "v1", "$path:$method").newBuilder().apply {
            if (!claude) setQueryParameter("alt", "sse")
            if (account == null) setQueryParameter("key", config.required("apiKey"))
        }.build()
        val payload = if (claude) cloudAnthropicBody(body, "vertex-2023-10-16", modelId) else cloudGeminiBody(body, modelId)
        return PreparedChatRequest(post(url, payload).apply {
            if (account != null) header("Authorization", "Bearer ${vertexAccessToken(account)}")
        }.build(), if (claude) "anthropic" else "gemini")
    }

    private fun serviceAccount(config: Map<String, String>): JsonObject? {
        val raw = config.value("serviceAccount") ?: config.value("apiKey") ?: return null
        if (!raw.trimStart().startsWith('{')) return null
        val account = json.parseToJsonElement(raw) as? JsonObject ?: error("Vertex service account must be a JSON object")
        require(!account.cloudText("client_email").isNullOrBlank() && !account.cloudText("private_key").isNullOrBlank()) {
            "Vertex service-account JSON requires client_email and private_key"
        }
        return account
    }

    private suspend fun vertexAccessToken(account: JsonObject): String = tokenMutex.withLock {
        val identity = sha256(account.toString().toByteArray())
        val now = Instant.now().epochSecond
        cachedToken?.takeIf { it.identity == identity && it.expiresAt > now + 60 }?.let { return@withLock it.value }
        // Fixed Google audience prevents credential JSON from redirecting signed assertions to another host.
        val audience = "https://oauth2.googleapis.com/token"
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        val header = buildJsonObject { put("alg", "RS256"); put("typ", "JWT"); account.cloudText("private_key_id")?.let { put("kid", it) } }
        val claims = buildJsonObject {
            put("iss", account.cloudText("client_email").orEmpty()); put("scope", "https://www.googleapis.com/auth/cloud-platform")
            put("aud", audience); put("iat", now); put("exp", now + 3600)
        }
        val unsigned = "${encode(header.toString())}.${encode(claims.toString())}"
        val pem = account.cloudText("private_key").orEmpty().replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(unsigned.toByteArray(Charsets.UTF_8)) }.sign()
        val assertion = "$unsigned.${encoder.encodeToString(signature)}"
        val request = Request.Builder().url(audience).post(FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer").add("assertion", assertion).build()).build()
        val result = executeJson(request) as? JsonObject ?: throw IOException("Invalid Google OAuth response")
        val token = result.cloudText("access_token")?.takeIf(String::isNotBlank) ?: throw IOException("Google OAuth returned no access token")
        val expiresIn = result.cloudText("expires_in")?.toLongOrNull() ?: 3600
        cachedToken = VertexToken(identity, token, now + expiresIn)
        token
    }

    private suspend fun vertexModels(config: Map<String, String>): List<String> {
        val token = vertexAccessToken(serviceAccount(config) ?: error("Vertex discovery requires service-account OAuth"))
        val base = config.value("baseURL") ?: "https://aiplatform.googleapis.com"
        val ids = mutableListOf<String>()
        for (publisher in listOf("google", "anthropic")) {
            var page: String? = null
            do {
                val url = appendVersioned(base, "v1beta1", "publishers/$publisher/models").newBuilder()
                    .addQueryParameter("pageSize", "100").apply { page?.let { addQueryParameter("pageToken", it) } }.build()
                val response = executeJson(Request.Builder().url(url).header("Authorization", "Bearer $token").build()) as? JsonObject
                    ?: throw IOException("Invalid Vertex publisher model list")
                array(response["publisherModels"]).forEach { model ->
                    val name = model.cloudText("name").orEmpty().substringAfterLast('/')
                    // Model Garden also contains embeddings, image/audio generators, and models requiring deployment.
                    if ((publisher == "google" && name.startsWith("gemini-") && !Regex("image|tts|audio|live|embedding").containsMatchIn(name)) ||
                        (publisher == "anthropic" && name.startsWith("claude-"))) ids += name
                }
                page = response.cloudText("nextPageToken")?.takeIf(String::isNotBlank)
            } while (page != null)
        }
        return ids.distinct()
    }

    private fun cloudflareRoot(config: Map<String, String>): String {
        val setting = config.value("baseURL") ?: config.required("baseURLOrAccountID")
        val root = if (setting.startsWith("https://") || setting.startsWith("http://")) setting else {
            "https://api.cloudflare.com/client/v4/accounts/$setting/ai"
        }
        return root.trimEnd('/').removeSuffix("/v1").removeSuffix("/run")
    }

    private suspend fun cloudflareModels(config: Map<String, String>): List<String> {
        val root = cloudflareRoot(config)
        val ids = mutableListOf<String>()
        var page = 1
        do {
            val url = append(root, "models/search").newBuilder().addQueryParameter("page", page.toString()).addQueryParameter("per_page", "100").build()
            val response = executeJson(Request.Builder().url(url).header("Authorization", "Bearer ${config.required("apiKey")}").build()) as? JsonObject
                ?: throw IOException("Invalid Cloudflare model list")
            if ((response["success"] as? JsonPrimitive)?.content == "false") throw IOException("Cloudflare model discovery failed: ${response["errors"]}")
            array(response["result"]).filter { model ->
                val task = model["task"] as? JsonObject
                task?.cloudText("name")?.lowercase() == "text generation"
            }.mapNotNullTo(ids) { it.cloudText("name") }
            val info = response["result_info"] as? JsonObject
            val pages = info?.cloudText("total_pages")?.toIntOrNull() ?: 1
            page++
        } while (page <= pages)
        return ids.distinct()
    }

    private suspend fun bedrockModels(config: Map<String, String>): List<String> {
        val region = config.value("region") ?: "us-east-1"
        val base = config.value("controlPlaneURL") ?: awsEndpoint("bedrock", region)
        val url = append(base, "foundation-models").newBuilder().addQueryParameter("byOutputModality", "TEXT").build()
        val response = executeJson(awsAuthorize(Request.Builder().url(url), "", config, region, bearer = false)) as? JsonObject
            ?: throw IOException("Invalid Bedrock foundation model list")
        val models = array(response["modelSummaries"]).filter {
            it.cloudText("responseStreamingSupported") == "true" &&
                (it["inputModalities"] as? JsonArray).orEmpty().any { value -> (value as? JsonPrimitive)?.content == "TEXT" }
        }
        val supported = models.mapNotNull { it.cloudText("modelId") }.toSet()
        val ids = supported.toMutableList()
        var next: String? = null
        do {
            val profilesUrl = append(base, "inference-profiles").newBuilder().addQueryParameter("maxResults", "1000")
                .apply { next?.let { addQueryParameter("nextToken", it) } }.build()
            val profiles = executeJson(awsAuthorize(Request.Builder().url(profilesUrl), "", config, region, bearer = false)) as? JsonObject
                ?: throw IOException("Invalid Bedrock inference profile list")
            array(profiles["inferenceProfileSummaries"]).filter { profile ->
                profile.cloudText("status") == "ACTIVE" && array(profile["models"]).any { it.cloudText("modelArn")?.substringAfter("foundation-model/") in supported }
            }.mapNotNullTo(ids) { it.cloudText("inferenceProfileId") }
            next = profiles.cloudText("nextToken")?.takeIf(String::isNotBlank)
        } while (next != null)
        return ids.distinct()
    }

    private fun usesAwsCredentials(config: Map<String, String>): Boolean = when (config.value("authType")) {
        "awsCredentials" -> true
        "apiKey" -> false
        else -> config.value("accessKeyId") != null || config.value("secretAccessKey") != null || config.value("accessKeySecret") != null
    }

    private fun awsAuthorize(builder: Request.Builder, payload: String, config: Map<String, String>, region: String, bearer: Boolean): Request {
        if (bearer && !usesAwsCredentials(config)) return builder.header("Authorization", "Bearer ${config.required("apiKey")}").build()
        val accessKey = config.required("accessKeyId")
        val secret = config.value("secretAccessKey") ?: config.required("accessKeySecret")
        val instant = Instant.now()
        val stamp = AWS_TIMESTAMP.format(instant)
        val date = stamp.take(8)
        val initial = builder.build()
        val url = initial.url
        val payloadHash = sha256(payload.toByteArray(Charsets.UTF_8))
        val headers = sortedMapOf(
            "host" to (url.host + if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""),
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to stamp,
        )
        config.value("sessionToken")?.let { headers["x-amz-security-token"] = it }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val canonicalQuery = (0 until url.querySize).map { index -> awsEncode(url.queryParameterName(index)) to awsEncode(url.queryParameterValue(index).orEmpty()) }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).joinToString("&") { "${it.first}=${it.second}" }
        // AWS double-encodes the URI for non-S3 services; preserve the path's slash separators.
        val canonicalPath = url.encodedPath.split('/').joinToString("/") { awsEncode(it) }
        val canonical = "${initial.method}\n$canonicalPath\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$date/$region/bedrock/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n${sha256(canonical.toByteArray(Charsets.UTF_8))}"
        val dateKey = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), date)
        val signingKey = hmac(hmac(hmac(dateKey, region), "bedrock"), "aws4_request")
        val signature = hex(hmac(signingKey, toSign))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.header("Authorization", "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature").build()
    }

    private fun githubBase(config: Map<String, String>): String {
        val base = config.value("baseURL") ?: "https://models.github.ai/inference"
        require(base.toHttpUrl().host !in setOf("models.github.ai", "models.inference.ai.azure.com")) {
            "GitHub Models retired on July 30, 2026; its public inference API and catalog are unavailable. Configure a compatible proxy endpoint or use Azure AI Foundry/Copilot instead."
        }
        return base
    }

    private suspend fun executeJson(request: Request): JsonElement = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val text = it.body?.string() ?: throw IOException("Cloud provider returned an empty response")
                        if (!it.isSuccessful) throw IOException("Cloud provider returned HTTP ${it.code}: ${text.take(1500)}")
                        json.parseToJsonElement(text)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun post(url: HttpUrl, body: JsonObject): Request.Builder = Request.Builder().url(url)
        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
    private fun append(base: String, vararg paths: String): HttpUrl = base.toHttpUrl().newBuilder().apply {
        for (path in paths) addPathSegments(path.trim('/'))
    }.build()
    private fun appendVersioned(base: String, version: String, path: String): HttpUrl {
        val root = base.toHttpUrl()
        val existing = root.encodedPath.trimEnd('/').substringAfterLast('/')
        return append(root.toString(), if (existing == "v1" || existing == "v1beta1") path else "$version/$path")
    }
    private fun awsEndpoint(service: String, region: String) = "https://$service.$region.${if (region.startsWith("cn-")) "amazonaws.com.cn" else "amazonaws.com"}"
    private fun array(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun Map<String, String>.value(key: String): String? = get(key)?.trim()?.takeIf(String::isNotBlank)
    private fun Map<String, String>.required(key: String): String = value(key) ?: error("Cloud provider requires $key")
    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(value.toByteArray(Charsets.UTF_8))
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) { for (byte in bytes) { val value = byte.toInt() and 255; append(HEX[value ushr 4]); append(HEX[value and 15]) } }
    private fun awsEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 255
            if (c in 65..90 || c in 97..122 || c in 48..57 || c == 45 || c == 46 || c == 95 || c == 126) append(c.toChar())
            else { append('%'); append(HEX[c ushr 4].uppercaseChar()); append(HEX[c and 15].uppercaseChar()) }
        }
    }
    private data class VertexToken(val identity: String, val value: String, val expiresAt: Long)
    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val AWS_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        const val HEX = "0123456789abcdef"
    }
}
