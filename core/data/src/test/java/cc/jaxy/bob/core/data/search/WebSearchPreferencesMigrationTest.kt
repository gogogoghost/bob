package cc.jaxy.bob.core.data.search

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import cc.jaxy.bob.core.common.preferences.AppLanguage
import cc.jaxy.bob.core.common.preferences.AppTheme
import cc.jaxy.bob.core.data.preferences.UiPreferencesStore
import cc.jaxy.bob.core.data.preferences.WebSearchPreferenceStorage
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchPreferencesMigrationTest {
    private val legacyEnabled = booleanPreferencesKey("web_search_enabled")
    private val legacyUrl = stringPreferencesKey("searxng_url")
    private val language = stringPreferencesKey("app_language")
    private val theme = stringPreferencesKey("app_theme")
    private val dynamicColor = booleanPreferencesKey("dynamic_color")

    private suspend fun migratedPolicy(store: SearchMemoryPreferences): WebSearchPolicy {
        WebSearchPreferenceStorage.migrate(store)
        return WebSearchPreferenceStorage.readPolicy(store.data.first())
    }

    @Test
    fun `fresh install has no global policy and every built in provider disabled`() = runBlocking {
        val store = SearchMemoryPreferences()
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        assertEquals(WebSearchPolicy(), migratedPolicy(store))
        assertEquals(WebSearchCatalog.providers, repository.providers.first())
        assertTrue(repository.providers.first().all { !it.enabled })
    }

    @Test
    fun `legacy URL and toggle migrate atomically regardless of first reader`() = runBlocking {
        for (enabled in listOf<Boolean?>(null, false, true)) {
            for (url in listOf("", "   ", " https://search.example/prefix/ ")) {
                for (readProvidersFirst in listOf(false, true)) {
                    val initial = mutablePreferencesOf(
                        legacyUrl to url,
                        language to "zh-CN",
                        theme to "DARK",
                        dynamicColor to true,
                    )
                    if (enabled != null) initial[legacyEnabled] = enabled
                    val store = SearchMemoryPreferences(initial)
                    val secrets = SearchMemorySecrets()
                    val repository = WebSearchProviderRepositoryImpl(store, secrets)
                    if (readProvidersFirst) repository.providers.first()
                    else migratedPolicy(store)

                    val expected = when {
                        enabled != true -> WebSearchPolicy()
                        url.isNotBlank() -> WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
                        else -> WebSearchPolicy(WebSearchMode.NATIVE)
                    }
                    // The migrated global default is only visible to storage-level
                    // readers; product code no longer consumes it.
                    assertEquals(expected, migratedPolicy(store))
                    val providers = repository.providers.first()
                    val searxng = providers.single { it.id == "searxng" }
                    assertEquals(url.trim(), searxng.endpoint)
                    assertEquals(url.isNotBlank(), searxng.enabled)
                    assertTrue(providers.filterNot { it.id == "searxng" }.all { !it.enabled })
                    assertTrue(secrets.values.isEmpty())
                    val saved = store.data.first()
                    assertNull(saved[legacyEnabled])
                    assertNull(saved[legacyUrl])
                    assertEquals("zh-CN", saved[language])
                    assertEquals("DARK", saved[theme])
                    assertEquals(true, saved[dynamicColor])
                    val preferences = UiPreferencesStore(store)
                    assertEquals(AppLanguage.CHINESE, preferences.language.first())
                    assertEquals(AppTheme.DARK, preferences.theme.first())
                    assertTrue(preferences.dynamicColor.first())
                }
            }
        }
    }

    @Test
    fun `legacy disabled global setting still preserves configured enabled instance`() = runBlocking {
        val store = SearchMemoryPreferences(mutablePreferencesOf(legacyEnabled to false, legacyUrl to "http://localhost:8888"))
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        assertTrue(repository.providers.first().single { it.id == "searxng" }.enabled)
        assertEquals(WebSearchPolicy(), migratedPolicy(store))
    }

    @Test
    fun `migration is one time and does not restore edited endpoint`() = runBlocking {
        val store = SearchMemoryPreferences(mutablePreferencesOf(legacyEnabled to true, legacyUrl to "https://old.example"))
        assertEquals(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"), migratedPolicy(store))
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        val edited = repository.providers.first().single { it.id == "searxng" }.copy(endpoint = "https://new.example/base", enabled = false)
        repository.save(edited)

        val reopened = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        assertEquals(edited, reopened.providers.first().single { it.id == "searxng" })
        assertNull(store.data.first()[legacyEnabled])
        assertNull(store.data.first()[legacyUrl])
    }

    @Test
    fun `canonical policy present during migration is not replaced by legacy toggle`() = runBlocking {
        val store = SearchMemoryPreferences(mutablePreferencesOf(
            legacyEnabled to true,
            legacyUrl to "https://old.example",
            WebSearchPreferenceStorage.policyKey to "{\"mode\":\"off\"}",
        ))
        assertEquals(WebSearchPolicy(), migratedPolicy(store))
        assertTrue(WebSearchProviderRepositoryImpl(store, SearchMemorySecrets()).providers.first().single { it.id == "searxng" }.enabled)
    }

    @Test
    fun `appearance setters retain independent stored preferences`() = runBlocking {
        val store = SearchMemoryPreferences()
        val preferences = UiPreferencesStore(store)
        preferences.setLanguage(AppLanguage.CHINESE)
        preferences.setTheme(AppTheme.DARK)
        preferences.setDynamicColor(true)
        assertEquals(AppLanguage.CHINESE, preferences.language.first())
        assertEquals(AppTheme.DARK, preferences.theme.first())
        assertTrue(preferences.dynamicColor.first())
        preferences.setLanguage(AppLanguage.SYSTEM)
        preferences.setTheme(AppTheme.SYSTEM)
        preferences.setDynamicColor(false)
        assertNull(store.data.first()[language])
        assertNull(store.data.first()[theme])
        assertFalse(preferences.dynamicColor.first())
    }

    @Test
    fun `failed migration leaves legacy settings recoverable and retries on next observation`() = runBlocking {
        val initial = mutablePreferencesOf(legacyEnabled to true, legacyUrl to "https://old.example", language to "en")
        val store = SearchMemoryPreferences(initial)
        val failure = IOException("disk unavailable")
        store.failNextUpdate = failure
        assertSame(failure, expectFailure<IOException> { WebSearchProviderRepositoryImpl(store, SearchMemorySecrets()).providers.first() })
        assertEquals(initial.asMap(), store.data.first().asMap())
        assertEquals(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"), migratedPolicy(store))
        assertNull(store.data.first()[legacyUrl])
    }

    @Test
    fun `damaged canonical configuration is an error not a reset to defaults`() = runBlocking {
        val store = SearchMemoryPreferences()
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        repository.providers.first()
        store.edit { it[WebSearchPreferenceStorage.policyKey] = "not-json" }
        expectFailure<SerializationException> { migratedPolicy(store) }
        assertEquals("not-json", store.data.first()[WebSearchPreferenceStorage.policyKey])
        store.edit { it[WebSearchPreferenceStorage.providersKey] = "not-json" }
        val reopened = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        expectFailure<SerializationException> { reopened.providers.first() }
        assertEquals("not-json", store.data.first()[WebSearchPreferenceStorage.providersKey])
    }
}
