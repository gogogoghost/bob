package cc.jaxy.bob.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import cc.jaxy.bob.core.data.search.WebSearchCatalog
import cc.jaxy.bob.core.data.search.WebSearchProtocol
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchProductViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> keep(vm: T): T = vm.also {
        stores += ViewModelStore().apply { put("test", vm) }
    }

    @Test
    fun `overview waits for providers and never writes while loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply { readGate = CompletableDeferred() }
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        assertTrue(vm.uiState.value.loading)
        vm.setProviderEnabled("tavily", true)
        runCurrent()
        assertTrue(repository.saves.isEmpty())
        repository.readGate!!.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.canEdit)
    }

    @Test
    fun `enabling missing credentials requests configuration without saving`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake()
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        vm.setProviderEnabled("tavily", true)
        runCurrent()
        assertEquals("tavily", vm.uiState.value.configureProviderId)
        assertFalse(vm.uiState.value.pending)
        assertTrue(repository.saves.isEmpty())
        vm.consumeConfigurationRequest()
        assertNull(vm.uiState.value.configureProviderId)
    }

    @Test
    fun `enabling then disabling flips the stored flag twice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply { keys["tavily"] = "private-key" }
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        assertFalse(vm.uiState.value.providers.single { it.id == "tavily" }.enabled)
        vm.setProviderEnabled("tavily", true)
        runCurrent()
        assertTrue(vm.uiState.value.providers.single { it.id == "tavily" }.enabled)
        vm.setProviderEnabled("tavily", false)
        runCurrent()
        assertFalse(vm.uiState.value.providers.single { it.id == "tavily" }.enabled)
        assertEquals(listOf(true, false), repository.saves.map { it.first.enabled })
    }

    @Test
    fun `unconfigured searxng requests endpoint configuration without requiring a key`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake(listOf(searchProvider("searxng").copy(
            protocol = WebSearchProtocol.SEARXNG, endpoint = "",
        )))
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        vm.setProviderEnabled("searxng", true)
        runCurrent()
        assertEquals("searxng", vm.uiState.value.configureProviderId)
        assertTrue(repository.saves.isEmpty())
        repository.saved.value = repository.saved.value.map { it.copy(endpoint = "https://search.example/search") }
        runCurrent()
        vm.setProviderEnabled("searxng", true)
        runCurrent()
        assertTrue(repository.saved.value.single().enabled)
        assertTrue(repository.keyReads.isEmpty())
    }

    @Test
    fun `pending provider write blocks conflicting toggles and failure hides secrets`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply {
            keys["tavily"] = "private-key"
            writeGate = CompletableDeferred()
            writeFailure = IOException("secret-in-exception")
        }
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        vm.setProviderEnabled("tavily", true)
        vm.setProviderEnabled("tavily", false)
        runCurrent()
        assertTrue(vm.uiState.value.pending)
        assertEquals(1, repository.saves.size)
        repository.writeGate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.pending)
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.providers.single { it.id == "tavily" }.enabled)
        assertFalse(vm.uiState.value.toString().contains("secret-in-exception"))
    }

    @Test
    fun `overview read errors block writes and retry restores the list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply { readFailure = IOException("failed") }
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.canEdit)
        vm.setProviderEnabled("tavily", true)
        runCurrent()
        assertTrue(repository.saves.isEmpty())
        repository.readFailure = null
        vm.load()
        runCurrent()
        assertTrue(vm.uiState.value.canEdit)
        assertEquals(listOf(searchProvider()), vm.uiState.value.providers)
    }
    @Test
    fun `provider enable failure keeps the service disabled and hides secrets`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply {
            keys["tavily"] = "private-key"
            writeFailure = IOException("private-key")
        }
        val vm = keep(WebSearchOverviewViewModel(repository))
        runCurrent()
        vm.setProviderEnabled("tavily", true)
        runCurrent()
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.providers.single().enabled)
        assertFalse(repository.saved.value.single().enabled)
        assertFalse(vm.uiState.value.toString().contains("private-key"))
        repository.writeFailure = null
        vm.setProviderEnabled("tavily", true)
        runCurrent()
        assertTrue(vm.uiState.value.providers.single().enabled)
        assertFalse(vm.uiState.value.saveFailed)
    }


    @Test
    fun `detail loads masked field data in memory and duplicate binding retains draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply { keys["tavily"] = "original-secret" }
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        assertEquals("original-secret", vm.uiState.value.apiKey)
        vm.setEndpoint("https://draft.example/search")
        vm.setApiKey("draft-secret")
        vm.bind("tavily")
        repository.saved.value = listOf(searchProvider().copy(endpoint = "https://external.example/search"))
        runCurrent()
        assertEquals("https://draft.example/search", vm.uiState.value.draft!!.endpoint)
        assertEquals("draft-secret", vm.uiState.value.apiKey)
        assertTrue(vm.uiState.value.dirty)
        assertTrue(repository.saves.isEmpty())
        assertFalse(vm.uiState.value.toString().contains("secret"))
    }

    @Test
    fun `detail saves nonsecret edits with null key and clears memory after success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply { keys["tavily"] = "retained-secret" }
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        vm.setEndpoint("  https://new.example/search  ")
        vm.save()
        runCurrent()
        assertEquals("https://new.example/search", repository.saves.single().first.endpoint)
        // The detail page owns config, not the switch: stored enabled stays false.
        assertFalse(repository.saves.single().first.enabled)
        assertNull(repository.saves.single().second)
        assertEquals("retained-secret", repository.keys["tavily"])
        assertTrue(vm.uiState.value.finished)
        assertEquals("", vm.uiState.value.apiKey)
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `detail validates enabled key and endpoint without losing edits`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake(listOf(searchProvider(enabled = true)))
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        vm.save()
        assertEquals(SearchProviderValidation.KEY, vm.uiState.value.validation)
        vm.setApiKey("draft-key")
        for (endpoint in listOf("bad", "ftp://example.com", "https://user:secret@example.com", "https://example.com?q=secret", "https://example.com/#secret")) {
            vm.setEndpoint(endpoint)
            vm.save()
            assertEquals(SearchProviderValidation.ENDPOINT, vm.uiState.value.validation)
            assertEquals(endpoint, vm.uiState.value.draft!!.endpoint)
            assertEquals("draft-key", vm.uiState.value.apiKey)
        }
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `clearing the key of an enabled service is rejected in the detail page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake(listOf(searchProvider(enabled = true))).apply { keys["tavily"] = "stored" }
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        vm.setApiKey("")
        vm.save()
        assertEquals(SearchProviderValidation.KEY, vm.uiState.value.validation)
        assertTrue(repository.saves.isEmpty())
        // Disable from the list (the switch's only home), then reopen the
        // detail page: key removal is accepted with enabled=false stored.
        repository.saved.value = listOf(searchProvider(enabled = false))
        val reopened = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        reopened.bind("tavily")
        runCurrent()
        reopened.setApiKey("")
        reopened.save()
        runCurrent()
        assertEquals("", repository.saves.single().second)
        assertNull(repository.keys["tavily"])
    }

    @Test
    fun `resaving an enabled service without touching the key trusts the vault`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake(listOf(searchProvider(enabled = true))).apply { keys["tavily"] = "vault-secret" }
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        // save() clears the memory field; a second save (rename/endpoint) must not demand re-typing.
        vm.setEndpoint("https://proxy.example/search")
        vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.finished)
    }

    @Test
    fun `failed pending detail save keeps draft key and blocks duplicate saves or deletes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = searchProvider("custom-test", builtin = false)
        val repository = SearchRepositoryFake(listOf(provider)).apply {
            writeGate = CompletableDeferred()
            writeFailure = IOException("draft-secret")
        }
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind(provider.id)
        runCurrent()
        vm.setName("Draft name")
        vm.setApiKey("draft-secret")
        vm.save()
        vm.save()
        vm.setName("Ignored")
        vm.requestDelete()
        vm.confirmDelete()
        runCurrent()
        assertTrue(vm.uiState.value.pending)
        assertEquals(1, repository.saves.size)
        assertTrue(repository.deletes.isEmpty())
        repository.writeGate!!.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.saveFailed)
        assertEquals("Draft name", vm.uiState.value.draft!!.name)
        assertEquals("draft-secret", vm.uiState.value.apiKey)
        assertEquals(provider, vm.uiState.value.original)
        assertFalse(vm.uiState.value.toString().contains("draft-secret"))
        repository.writeFailure = null
        vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.finished)
        assertEquals("draft-secret", repository.keys[provider.id])
    }

    @Test
    fun `custom creation requires name and adopts selected official protocol defaults`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake()
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind(null)
        runCurrent()
        val id = vm.uiState.value.draft!!.id
        assertTrue(id.startsWith("custom-"))
        assertFalse(vm.uiState.value.draft!!.builtin)
        vm.save()
        assertEquals(SearchProviderValidation.NAME, vm.uiState.value.validation)
        vm.setName("  My search  ")
        vm.setProtocol(WebSearchProtocol.BRAVE)
        val official = WebSearchCatalog.providers.first { it.protocol == WebSearchProtocol.BRAVE }
        assertEquals(official.endpoint, vm.uiState.value.draft!!.endpoint)
        assertEquals(official.docsUrl, vm.uiState.value.draft!!.docsUrl)
        vm.setEndpoint("https://proxy.example/search")
        vm.setProtocol(WebSearchProtocol.EXA)
        assertEquals("https://proxy.example/search", vm.uiState.value.draft!!.endpoint)
        vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.finished)
        assertEquals("My search", repository.saves.single().first.name)
        assertEquals(id, repository.saves.single().first.id)
    }

    @Test
    fun `builtin identity is immutable and builtin deletion cannot be requested`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake()
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        vm.setName("renamed")
        vm.setProtocol(WebSearchProtocol.EXA)
        vm.requestDelete()
        vm.confirmDelete()
        runCurrent()
        assertEquals(searchProvider(), vm.uiState.value.draft)
        assertFalse(vm.uiState.value.confirmDelete)
        assertTrue(repository.deletes.isEmpty())
    }

    @Test
    fun `custom deletion requires confirmation and failed delete is recoverable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake(listOf(searchProvider("custom-test", builtin = false)))
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("custom-test")
        runCurrent()
        vm.confirmDelete()
        assertTrue(repository.deletes.isEmpty())
        vm.requestDelete()
        vm.dismissDelete()
        vm.confirmDelete()
        assertTrue(repository.deletes.isEmpty())
        repository.writeFailure = IOException("failed")
        vm.requestDelete()
        vm.confirmDelete()
        runCurrent()
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.finished)
        assertFalse(vm.uiState.value.confirmDelete)
        repository.writeFailure = null
        vm.requestDelete()
        vm.confirmDelete()
        runCurrent()
        assertEquals(listOf("custom-test", "custom-test"), repository.deletes)
        assertTrue(vm.uiState.value.finished)
        assertTrue(repository.saved.value.isEmpty())
    }

    @Test
    fun `detail load errors retry and missing provider never becomes a new custom service`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = SearchRepositoryFake().apply { keyFailure = IOException("private") }
        val vm = keep(WebSearchProviderViewModel(repository, testSearchClient()))
        vm.bind("tavily")
        runCurrent()
        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.canEdit)
        vm.setName("ignored")
        vm.save()
        assertTrue(repository.saves.isEmpty())
        repository.keyFailure = null
        vm.load()
        runCurrent()
        assertTrue(vm.uiState.value.canEdit)
        vm.bind("deleted")
        runCurrent()
        assertTrue(vm.uiState.value.missing)
        assertFalse(vm.uiState.value.canEdit)
        assertNull(vm.uiState.value.draft)
        vm.save()
        assertTrue(repository.saves.isEmpty())
    }
}

