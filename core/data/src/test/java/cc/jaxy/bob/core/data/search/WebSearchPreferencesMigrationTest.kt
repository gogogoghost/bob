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

    @Test
    fun `fresh install is off with every built in provider disabled`() = runBlocking {
        val store = SearchMemoryPreferences()
        val preferences = UiPreferencesStore(store)
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        assertEquals(WebSearchPolicy(), preferences.webSearchSettings.first().policy)
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
                    val preferences = UiPreferencesStore(store)
                    val secrets = SearchMemorySecrets()
                    val repository = WebSearchProviderRepositoryImpl(store, secrets)
                    if (readProvidersFirst) repository.providers.first() else preferences.webSearchSettings.first()

                    val expected = when {
                        enabled != true -> WebSearchPolicy()
                        url.isNotBlank() -> WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
                        else -> WebSearchPolicy(WebSearchMode.NATIVE)
                    }
                    assertEquals(expected, preferences.webSearchSettings.first().policy)
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
        assertEquals(WebSearchPolicy(), UiPreferencesStore(store).webSearchSettings.first().policy)
    }

    @Test
    fun `canonical policy round trips without requiring or changing chosen provider`() = runBlocking {
        val store = SearchMemoryPreferences()
        val preferences = UiPreferencesStore(store)
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        for (policy in listOf(WebSearchPolicy(WebSearchMode.NATIVE), WebSearchPolicy(WebSearchMode.PROVIDER, "missing-provider"), WebSearchPolicy(WebSearchMode.PROVIDER, "tavily"), WebSearchPolicy())) {
            preferences.setWebSearchPolicy(policy)
            assertEquals(policy, UiPreferencesStore(store).webSearchSettings.first().policy)
            assertTrue(repository.providers.first().all { !it.enabled })
        }
    }

    @Test
    fun `migration is one time and does not restore edited endpoint or policy`() = runBlocking {
        val store = SearchMemoryPreferences(mutablePreferencesOf(legacyEnabled to true, legacyUrl to "https://old.example"))
        val preferences = UiPreferencesStore(store)
        assertEquals(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"), preferences.webSearchSettings.first().policy)
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        val edited = repository.providers.first().single { it.id == "searxng" }.copy(endpoint = "https://new.example/base", enabled = false)
        repository.save(edited)
        assertEquals(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"), preferences.webSearchSettings.first().policy)
        preferences.setWebSearchPolicy(WebSearchPolicy(WebSearchMode.NATIVE))

        val reopened = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        assertEquals(edited, reopened.providers.first().single { it.id == "searxng" })
        assertEquals(WebSearchPolicy(WebSearchMode.NATIVE), UiPreferencesStore(store).webSearchSettings.first().policy)
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
        assertEquals(WebSearchPolicy(), UiPreferencesStore(store).webSearchSettings.first().policy)
        assertTrue(WebSearchProviderRepositoryImpl(store, SearchMemorySecrets()).providers.first().single { it.id == "searxng" }.enabled)
    }

    @Test
    fun `appearance setters retain independent canonical search configuration`() = runBlocking {
        val store = SearchMemoryPreferences()
        val preferences = UiPreferencesStore(store)
        val policy = WebSearchPolicy(WebSearchMode.NATIVE)
        preferences.setWebSearchPolicy(policy)
        preferences.setLanguage(AppLanguage.CHINESE)
        preferences.setTheme(AppTheme.DARK)
        preferences.setDynamicColor(true)
        assertEquals(AppLanguage.CHINESE, preferences.language.first())
        assertEquals(AppTheme.DARK, preferences.theme.first())
        assertTrue(preferences.dynamicColor.first())
        assertEquals(policy, preferences.webSearchSettings.first().policy)
        preferences.setLanguage(AppLanguage.SYSTEM)
        preferences.setTheme(AppTheme.SYSTEM)
        preferences.setDynamicColor(false)
        assertNull(store.data.first()[language])
        assertNull(store.data.first()[theme])
        assertFalse(preferences.dynamicColor.first())
        assertEquals(policy, preferences.webSearchSettings.first().policy)
    }

    @Test
    fun `failed migration leaves legacy settings recoverable and retries on next observation`() = runBlocking {
        val initial = mutablePreferencesOf(legacyEnabled to true, legacyUrl to "https://old.example", language to "en")
        val store = SearchMemoryPreferences(initial)
        val failure = IOException("disk unavailable")
        store.failNextUpdate = failure
        val preferences = UiPreferencesStore(store)
        assertSame(failure, expectFailure<IOException> { preferences.webSearchSettings.first() })
        assertEquals(initial.asMap(), store.data.first().asMap())
        assertEquals(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"), preferences.webSearchSettings.first().policy)
        assertNull(store.data.first()[legacyUrl])
    }

    @Test
    fun `damaged canonical configuration is an error not a reset to defaults`() = runBlocking {
        val store = SearchMemoryPreferences()
        val preferences = UiPreferencesStore(store)
        preferences.webSearchSettings.first()
        store.edit { it[WebSearchPreferenceStorage.policyKey] = "not-json" }
        expectFailure<SerializationException> { preferences.webSearchSettings.first() }
        assertEquals("not-json", store.data.first()[WebSearchPreferenceStorage.policyKey])
        store.edit { it[WebSearchPreferenceStorage.providersKey] = "not-json" }
        val repository = WebSearchProviderRepositoryImpl(store, SearchMemorySecrets())
        expectFailure<SerializationException> { repository.providers.first() }
        assertEquals("not-json", store.data.first()[WebSearchPreferenceStorage.providersKey])
    }
}
