package cc.jaxy.bob.feature.agents

import androidx.lifecycle.ViewModelStore
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.agent.AgentRepositoryImpl
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.files.FileRepository
import cc.jaxy.bob.core.data.local.AgentRow
import cc.jaxy.bob.core.data.local.LocalDao
import cc.jaxy.bob.core.data.search.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditViewModelTest {
    @After
    fun resetDispatcher() { Dispatchers.resetMain() }

    @Test
    fun `creating agents saves chosen search policy and other editor fields`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (choice in listOf(WebSearchPolicy(), WebSearchPolicy(WebSearchMode.NATIVE), WebSearchPolicy(WebSearchMode.PROVIDER, "missing"))) {
            val fixture = Fixture()
            try {
                val vm = fixture.vm
                vm.bind(null)
                vm.onTitleChange("Researcher")
                vm.onDescriptionChange("Research assistant")
                vm.onSystemRoleChange("Check sources")
                vm.onOpeningMessageChange("What should I research?")
                vm.addQuestion()
                vm.onQuestionChange(0, "Recent discoveries")
                vm.setModel(AIModel(id = "model-id", providerId = "provider-id"))
                vm.onWebSearchChange(choice)
                vm.save()
                runCurrent()

                val id = requireNotNull(vm.uiState.value.createdId)
                val config = fixture.repository().getConfig(id).value()
                assertEquals(choice, config.webSearch)
                assertEquals("Researcher", config.title)
                assertEquals("Research assistant", config.description)
                assertEquals("Check sources", config.systemRole)
                assertEquals("What should I research?", config.openingMessage)
                assertEquals(listOf("Recent discoveries"), config.openingQuestions)
                assertEquals("model-id", config.model)
                assertEquals("provider-id", config.provider)
            } finally {
                fixture.store.clear()
                runCurrent()
            }
        }
    }

    @Test
    fun `editing and reloading retains explicit choices`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val id = fixture.repository().create("Researcher", systemRole = "Original prompt", webSearch = WebSearchPolicy()).value()
        try {
            val vm = fixture.vm
            vm.bind(id)
            runCurrent()
            assertEquals(WebSearchPolicy(), vm.uiState.value.webSearch)
            vm.onDescriptionChange("Edited description")
            vm.save()
            runCurrent()
            assertTrue(vm.uiState.value.saved)
            assertEquals(WebSearchPolicy(), fixture.repository().getConfig(id).value().webSearch)

            vm.onWebSearchChange(WebSearchPolicy(WebSearchMode.NATIVE))
            assertFalse(vm.uiState.value.saved)
            vm.save()
            runCurrent()
            assertTrue(vm.uiState.value.saved)
            assertEquals(WebSearchPolicy(WebSearchMode.NATIVE), fixture.repository().getConfig(id).value().webSearch)

            // Unsaved draft is discarded by an explicit reload (storage wins).
            vm.onWebSearchChange(WebSearchPolicy())
            vm.load()
            runCurrent()
            assertEquals(WebSearchPolicy(WebSearchMode.NATIVE), vm.uiState.value.webSearch)
            vm.onWebSearchChange(WebSearchPolicy(WebSearchMode.NATIVE))
            vm.save()
            runCurrent()
            vm.load()
            runCurrent()
            assertEquals(WebSearchPolicy(WebSearchMode.NATIVE), vm.uiState.value.webSearch)
            val config = fixture.repository().getConfig(id).value()
            assertEquals("Original prompt", config.systemRole)
            assertEquals("Edited description", config.description)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `failed override save exposes error keeps unsaved choice and can retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        val id = fixture.repository().create("Researcher", webSearch = WebSearchPolicy(WebSearchMode.NATIVE)).value()
        try {
            val vm = fixture.vm
            vm.bind(id)
            runCurrent()
            vm.onWebSearchChange(WebSearchPolicy())
            fixture.failWrites = true
            vm.save()
            runCurrent()

            assertNotNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.saving)
            assertFalse(vm.uiState.value.saved)
            assertEquals(WebSearchPolicy(), vm.uiState.value.webSearch)
            assertEquals(WebSearchPolicy(WebSearchMode.NATIVE), fixture.repository().getConfig(id).value().webSearch)

            fixture.failWrites = false
            vm.save()
            runCurrent()
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.saved)
            assertEquals(WebSearchPolicy(), fixture.repository().getConfig(id).value().webSearch)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `provider changes never rewrite the selected policy and bind retains observed providers`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            val provider = WebSearchProvider("custom-one", "Research service", WebSearchProtocol.BRAVE, "https://example.com", enabled = true)
            f.providers.providers.value = listOf(provider)
            runCurrent()
            f.vm.bind(null)
            assertEquals(listOf(provider), f.vm.uiState.value.searchProviders)
            val policy = WebSearchPolicy(WebSearchMode.PROVIDER, provider.id)
            f.vm.onWebSearchChange(policy)
            f.providers.providers.value = listOf(provider.copy(enabled = false))
            runCurrent()
            assertEquals(policy, f.vm.uiState.value.webSearch)
            assertFalse(f.vm.uiState.value.searchProviders.single().enabled)
            f.providers.providers.value = emptyList()
            runCurrent()
            assertEquals(policy, f.vm.uiState.value.webSearch)
            f.vm.onTitleChange("Researcher")
            f.vm.save()
            runCurrent()
            assertEquals(policy, f.repository().getConfig(requireNotNull(f.vm.uiState.value.createdId)).value().webSearch)
        } finally {
            f.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `failed load cannot overwrite stored configuration and retry succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.vm.bind("missing")
            f.vm.onTitleChange("Must not save while loading")
            f.vm.save()
            runCurrent()
            assertTrue(f.vm.uiState.value.loadFailed)
            f.vm.dismissError()
            assertNotNull(f.vm.uiState.value.error)
            f.vm.onTitleChange("Must not save after failure")
            f.vm.save()
            runCurrent()
            assertTrue(f.rows.isEmpty())
            f.rows["missing"] = AgentRow("missing", """{"agent":{"id":"missing"},"config":{"title":"Recovered","webSearch":{"mode":"off"}}}""")
            f.vm.load()
            runCurrent()
            assertFalse(f.vm.uiState.value.loadFailed)
            assertEquals("Recovered", f.vm.uiState.value.title)
            assertNull(f.vm.uiState.value.error)
        } finally {
            f.store.clear()
            runCurrent()
        }
    }

    private class Providers : WebSearchProviderRepository {
        override val providers = MutableStateFlow<List<WebSearchProvider>>(emptyList())
        override suspend fun save(provider: WebSearchProvider, apiKey: String?) = error("Unexpected save")
        override suspend fun delete(id: String) = error("Unexpected delete")
        override suspend fun apiKey(id: String): String? = error("Unexpected key read")
    }

    private class Fixture {
        val rows = linkedMapOf<String, AgentRow>()
        var failWrites = false
        val providers = Providers()
        private val dao = Proxy.newProxyInstance(LocalDao::class.java.classLoader, arrayOf(LocalDao::class.java)) { proxy, method, args ->
            when (method.name) {
                "agent" -> rows[args?.firstOrNull() as String]
                "putAgent" -> {
                    check(!failWrites) { "Storage unavailable" }
                    val row = args?.firstOrNull() as AgentRow
                    rows[row.id] = row
                    Unit
                }
                "toString" -> "AgentEditorDaoTestProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected local DAO call: ${method.name}")
            }
        } as LocalDao
        private val files = Proxy.newProxyInstance(FileRepository::class.java.classLoader, arrayOf(FileRepository::class.java)) { _, method, _ ->
            error("Unexpected file operation: ${method.name}")
        } as FileRepository
        val vm = AgentEditViewModel(repository(), files, providers)
        val store = ViewModelStore().apply { put("editor", vm) }
        fun repository() = AgentRepositoryImpl(dao, providers)
    }

    private fun <T> AnResult<T>.value(): T = when (this) {
        is AnResult.Ok -> value
        is AnResult.Err -> error("Unexpected repository error: $error")
    }
}