private fun testSearchClient(): cc.jaxy.bob.core.data.search.WebSearchClient =
    cc.jaxy.bob.core.data.search.WebSearchClient(okhttp3.OkHttpClient())

private fun searchProvider(id: String = "tavily", enabled: Boolean = false, builtin: Boolean = true) = WebSearchProvider(
    id = id, name = "Test search", protocol = WebSearchProtocol.TAVILY,
    endpoint = "https://search.example/search", enabled = enabled, builtin = builtin,
)

private class SearchRepositoryFake(initial: List<WebSearchProvider> = listOf(searchProvider())) : WebSearchProviderRepository {
    val saved = MutableStateFlow(initial)
    val saves = mutableListOf<Pair<WebSearchProvider, String?>>()
    val deletes = mutableListOf<String>()
    val keys = mutableMapOf<String, String>()
    val keyReads = mutableListOf<String>()
    var readGate: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null
    var readFailure: Exception? = null
    var writeFailure: Exception? = null
    var keyFailure: Exception? = null
    override val providers: Flow<List<WebSearchProvider>> = flow {
        readGate?.await()
        readFailure?.let { throw it }
        emitAll(saved)
    }
    override suspend fun apiKey(id: String): String? {
        keyReads += id
        keyFailure?.let { throw it }
        return keys[id]
    }
    override suspend fun save(provider: WebSearchProvider, apiKey: String?) {
        saves += provider to apiKey
        writeGate?.await()
        writeFailure?.let { throw it }
        if (apiKey != null) {
            if (apiKey.isEmpty()) keys.remove(provider.id) else keys[provider.id] = apiKey
        }
        saved.value = saved.value.filterNot { it.id == provider.id } + provider
    }
    override suspend fun delete(id: String) {
        deletes += id
        writeGate?.await()
        writeFailure?.let { throw it }
        saved.value = saved.value.filterNot { it.id == id }
        keys.remove(id)
    }
}
