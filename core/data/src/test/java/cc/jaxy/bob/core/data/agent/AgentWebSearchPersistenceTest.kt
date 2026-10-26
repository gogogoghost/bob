package cc.jaxy.bob.core.data.agent

import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.AgentRow
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.search.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentWebSearchPersistenceTest {
    private val choices = listOf(null, WebSearchPolicy(), WebSearchPolicy(WebSearchMode.NATIVE), WebSearchPolicy(WebSearchMode.PROVIDER, "custom-missing"))

    @Test fun `all policies persist and duplicate without losing configuration`() = runBlocking {
        val f = Fixture()
        for (choice in choices) {
            val id = f.repository.create("Researcher", systemRole = "Check sources", model = "model", provider = "provider", description = "Reports", avatar = "icon", openingMessage = "Hello", openingQuestions = listOf("News", ""), webSearch = choice).value()
            f.repository.updateConfig(id, mapOf("temperature" to JsonPrimitive(0.4), "backgroundColor" to JsonPrimitive("blue"))).value()
            val duplicate = f.repository.duplicate(id).value()
            for (key in listOf(id, duplicate)) {
                val c = f.repository.getConfig(key).value()
                assertEquals(choice, c.webSearch)
                assertEquals("Check sources", c.systemRole)
                assertEquals("model", c.model)
                assertEquals("provider", c.provider)
                assertEquals("Reports", c.description)
                assertEquals("icon", c.avatar)
                assertEquals("Hello", c.openingMessage)
                assertEquals(listOf("News"), c.openingQuestions)
                assertEquals(0.4, c.temperature)
                assertEquals("blue", c.backgroundColor)
                assertFalse(f.config(key).containsKey("webSearchEnabled"))
            }
            f.repository.updateConfig(duplicate, mapOf("webSearch" to JsonNull)).value()
            assertEquals(choice, f.repository.getConfig(id).value().webSearch)
            assertNull(f.repository.getConfig(duplicate).value().webSearch)
        }
    }

    @Test fun `legacy choices migrate once retaining unknown and known fields`() = runBlocking {
        for (configured in listOf(false, true)) {
            for ((legacy, expected) in listOf(
                "false" to WebSearchPolicy(),
                "true" to if (configured) WebSearchPolicy(WebSearchMode.PROVIDER, "searxng") else WebSearchPolicy(WebSearchMode.NATIVE),
                "null" to null,
                null to null,
            )) {
                val f = Fixture()
                if (configured) f.providers.providers.value = listOf(WebSearchProvider("searxng", "SearXNG", WebSearchProtocol.SEARXNG, "https://search.example", enabled = false))
                f.rows["legacy"] = AgentRow("legacy", """{"agent":{"id":"legacy","title":"Legacy"},"extra":42,"config":{"title":"Legacy","systemRole":"Keep","temperature":0.7,"unknown":"retained"${legacy?.let { ",\"webSearchEnabled\":$it" }.orEmpty()}}}""")
                assertEquals(expected, f.repository.getConfig("legacy").value().webSearch)
                assertFalse(f.config("legacy").containsKey("webSearchEnabled"))
                assertEquals(JsonPrimitive("retained"), f.config("legacy")["unknown"])
                assertEquals("Keep", f.repository.getConfig("legacy").value().systemRole)
                f.providers.providers.value = emptyList()
                assertEquals(expected, f.repository.getConfig("legacy").value().webSearch)
                f.repository.updateConfig("legacy", mapOf("description" to JsonPrimitive("Edited"))).value()
                val copy = f.repository.duplicate("legacy").value()
                assertEquals(JsonPrimitive("retained"), f.config(copy)["unknown"])
                assertEquals(expected, f.repository.getConfig(copy).value().webSearch)
            }
        }
    }

    @Test fun `invalid legacy boolean fails without rewriting`() = runBlocking {
        for (invalid in listOf("\"true\"", "1", "[]", "{}")) {
            val f = Fixture()
            val row = AgentRow("bad", """{"agent":{"id":"bad"},"config":{"webSearchEnabled":$invalid}}""")
            f.rows["bad"] = row
            assertTrue(f.repository.getConfig("bad") is AnResult.Err)
            assertTrue(f.repository.listAgents() is AnResult.Err)
            assertTrue(f.repository.duplicate("bad") is AnResult.Err)
            assertTrue(f.repository.updateConfig("bad", mapOf("title" to JsonPrimitive("No"))) is AnResult.Err)
            assertEquals(mapOf("bad" to row), f.rows)
        }
    }

    @Test fun `invalid canonical policies fail atomically`() = runBlocking {
        val f = Fixture()
        val id = f.repository.create("Researcher", webSearch = WebSearchPolicy(WebSearchMode.NATIVE)).value()
        val row = f.rows.getValue(id)
        for (invalid in listOf("true", "\"native\"", "{\"mode\":\"provider\"}", "{\"mode\":\"off\",\"providerId\":\"x\"}", "{\"mode\":\"unknown\"}")) {
            assertTrue(f.repository.updateConfig(id, mapOf("webSearch" to Json.parseToJsonElement(invalid), "title" to JsonPrimitive("No"))) is AnResult.Err)
            assertEquals(row, f.rows[id])
        }
        assertTrue(f.repository.updateConfig(id, mapOf("webSearchEnabled" to JsonPrimitive(true))) is AnResult.Err)
    }

    private class Fixture {
        val rows = linkedMapOf<String, AgentRow>()
        val providers = Providers()
        private val dao = Proxy.newProxyInstance(LocalDao::class.java.classLoader, arrayOf(LocalDao::class.java)) { _, method, args ->
            when (method.name) {
                "agent" -> rows[args!![0] as String]
                "agents" -> rows.values.toList()
                "putAgent" -> { val row = args!![0] as AgentRow; rows[row.id] = row; Unit }
                else -> error("Unexpected DAO call ${method.name}")
            }
        } as LocalDao
        val repository = AgentRepositoryImpl(dao, providers)
        fun config(id: String) = Json.parseToJsonElement(rows.getValue(id).payload).jsonObject.getValue("config").jsonObject
    }
    private class Providers : WebSearchProviderRepository {
        override val providers = MutableStateFlow<List<WebSearchProvider>>(emptyList())
        override suspend fun save(provider: WebSearchProvider, apiKey: String?) = error("Unexpected save")
        override suspend fun delete(id: String) = error("Unexpected delete")
        override suspend fun apiKey(id: String): String? = error("Unexpected key read")
    }
    private fun <T> AnResult<T>.value(): T = when (this) {
        is AnResult.Ok -> value
        is AnResult.Err -> error("Unexpected repository error: $error")
    }
}
