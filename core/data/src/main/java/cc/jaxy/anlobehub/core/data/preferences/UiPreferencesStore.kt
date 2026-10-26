package cc.jaxy.anlobehub.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.jaxy.anlobehub.core.common.preferences.AppLanguage
import cc.jaxy.anlobehub.core.common.preferences.AppTheme
import cc.jaxy.anlobehub.core.data.search.WebSearchCatalog
import cc.jaxy.anlobehub.core.data.search.WebSearchMode
import cc.jaxy.anlobehub.core.data.search.WebSearchPolicy
import cc.jaxy.anlobehub.core.data.search.WebSearchProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val Context.uiPrefsDataStore by preferencesDataStore(name = "ui_prefs")

data class WebSearchSettings(val policy: WebSearchPolicy = WebSearchPolicy())

interface WebSearchPreferences {
    val webSearchSettings: Flow<WebSearchSettings>
    suspend fun setWebSearchPolicy(policy: WebSearchPolicy)
}

/**
 * Persists appearance and opt-in chat search preferences. Search is disabled by default.
 */
@Singleton
class UiPreferencesStore internal constructor(
    private val store: DataStore<Preferences>,
) : WebSearchPreferences {
    @Inject constructor(@ApplicationContext context: Context) : this(context.uiPrefsDataStore)

    private val languageKey = stringPreferencesKey("app_language")
    private val themeKey = stringPreferencesKey("app_theme")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")

    companion object {
        private val staticLanguageKey = stringPreferencesKey("app_language")

        /**
         * Blocking read for [android.app.Activity.attachBaseContext], which runs
         * before Hilt injection. Returns null when following the system.
         */
        fun readLanguageTagBlocking(context: Context): String? {
            return try {
                runBlocking {
                    context.uiPrefsDataStore.data.map { it[staticLanguageKey] }.first()
                }.takeUnless { it.isNullOrBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    val language: Flow<AppLanguage> =
        store.data.map { AppLanguage.fromTag(it[languageKey]) }

    val theme: Flow<AppTheme> =
        store.data.map { AppTheme.fromName(it[themeKey]) }

    val dynamicColor: Flow<Boolean> =
        store.data.map { it[dynamicColorKey] ?: false }

    override val webSearchSettings: Flow<WebSearchSettings> =
        store.data.onStart { WebSearchPreferenceStorage.migrate(store) }.map {
            WebSearchSettings(WebSearchPreferenceStorage.readPolicy(it))
        }.distinctUntilChanged()

    override suspend fun setWebSearchPolicy(policy: WebSearchPolicy) {
        WebSearchPreferenceStorage.migrate(store)
        store.edit { it[WebSearchPreferenceStorage.policyKey] = WebSearchPreferenceStorage.json.encodeToString(policy) }
    }

    suspend fun setLanguage(language: AppLanguage) {
        store.edit {
            if (language == AppLanguage.SYSTEM) {
                it.remove(languageKey)
            } else {
                it[languageKey] = language.tag.orEmpty()
            }
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[dynamicColorKey] = enabled }
    }

    suspend fun setTheme(theme: AppTheme) {
        store.edit {
            if (theme == AppTheme.SYSTEM) {
                it.remove(themeKey)
            } else {
                it[themeKey] = theme.name
            }
        }
    }
}

/** Both entry points migrate together, so observing providers first cannot lose the legacy URL. */
internal object WebSearchPreferenceStorage {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val providersKey = stringPreferencesKey("web_search_providers")
    val policyKey = stringPreferencesKey("web_search_policy")
    private val migratedKey = booleanPreferencesKey("web_search_migrated_v1")
    private val legacyEnabledKey = booleanPreferencesKey("web_search_enabled")
    private val legacyUrlKey = stringPreferencesKey("searxng_url")

    fun readPolicy(preferences: Preferences): WebSearchPolicy =
        preferences[policyKey]?.let { json.decodeFromString<WebSearchPolicy>(it) } ?: WebSearchPolicy()

    fun readProviders(preferences: Preferences): List<WebSearchProvider> {
        val saved = preferences[providersKey]?.let { json.decodeFromString<List<WebSearchProvider>>(it) }.orEmpty()
        // Self-heal duplicate IDs (first wins) instead of bricking every
        // reader: a duplicated write must stay recoverable from the UI.
        val seen = LinkedHashSet<String>()
        val deduped = saved.filter { seen.add(it.id) }
        val byId = deduped.associateBy { it.id }
        val catalogIds = WebSearchCatalog.providers.map { it.id }.toSet()
        return WebSearchCatalog.providers.map { builtin ->
            byId[builtin.id]?.let { builtin.copy(endpoint = it.endpoint, enabled = it.enabled) } ?: builtin
        } + saved.filter { it.id !in catalogIds }
    }

    suspend fun migrate(store: DataStore<Preferences>) {
        if (store.data.first()[migratedKey] == true) return
        store.edit { preferences ->
            if (preferences[migratedKey] == true) return@edit
            val legacyUrl = preferences[legacyUrlKey].orEmpty().trim()
            val providers = readProviders(preferences).map {
                if (it.id == "searxng" && legacyUrl.isNotEmpty() && it.endpoint.isBlank()) {
                    it.copy(endpoint = legacyUrl, enabled = true)
                } else it
            }
            if (preferences[policyKey] == null) {
                val policy = when {
                    preferences[legacyEnabledKey] != true -> WebSearchPolicy()
                    legacyUrl.isNotEmpty() -> WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
                    else -> WebSearchPolicy(WebSearchMode.NATIVE)
                }
                preferences[policyKey] = json.encodeToString(policy)
            }
            preferences[providersKey] = json.encodeToString(providers)
            preferences.remove(legacyEnabledKey)
            preferences.remove(legacyUrlKey)
            preferences[migratedKey] = true
        }
    }
}
