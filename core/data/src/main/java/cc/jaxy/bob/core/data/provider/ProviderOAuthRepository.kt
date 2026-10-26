package cc.jaxy.bob.core.data.provider

import android.util.Base64
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Device codes are transient: never put this object in saved state or the credential vault. */
class ProviderDeviceAuthorization internal constructor(
    val providerId: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Long,
    val verificationUriComplete: String?,
    internal val deviceCode: String,
    internal val intervalSeconds: Long,
    internal val attemptId: String,
)

data class ProviderOAuthConnection(val connected: Boolean, val account: String?)

class ProviderOAuthException(val reason: Reason, val httpStatus: Int? = null) : IOException(
    if (httpStatus == null) "Provider authorization: ${reason.name}"
    else "Provider authorization: ${reason.name} (HTTP $httpStatus)",
) {
    enum class Reason { NOT_CONNECTED, EXPIRED, DENIED, RECONNECT, SUBSCRIPTION_REQUIRED, INVALID_RESPONSE, REQUEST_FAILED }
}

/**
 * Public subscription OAuth clients and endpoints are taken from upstream model-bank cards
 * and apps/server/src/services/oauthDeviceFlow (commit 6c7a6fb033e443bc27bf34cbd5509ab3db7f22f3).
 * Tokens live only in ProviderSecretStore; concurrent refreshes are serialized before rotation.
 */
@Singleton
class ProviderOAuthRepository @Inject constructor(private val secrets: ProviderSecretStore) {
    private val client = OkHttpClient()

    suspend fun begin(providerId: String): ProviderDeviceAuthorization {
        val config = config(providerId)
        val attemptId = UUID.randomUUID().toString()
        lock(providerId).withLock { attempts[providerId] = attemptId }
        try {
            val body = if (providerId == "chatgpt") {
                jsonBody(mapOf("client_id" to config.clientId))
            } else {
                form("client_id" to config.clientId, "scope" to config.scope)
            }
            val result = request(config.deviceEndpoint, body).checked()
            val interval = if (providerId == "chatgpt") {
                result.number("interval")?.coerceAtLeast(1)?.plus(3) ?: 8
            } else {
                result.number("interval")?.coerceAtLeast(1) ?: 5
            }
            val lifetime = if (providerId == "chatgpt") 900L else result.number("expires_in")
                ?: throw ProviderOAuthException(ProviderOAuthException.Reason.INVALID_RESPONSE)
            val authorization = ProviderDeviceAuthorization(
                providerId = providerId,
                userCode = result.required("user_code"),
                verificationUri = if (providerId == "chatgpt") "https://auth.openai.com/codex/device"
                    else result.text("verification_uri") ?: result.required("verification_url"),
                expiresAt = System.currentTimeMillis() + lifetime.coerceAtLeast(1) * 1000,
                verificationUriComplete = result.text("verification_uri_complete"),
                deviceCode = result.required(if (providerId == "chatgpt") "device_auth_id" else "device_code"),
                intervalSeconds = interval,
                attemptId = attemptId,
            )
            lock(providerId).withLock { requireAttempt(authorization) }
            return authorization
        } catch (error: Exception) {
            withContext(NonCancellable) {
                lock(providerId).withLock {
                    if (attempts[providerId] == attemptId) attempts.remove(providerId)
                }
            }
            throw error
        }
    }

