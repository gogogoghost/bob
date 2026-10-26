package cc.jaxy.bob.core.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import cc.jaxy.bob.core.data.preferences.WebSearchPreferenceStorage
import cc.jaxy.bob.core.data.preferences.uiPrefsDataStore
import cc.jaxy.bob.core.data.provider.ProviderSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class WebSearchProviderRepositoryImpl internal constructor(
    private val store: DataStore<Preferences>,
    private val secrets: ProviderSecretStore,
) : WebSearchProviderRepository {
    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: ProviderSecretStore,
    ) : this(context.uiPrefsDataStore, secrets)

    private val mutex = Mutex()

    override val providers: Flow<List<WebSearchProvider>> = store.data
        .onStart { WebSearchPreferenceStorage.migrate(store) }
        .map(WebSearchPreferenceStorage::readProviders)
        .distinctUntilChanged()

    override suspend fun save(provider: WebSearchProvider, apiKey: String?) = mutex.withLock {
        WebSearchPreferenceStorage.migrate(store)
        validateId(provider.id)
        val builtin = WebSearchCatalog.providers.find { it.id == provider.id }
        require(provider.builtin == (builtin != null)) { "Search provider type cannot be changed." }
        require(builtin == null || provider.protocol == builtin.protocol) { "Built-in search protocols cannot be changed." }
        require(provider.name.isNotBlank()) { "Enter a search provider name." }
        val normalized = (builtin ?: provider).copy(
            name = (builtin?.name ?: provider.name).trim(),
            endpoint = provider.endpoint.trim(),
            enabled = provider.enabled,
        )
        validateEndpoint(normalized)
        val current = WebSearchPreferenceStorage.readProviders(store.data.first())
        val previousSecrets = secrets.read(secretId(provider.id))
        val nextSecrets = when (apiKey) {
            null -> previousSecrets
            else -> apiKey.trim().takeIf(String::isNotEmpty)?.let { mapOf(API_KEY to it) } ?: emptyMap()
        }
        val key = nextSecrets[API_KEY]
        require(key == null || key.none { it.isISOControl() }) { "The API key contains invalid characters." }
        require(!normalized.enabled || !normalized.requiresApiKey || !key.isNullOrBlank()) {
            "Enter an API key before enabling this search provider."
        }
        val next = if (current.any { it.id == provider.id }) {
            current.map { if (it.id == provider.id) normalized else it }
        } else {
            current + normalized
        }
        commit(provider.id, previousSecrets, nextSecrets, next)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        validateId(id)
        require(WebSearchCatalog.providers.none { it.id == id }) { "Built-in search providers cannot be deleted." }
        WebSearchPreferenceStorage.migrate(store)
        val current = WebSearchPreferenceStorage.readProviders(store.data.first())
        require(current.any { it.id == id }) { "Search provider not found." }
        commit(id, secrets.read(secretId(id)), emptyMap(), current.filterNot { it.id == id })
    }

    override suspend fun apiKey(id: String): String? = mutex.withLock {
        validateId(id)
        WebSearchPreferenceStorage.migrate(store)
        if (WebSearchPreferenceStorage.readProviders(store.data.first()).none { it.id == id }) return@withLock null
        secrets.read(secretId(id))[API_KEY]
    }

    /** Finish or roll back both writes even when the caller cancels between them. */
    private suspend fun commit(
        id: String,
        previousSecrets: Map<String, String>,
        nextSecrets: Map<String, String>,
        providers: List<WebSearchProvider>,
    ) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val keyChanged = previousSecrets != nextSecrets
            try {
                if (keyChanged) secrets.write(secretId(id), nextSecrets)
                store.edit {
                    it[WebSearchPreferenceStorage.providersKey] = WebSearchPreferenceStorage.json.encodeToString(providers)
                }
            } catch (failure: Exception) {
                if (keyChanged) {
                    try {
                        secrets.write(secretId(id), previousSecrets)
                    } catch (rollback: Exception) {
                        failure.addSuppressed(rollback)
                    }
                }
                throw failure
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun validateEndpoint(provider: WebSearchProvider) {
        if (provider.endpoint.isEmpty()) {
            require(!provider.enabled && provider.protocol == WebSearchProtocol.SEARXNG) { "Enter a search endpoint URL." }
            return
        }
        val url = provider.endpoint.toHttpUrlOrNull()
        require(
            provider.endpoint.none { it.isISOControl() } && url != null &&
                url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null,
        ) { "Use an HTTP(S) search endpoint without credentials, a query, or a fragment." }
    }

    private fun validateId(id: String) {
        require(WebSearchCatalog.providers.any { it.id == id } || CUSTOM_ID.matches(id)) { "Invalid search provider ID." }
    }

    private fun secretId(id: String) = "search.$id"

    private companion object {
        const val API_KEY = "apiKey"
        val CUSTOM_ID = Regex("custom-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
