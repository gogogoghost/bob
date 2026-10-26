package cc.jaxy.anlobehub.core.data.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.jaxy.anlobehub.core.data.preferences.UiPreferencesStore
import cc.jaxy.anlobehub.core.data.provider.ProviderSecretStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebSearchProviderRepositoryTest {

    @Test
    fun `keys use namespaced vault IDs and never enter backed up configuration`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = builtin("tavily").copy(enabled = true)

        repository.save(provider, "only-in-encrypted-vault")

        assertEquals(mapOf("apiKey" to "only-in-encrypted-vault"), secrets.values["search.tavily"])
        assertTrue(secrets.values.keys.all { ':' !in it })
        assertFalse(store.data.first().asMap().values.joinToString().contains("only-in-encrypted-vault"))
        val reopened = WebSearchProviderRepositoryImpl(store, secrets)
        assertEquals(provider, reopened.providers.first().single { it.id == provider.id })
        assertEquals("only-in-encrypted-vault", reopened.apiKey(provider.id))
    }

    @Test
    fun `null retains a key and empty clears only after disabling`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val enabled = builtin("brave").copy(enabled = true)
        repository.save(enabled, "saved-key")
        val edited = enabled.copy(endpoint = "https://proxy.example/search")
        repository.save(edited)
        assertEquals("saved-key", repository.apiKey(enabled.id))
        assertEquals(edited, repository.providers.first().single { it.id == edited.id })

        expectFailure<IllegalArgumentException> { repository.save(edited, "") }
        assertEquals("saved-key", repository.apiKey(enabled.id))
        assertTrue(repository.providers.first().single { it.id == enabled.id }.enabled)

        repository.save(edited.copy(enabled = false), "")
        assertNull(repository.apiKey(enabled.id))
        assertFalse(secrets.values.containsKey("search.brave"))
        assertFalse(repository.providers.first().single { it.id == enabled.id }.enabled)
    }

    @Test
    fun `enabling requires the currently saved key`() = runBlocking {
        val repository = WebSearchProviderRepositoryImpl(SearchMemoryPreferences(), SearchMemorySecrets())
        val provider = custom()
        repository.save(provider)
        expectFailure<IllegalArgumentException> { repository.save(provider.copy(enabled = true)) }
        expectFailure<IllegalArgumentException> { repository.save(provider.copy(enabled = true), "   ") }
        expectFailure<IllegalArgumentException> { repository.save(provider.copy(enabled = true), "bad\r\nkey") }
        assertFalse(repository.providers.first().single { it.id == provider.id }.enabled)
        repository.save(provider.copy(enabled = true), "working-key")
        assertTrue(repository.providers.first().single { it.id == provider.id }.enabled)
    }

    @Test
    fun `save rejects unsafe endpoints before changing a key or provider`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom()
        repository.save(provider, "saved-key")
        for (endpoint in listOf("", "not a url", "ftp://example.com/search", "https://name:password@example.com/search", "https://example.com/search?api_key=secret", "https://example.com/#fragment", "https://example.com/\nsearch")) {
            expectFailure<IllegalArgumentException> { repository.save(provider.copy(endpoint = endpoint), "replacement") }
        }
        assertEquals(provider, repository.providers.first().single { it.id == provider.id })
        assertEquals("saved-key", repository.apiKey(provider.id))
    }

    @Test
    fun `unconfigured SearXNG cannot enable and configured instance needs no key`() = runBlocking {
        val repository = WebSearchProviderRepositoryImpl(SearchMemoryPreferences(), SearchMemorySecrets())
        val provider = builtin("searxng")
        repository.save(provider)
        expectFailure<IllegalArgumentException> { repository.save(provider.copy(enabled = true)) }
        val configured = provider.copy(endpoint = "http://127.0.0.1:8888/prefix", enabled = true)
        repository.save(configured)
        assertEquals(configured, repository.providers.first().single { it.id == provider.id })
        assertNull(repository.apiKey(provider.id))
    }

    @Test
    fun `custom providers edit and delete credentials without rewriting selected policy`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val preferences = UiPreferencesStore(store)
        val provider = custom().copy(enabled = true)
        repository.save(provider, "custom-key")
        val policy = WebSearchPolicy(WebSearchMode.PROVIDER, provider.id)
        preferences.setWebSearchPolicy(policy)
        val edited = provider.copy(name = "Updated service", endpoint = "http://127.0.0.1:8080/search")
        repository.save(edited)
        assertEquals(edited, repository.providers.first().single { it.id == provider.id })
        repository.delete(provider.id)
        assertFalse(repository.providers.first().any { it.id == provider.id })
        assertNull(repository.apiKey(provider.id))
        assertFalse(secrets.values.containsKey("search.${provider.id}"))
        assertEquals(policy, preferences.webSearchSettings.first().policy)
    }

    @Test
    fun `builtins cannot delete or impersonate custom protocols`() = runBlocking {
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(SearchMemoryPreferences(), secrets)
        expectFailure<IllegalArgumentException> { repository.delete("tavily") }
        expectFailure<IllegalArgumentException> { repository.save(builtin("tavily").copy(protocol = WebSearchProtocol.EXA), "key") }
        expectFailure<IllegalArgumentException> { repository.save(builtin("tavily").copy(builtin = false), "key") }
        expectFailure<IllegalArgumentException> { repository.save(custom().copy(builtin = true), "key") }
        for (id in listOf("custom", "custom-../escape", "custom-not-a-uuid", "search:tavily", "../tavily")) {
            expectFailure<IllegalArgumentException> { repository.save(custom().copy(id = id), "key") }
        }
        assertTrue(secrets.values.isEmpty())
        assertEquals(WebSearchCatalog.providers, repository.providers.first())
    }

    @Test
    fun `config write failure restores the previous key and propagates the same failure`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom().copy(enabled = true)
        repository.save(provider, "original")
        val failure = IOException("disk full")
        store.failNextUpdate = failure

        assertPropagatedFailure(failure, expectFailure<IOException> { repository.save(provider.copy(name = "Changed"), "replacement") })
        assertEquals("original", repository.apiKey(provider.id))
        assertEquals(provider, repository.providers.first().single { it.id == provider.id })
    }

    @Test
    fun `failed new configuration removes its newly written key`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        repository.providers.first()
        val provider = custom()
        store.failNextUpdate = IOException("write failed")
        expectFailure<IOException> { repository.save(provider, "new-key") }
        assertFalse(secrets.values.containsKey("search.${provider.id}"))
        assertFalse(repository.providers.first().any { it.id == provider.id })
    }

    @Test
    fun `failed deletion restores its key and configuration`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom()
        repository.save(provider, "saved-key")
        store.failNextUpdate = IOException("write failed")
        expectFailure<IOException> { repository.delete(provider.id) }
        assertEquals("saved-key", repository.apiKey(provider.id))
        assertEquals(provider, repository.providers.first().single { it.id == provider.id })
    }

    @Test
    fun `vault failure cannot publish changed provider configuration`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom()
        repository.save(provider, "saved-key")
        val failure = IOException("vault unavailable")
        secrets.failNextWrite = failure
        assertPropagatedFailure(failure, expectFailure<IOException> { repository.save(provider.copy(enabled = true), "replacement") })
        assertEquals(provider, repository.providers.first().single { it.id == provider.id })
        assertEquals("saved-key", repository.apiKey(provider.id))
    }

    @Test
    fun `vault cancellation is preserved and partial key changes roll back`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom()
        repository.save(provider, "saved-key")
        val cancellation = CancellationException("cancelled")
        secrets.afterWrite = {
            if (it["apiKey"] == "replacement") throw cancellation
        }
        assertPropagatedFailure(cancellation, expectFailure<CancellationException> { repository.save(provider.copy(enabled = true), "replacement") })
        assertEquals("saved-key", repository.apiKey(provider.id))
        assertEquals(provider, repository.providers.first().single { it.id == provider.id })
    }

    @Test
    fun `caller cancellation between writes finishes a consistent commit`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom().copy(enabled = true)
        repository.providers.first()
        val keyWritten = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        secrets.afterWrite = {
            keyWritten.complete(Unit)
            finishWrite.await()
        }
        val saving = launch { repository.save(provider, "new-key") }
        keyWritten.await()
        saving.cancel()
        finishWrite.complete(Unit)
        saving.join()
        assertTrue(saving.isCancelled)
        assertEquals(provider, repository.providers.first().single { it.id == provider.id })
        assertEquals("new-key", repository.apiKey(provider.id))
    }

    @Test
    fun `rollback failure is attached without replacing the original failure`() = runBlocking {
        val store = SearchMemoryPreferences()
        val secrets = SearchMemorySecrets()
        val repository = WebSearchProviderRepositoryImpl(store, secrets)
        val provider = custom()
        repository.save(provider, "old-key")
        val diskFailure = IOException("configuration failed")
        val rollbackFailure = IOException("rollback failed")
        secrets.afterWrite = { if (it["apiKey"] == "new-key") secrets.failNextWrite = rollbackFailure }
        store.failNextUpdate = diskFailure
        val actual = expectFailure<IOException> { repository.save(provider, "new-key") }
        val original = assertPropagatedFailure(diskFailure, actual)
        assertEquals(listOf(rollbackFailure), original.suppressed.toList())
    }

    private fun assertPropagatedFailure(expected: Throwable, actual: Throwable): Throwable {
        // Coroutine debug-mode stacktrace recovery copies exceptions and retains their original as the cause.
        assertEquals(expected.javaClass, actual.javaClass)
        val original = generateSequence(actual) { it.cause }.firstOrNull { it === expected }
        assertSame(expected, original)
        return requireNotNull(original)
    }

    private fun builtin(id: String) = WebSearchCatalog.providers.single { it.id == id }

    private fun custom() = WebSearchProvider(
        id = "custom-${UUID.randomUUID()}",
        name = "My service",
        protocol = WebSearchProtocol.TAVILY,
        endpoint = "https://search.example/search",
    )
}

internal class SearchMemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    var failNextUpdate: Exception? = null
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        val next = transform(state.value)
        failNextUpdate?.let { failure ->
            failNextUpdate = null
            throw failure
        }
        state.value = next
        next
    }
}

internal class SearchMemorySecrets : ProviderSecretStore {
    val values = mutableMapOf<String, Map<String, String>>()
    var failNextWrite: Exception? = null
    var afterWrite: (suspend (Map<String, String>) -> Unit)? = null

    override suspend fun read(providerId: String): Map<String, String> = values[providerId].orEmpty()

    override suspend fun write(providerId: String, secrets: Map<String, String>) {
        failNextWrite?.let { failure ->
            failNextWrite = null
            throw failure
        }
        if (secrets.isEmpty()) values.remove(providerId) else values[providerId] = secrets.toMap()
        afterWrite?.invoke(secrets)
    }
}

internal suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw failure
    }
    fail("Expected ${T::class.java.simpleName}")
    error("unreachable")
}