    suspend fun awaitAuthorization(authorization: ProviderDeviceAuthorization) {
        val id = authorization.providerId
        val config = config(id)
        var interval = authorization.intervalSeconds
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                lock(id).withLock { requireAttempt(authorization) }
                val remaining = authorization.expiresAt - System.currentTimeMillis()
                if (remaining <= 0) throw ProviderOAuthException(ProviderOAuthException.Reason.EXPIRED)
                delay(minOf(interval * 1000, remaining))
                if (System.currentTimeMillis() >= authorization.expiresAt) {
                    throw ProviderOAuthException(ProviderOAuthException.Reason.EXPIRED)
                }
                val response = if (id == "chatgpt") {
                    request(
                        "https://auth.openai.com/api/accounts/deviceauth/token",
                        jsonBody(mapOf("device_auth_id" to authorization.deviceCode, "user_code" to authorization.userCode)),
                    )
                } else {
                    request(
                        config.tokenEndpoint,
                        form("client_id" to config.clientId, "device_code" to authorization.deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
                    )
                }
                if (id == "chatgpt" && response.status in setOf(403, 404)) continue
                when (response.data.text("error")) {
                    "authorization_pending" -> continue
                    "slow_down" -> { interval += 5; continue }
                    "expired_token" -> throw ProviderOAuthException(ProviderOAuthException.Reason.EXPIRED)
                    "access_denied" -> throw ProviderOAuthException(ProviderOAuthException.Reason.DENIED)
                }
                val authorized = response.checked()
                val tokenData = if (id == "chatgpt") {
                    request(config.tokenEndpoint, form(
                        "client_id" to config.clientId,
                        "code" to authorized.required("authorization_code"),
                        "code_verifier" to authorized.required("code_verifier"),
                        "grant_type" to "authorization_code",
                        "redirect_uri" to "https://auth.openai.com/deviceauth/callback",
                    )).checked()
                } else authorized
                val tokens = tokenValues(id, tokenData)
                val complete = if (id == "githubcopilot") {
                    val oauthToken = tokens.getValue(ACCESS)
                    val bearer = copilotToken(oauthToken)
                    val user = request("https://api.github.com/user", token = oauthToken).checked()
                    tokens + bearer + mapOf(USERNAME to user.required("login"))
                } else tokens
                currentCoroutineContext().ensureActive()
                lock(id).withLock {
                    requireAttempt(authorization)
                    // Once granted, finish the encrypted write even if navigation cancels the UI.
                    withContext(NonCancellable) { save(id, complete) }
                    attempts.remove(id)
                }
                return
            }
        } finally {
            withContext(NonCancellable) {
                lock(id).withLock {
                    if (attempts[id] == authorization.attemptId) attempts.remove(id)
                }
            }
        }
    }

    /** Metadata only; opening settings must not refresh a token or reveal it to Compose. */
    suspend fun connection(providerId: String): ProviderOAuthConnection = lock(providerId).withLock {
        val stored = secrets.read(providerId)
        ProviderOAuthConnection(!stored[ACCESS].isNullOrBlank(), stored[USERNAME] ?: stored[ACCOUNT])
    }

    suspend fun credentials(providerId: String): Map<String, String> = lock(providerId).withLock {
        val config = config(providerId)
        var stored = secrets.read(providerId)
        if (stored[ACCESS].isNullOrBlank()) throw ProviderOAuthException(ProviderOAuthException.Reason.NOT_CONNECTED)
        val expiryKey = if (providerId == "githubcopilot") BEARER_EXPIRY else EXPIRY
        val expiry = stored[expiryKey]?.toLongOrNull()
        val jwtExpiry = if (providerId == "githubcopilot") null else jwtClaims(stored[ACCESS])?.number("exp")?.times(1000)
        val now = System.currentTimeMillis()
        val skew = if (providerId == "githubcopilot") 300_000 else 120_000
        val expiring = expiry == null && jwtExpiry == null ||
            expiry != null && expiry - now <= skew || jwtExpiry != null && jwtExpiry - now <= skew
        if (expiring || providerId == "githubcopilot" && stored[BEARER].isNullOrBlank()) {
            val updates = if (providerId == "githubcopilot") {
                copilotToken(stored.getValue(ACCESS))
            } else {
                val refresh = stored[REFRESH]?.takeIf { it.isNotBlank() }
                    ?: throw ProviderOAuthException(ProviderOAuthException.Reason.RECONNECT)
                val response = request(config.tokenEndpoint, form(
                    "client_id" to config.clientId, "grant_type" to "refresh_token", "refresh_token" to refresh,
                ))
                if (response.data.text("error") == "invalid_grant") {
                    throw ProviderOAuthException(ProviderOAuthException.Reason.RECONNECT)
                }
                tokenValues(providerId, response.checked(), stored)
            }
            stored = (if (providerId == "githubcopilot") stored else stored - EXPIRY) + updates
            // Refresh tokens may be single-use: persist rotated credentials before returning them.
            withContext(NonCancellable) { secrets.write(providerId, stored) }
        }
        buildMap {
            put("apiKey", stored.getValue(if (providerId == "githubcopilot") BEARER else ACCESS))
            put("baseURL", config.baseURL)
            stored[ACCOUNT]?.let { put(ACCOUNT, it) }
            stored[USERNAME]?.let { put(USERNAME, it) }
        }
    }

    /** Local disconnect, not provider-side revocation; preserve unrelated provider settings. */
    suspend fun disconnect(providerId: String) = lock(providerId).withLock {
        attempts.remove(providerId)
        secrets.write(providerId, secrets.read(providerId).filterKeys { it !in secretKeys })
    }

    private suspend fun save(id: String, tokens: Map<String, String>) {
        secrets.write(id, secrets.read(id).filterKeys { it !in secretKeys } + tokens)
    }

    private fun tokenValues(id: String, data: JsonObject, old: Map<String, String> = emptyMap()): Map<String, String> {
        val access = data.required("access_token")
        val values = mutableMapOf(ACCESS to access)
        (data.text("refresh_token") ?: old[REFRESH])?.let { values[REFRESH] = it }
        val expires = data.number("expires_in")?.let { System.currentTimeMillis() + it * 1000 }
            ?: jwtClaims(access)?.number("exp")?.times(1000)
        expires?.let { values[EXPIRY] = it.toString() }
        if (id == "chatgpt") {
            val account = accountId(data.text("id_token"), access) ?: old[ACCOUNT]
                ?: throw ProviderOAuthException(ProviderOAuthException.Reason.INVALID_RESPONSE)
            values[ACCOUNT] = account
        }
        return values
    }

    private suspend fun copilotToken(oauthToken: String): Map<String, String> {
        val response = request("https://api.github.com/copilot_internal/v2/token", token = oauthToken)
        if (response.status == 401) throw ProviderOAuthException(ProviderOAuthException.Reason.RECONNECT)
        if (response.status == 403) throw ProviderOAuthException(ProviderOAuthException.Reason.SUBSCRIPTION_REQUIRED)
        val data = response.checked()
        val expiry = data.number("expires_at")
            ?: throw ProviderOAuthException(ProviderOAuthException.Reason.INVALID_RESPONSE)
        return mapOf(BEARER to data.required("token"), BEARER_EXPIRY to (expiry * 1000).toString())
    }

    private fun accountId(vararg tokens: String?): String? = tokens.firstNotNullOfOrNull { token ->
        val claims = jwtClaims(token)
        claims?.text("chatgpt_account_id")
            ?: (claims?.get("https://api.openai.com/auth") as? JsonObject)?.text("chatgpt_account_id")
            ?: (((claims?.get("organizations") as? JsonArray)?.firstOrNull()) as? JsonObject)?.text("id")
    }

    // JWT claims are used only as metadata / refresh hints, never to verify identity locally.
    private fun jwtClaims(token: String?): JsonObject? = try {
        token?.split('.')?.getOrNull(1)?.let {
            Json.parseToJsonElement(String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)) as? JsonObject
        }
    } catch (_: IllegalArgumentException) { null }

    private fun requireAttempt(authorization: ProviderDeviceAuthorization) {
        if (attempts[authorization.providerId] != authorization.attemptId) throw CancellationException("Authorization cancelled")
    }

    /** Settings migrations/saves share token-rotation exclusion; never write a stale vault snapshot. */
    internal suspend fun <T> withCredentialLock(id: String, operation: suspend () -> T): T =
        lock(id).withLock { operation() }

    private fun lock(id: String): Mutex = locks[id] ?: throw IllegalArgumentException("Unknown subscription provider: $id")
    private fun config(id: String): Config = configs[id] ?: throw IllegalArgumentException("Unknown subscription provider: $id")

    private data class HttpResult(val status: Int, val data: JsonObject) {
        fun checked(): JsonObject {
            if (status !in 200..299 || data.text("error") != null) {
                throw ProviderOAuthException(ProviderOAuthException.Reason.REQUEST_FAILED, status)
            }
            return data
        }
    }

    private suspend fun request(url: String, body: RequestBody? = null, token: String? = null): HttpResult {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Bob/1.0")
        if (body != null) builder.post(body)
        if (token != null) builder.header("Authorization", "token $token")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val text = it.body?.string().orEmpty()
                            val data = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
                            // OpenAI returns empty/non-JSON 403/404 while waiting for approval.
                            HttpResult(it.code, data ?: JsonObject(emptyMap()))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private data class Config(val clientId: String, val deviceEndpoint: String, val tokenEndpoint: String, val scope: String, val baseURL: String)

    private companion object {
        const val ACCESS = "oauthAccessToken"
        const val REFRESH = "oauthRefreshToken"
        const val EXPIRY = "oauthTokenExpiresAt"
        const val ACCOUNT = "oauthAccountId"
        const val USERNAME = "oauthUsername"
        const val BEARER = "oauthBearerToken"
        const val BEARER_EXPIRY = "oauthBearerTokenExpiresAt"
        val secretKeys = setOf(ACCESS, REFRESH, EXPIRY, ACCOUNT, USERNAME, BEARER, BEARER_EXPIRY)
        val configs = mapOf(
            "chatgpt" to Config("app_EMoamEEZ73f0CkXaXp7hrann", "https://auth.openai.com/api/accounts/deviceauth/usercode", "https://auth.openai.com/oauth/token", "", "https://chatgpt.com/backend-api/codex"),
            "githubcopilot" to Config("Iv1.b507a08c87ecfe98", "https://github.com/login/device/code", "https://github.com/login/oauth/access_token", "read:user", "https://api.githubcopilot.com"),
            "supergrok" to Config("b1a00492-073a-47ea-816f-4c329264a828", "https://auth.x.ai/oauth2/device/code", "https://auth.x.ai/oauth2/token", "openid profile email offline_access grok-cli:access api:access", "https://api.x.ai/v1"),
        )
        // Shared across direct runtime instances and the injected settings instance.
        val locks = configs.keys.associateWith { Mutex() }
        val attempts = ConcurrentHashMap<String, String>()
        fun form(vararg pairs: Pair<String, String>): FormBody = FormBody.Builder().apply { pairs.forEach { (key, value) -> add(key, value) } }.build()
        fun jsonBody(values: Map<String, String>): RequestBody = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().toRequestBody("application/json".toMediaType())
        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun JsonObject.required(key: String): String = text(key) ?: throw ProviderOAuthException(ProviderOAuthException.Reason.INVALID_RESPONSE)
        fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
    }
}
