package cc.jaxy.anlobehub.core.data.provider

import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.local.CatalogSeeder
import cc.jaxy.anlobehub.core.data.local.LocalDao
import cc.jaxy.anlobehub.core.data.local.ProviderRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialPersistenceTest {
    @Test
    fun `new credentials stay out of provider database config`() = runBlocking {
        val rows = linkedMapOf<String, ProviderRow>()
        val secrets = MemorySecrets()
        val repository = repository(rows, secrets)
        val id = (repository.createCustom("Local endpoint", "http://127.0.0.1:1234/v1") as AnResult.Ok).value
        assertFalse(rows.getValue(id).enabled)
        val apiKey = "local-test-secret"

        assertTrue(repository.updateConfig(id, mapOf("apiKey" to apiKey)) is AnResult.Ok)

        val config = Json.decodeFromString<Map<String, String>>(rows.getValue(id).config)
        assertFalse(config.containsKey("apiKey"))
        assertEquals(apiKey, secrets.values.getValue(id).getValue("apiKey"))
        val detail = (repository.getDetail(id) as AnResult.Ok).value
        assertEquals(apiKey, detail.apiKey)
    }
    @Test
    fun `provider-specific secret fields stay out of database config`() = runBlocking {
        val id = "bedrock"
        val rows = linkedMapOf(
            id to ProviderRow(id, "Bedrock", "builtin", true, "{}"),
        )
        val secrets = MemorySecrets()
        val repository = repository(rows, secrets)
        val values = mapOf(
            "accessKeyId" to "aws-access-id",
            "secretAccessKey" to "aws-secret-access-key",
            "sessionToken" to "aws-session-token",
            "region" to "us-east-1",
        )

        assertTrue(repository.updateConfig(id, values) is AnResult.Ok)

        val config = Json.decodeFromString<Map<String, String>>(rows.getValue(id).config)
        assertEquals(mapOf("region" to "us-east-1"), config)
        val storedSecrets = secrets.values.getValue(id)
        assertEquals("aws-access-id", storedSecrets.getValue("accessKeyId"))
        assertEquals("aws-secret-access-key", storedSecrets.getValue("secretAccessKey"))
        assertEquals("aws-session-token", storedSecrets.getValue("sessionToken"))
        val detail = (repository.getDetail(id) as AnResult.Ok).value
        assertEquals("us-east-1", detail.keyVaults["region"])
        assertEquals("aws-secret-access-key", detail.keyVaults["secretAccessKey"])
    }


    @Test
    fun `legacy plaintext credentials migrate without losing the configured key`() = runBlocking {
        val id = "custom-legacy"
        val apiKey = "legacy-test-secret"
        val rows = linkedMapOf(
            id to ProviderRow(
                id = id,
                name = "Legacy endpoint",
                source = "custom",
                enabled = true,
                config = Json.encodeToString(
                    mapOf(
                        "baseURL" to "http://127.0.0.1:1234/v1",
                        "apiKey" to apiKey,
                        "__sdkType" to "openai",
                    ),
                ),
            ),
        )
        val secrets = MemorySecrets()
        val repository = repository(rows, secrets)

        val detail = (repository.getDetail(id) as AnResult.Ok).value

        assertEquals(apiKey, detail.apiKey)
        assertEquals(apiKey, secrets.values.getValue(id).getValue("apiKey"))
        val persisted = Json.decodeFromString<Map<String, String>>(rows.getValue(id).config)
        assertFalse(persisted.containsKey("apiKey"))
    }

    @Test
    fun `settings snapshots cannot expose or overwrite refreshed OAuth tokens`() = runBlocking {
        val id = "chatgpt"
        val rows = linkedMapOf(id to ProviderRow(id, "ChatGPT", "builtin", true, "{}"))
        val secrets = MemorySecrets().apply {
            values[id] = mapOf("oauthAccessToken" to "current-access", "oauthRefreshToken" to "current-refresh")
        }
        val repository = repository(rows, secrets)

        val detail = (repository.getDetail(id) as AnResult.Ok).value
        assertTrue(detail.keyVaults.keys.none { it.startsWith("oauth") })
        assertTrue(repository.updateConfig(id, mapOf("oauthAccessToken" to "stale-access", "oauthRefreshToken" to "stale-refresh")) is AnResult.Ok)

        assertEquals("current-access", secrets.values.getValue(id)["oauthAccessToken"])
        assertEquals("current-refresh", secrets.values.getValue(id)["oauthRefreshToken"])
        assertFalse(Json.decodeFromString<Map<String, String>>(rows.getValue(id).config).containsKey("oauthRefreshToken"))
    }

    private fun repository(
        rows: MutableMap<String, ProviderRow>,
        secrets: MemorySecrets,
    ): ProviderRepositoryImpl {
        val dao = Proxy.newProxyInstance(
            LocalDao::class.java.classLoader,
            arrayOf(LocalDao::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "provider" -> rows[args?.firstOrNull() as String]
                "providers" -> rows.values.toList()
                "models" -> emptyList<cc.jaxy.anlobehub.core.data.local.ModelRow>()
                "putModels" -> Unit
                "putProvider" -> {
                    val row = args?.firstOrNull() as ProviderRow
                    rows[row.id] = row
                    Unit
                }
                "toString" -> "ProviderDaoTestProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected local DAO call: ${method.name}")
            }
        } as LocalDao
        return ProviderRepositoryImpl(
            dao,
            secrets,
            CatalogSeeder(dao) { "{\"providers\":[],\"models\":[]}" },
        )
    }

    private class MemorySecrets : ProviderSecretStore {
        val values = mutableMapOf<String, Map<String, String>>()

        override suspend fun read(providerId: String): Map<String, String> =
            values[providerId].orEmpty()

        override suspend fun write(providerId: String, secrets: Map<String, String>) {
            if (secrets.isEmpty()) values.remove(providerId) else values[providerId] = secrets.toMap()
        }
    }
}
