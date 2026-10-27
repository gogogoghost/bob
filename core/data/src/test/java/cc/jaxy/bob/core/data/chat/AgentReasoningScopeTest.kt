package cc.jaxy.bob.core.data.chat

import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.local.CatalogSeeder
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.local.ReasoningRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentReasoningScopeTest {
    @Test
    fun `reasoning settings are independent per agent with a global fallback`() = runBlocking {
        val rows = mutableMapOf<Triple<String, String, String>, ReasoningRow>()
        val dao = java.lang.reflect.Proxy.newProxyInstance(
            LocalDao::class.java.classLoader,
            arrayOf(LocalDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "reasoning" -> rows[Triple(args!![0] as String, args[1] as String, args[2] as String)]
                "putReasoning" -> {
                    val row = args!![0] as ReasoningRow
                    rows[Triple(row.agentId, row.providerId, row.modelId)] = row
                    Unit
                }
                "deleteReasoning" -> {
                    rows.remove(Triple(args!![0] as String, args[1] as String, args[2] as String))
                    Unit
                }
                "toString" -> "ReasoningDaoTestProxy"
                "hashCode" -> System.identityHashCode(rows)
                "equals" -> rows === args?.firstOrNull()
                else -> error("Unexpected local DAO call: ${method.name}")
            }
        } as LocalDao
        val repository = ModelRepositoryImpl(dao, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })

        repository.updateReasoningConfig("", "model", "provider", mapOf("effort" to "medium"))

        repository.updateReasoningConfig("agent-a", "model", "provider", mapOf("effort" to "high"))
        repository.updateReasoningConfig("agent-b", "model", "provider", mapOf("effort" to "low"))

        assertEquals("high", (repository.getReasoningConfig("agent-a", "model", "provider") as AnResult.Ok).value.effort)
        assertEquals("low", (repository.getReasoningConfig("agent-b", "model", "provider") as AnResult.Ok).value.effort)
        // An agent without an override still sees the pre-migration global value.
        assertEquals("medium", (repository.getReasoningConfig("agent-c", "model", "provider") as AnResult.Ok).value.effort)
        assertEquals("medium", (repository.getReasoningConfig("", "model", "provider") as AnResult.Ok).value.effort)

        repository.updateReasoningConfig("agent-a", "model", "provider", emptyMap())
        assertEquals("medium", (repository.getReasoningConfig("agent-a", "model", "provider") as AnResult.Ok).value.effort)
    }
}
