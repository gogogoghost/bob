package cc.jaxy.bob.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.data.search.WebSearchCatalog
import cc.jaxy.bob.core.data.search.WebSearchProtocol
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchProviderValidation { NAME, ENDPOINT, KEY }

enum class SearchTestResult { TESTING, OK, FAILED }

data class WebSearchProviderState(
    val original: WebSearchProvider? = null,
    val draft: WebSearchProvider? = null,
    val testResult: SearchTestResult? = null,
    val testError: String? = null,
    // Memory-only: no SavedStateHandle, rememberSaveable, persistence, or logging of keys.
    val apiKey: String = "",
    val keyEdited: Boolean = false,
    /** Vault really holds a key (loaded once per bind); resaves must trust it, not the cleared field. */
    val hasStoredKey: Boolean = false,
    val loading: Boolean = true,
    val pending: Boolean = false,
    val loadFailed: Boolean = false,
    val missing: Boolean = false,
    val saveFailed: Boolean = false,
    val validation: SearchProviderValidation? = null,
    val confirmDelete: Boolean = false,
    val finished: Boolean = false,
) {
    val canEdit: Boolean get() = draft != null && !loading && !loadFailed && !pending && !finished
    val dirty: Boolean get() = draft != original || keyEdited

    override fun toString(): String = "WebSearchProviderState(loading=$loading, pending=$pending, key=<redacted>)"
}

@HiltViewModel
class WebSearchProviderViewModel @Inject constructor(
    private val repository: WebSearchProviderRepository,
    private val searchClient: cc.jaxy.bob.core.data.search.WebSearchClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WebSearchProviderState())
    val uiState = _uiState.asStateFlow()
    private var bound = false
    private var providerId: String? = null
    private var loadJob: Job? = null

    fun bind(id: String?) {
        if (bound && providerId == id) return
        if (_uiState.value.pending) return
        bound = true
        providerId = id
        _uiState.value = WebSearchProviderState()
        load()
    }

    fun load() {
        if (!bound || _uiState.value.pending) return
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true, loadFailed = false, missing = false) }
        val id = providerId
        loadJob = viewModelScope.launch {
            try {
                val provider = if (id == null) {
                    WebSearchCatalog.providers.first().copy(
                        id = "custom-${UUID.randomUUID()}", name = "", builtin = false, enabled = false,
                    )
                } else {
                    repository.providers.first().firstOrNull { it.id == id }
                }
                if (provider == null) {
                    _uiState.update { it.copy(loading = false, missing = true) }
                    return@launch
                }
                val key = if (id == null) "" else repository.apiKey(id).orEmpty()
                _uiState.update {
                    if (it.draft != null) {
                        // A retry must not erase an existing unsaved form.
                        it.copy(loading = false, loadFailed = false)
                    } else {
                        WebSearchProviderState(original = provider, draft = provider, apiKey = key,
                            hasStoredKey = key.isNotBlank(), loading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private fun edit(transform: (WebSearchProvider) -> WebSearchProvider) {
        val state = _uiState.value
        if (!state.canEdit) return
        _uiState.update { it.copy(draft = it.draft?.let(transform), validation = null, saveFailed = false, testResult = null, testError = null) }
    }

    fun setName(value: String) = edit { if (it.builtin) it else it.copy(name = value) }
    fun setEndpoint(value: String) = edit { it.copy(endpoint = value) }
    fun setProtocol(protocol: WebSearchProtocol) = edit { draft ->
        if (draft.builtin || draft.protocol == protocol) draft else {
            val previous = WebSearchCatalog.providers.firstOrNull { it.protocol == draft.protocol }
            val next = WebSearchCatalog.providers.firstOrNull { it.protocol == protocol }
                ?: return@edit draft
            val fallbackEndpoint = previous?.endpoint.orEmpty()
            draft.copy(
                protocol = protocol,
                endpoint = if (draft.endpoint.isBlank() || draft.endpoint == fallbackEndpoint) next.endpoint else draft.endpoint,
                docsUrl = next.docsUrl,
            )
        }
    }

    fun setApiKey(value: String) {
        if (!_uiState.value.canEdit) return
        _uiState.update { it.copy(apiKey = value, keyEdited = true, validation = null, saveFailed = false, testResult = null, testError = null) }
    }

    fun save() {
        val state = _uiState.value
        val draft = state.draft ?: return
        if (!state.canEdit) return
        // The detail page has no enable switch (the list owns it): never flip
        // enabled from here. New customs stay disabled; existing keep theirs.
        // The key rule below still validates against the *stored* enabled state.
        val storedEnabled = state.original?.enabled == true
        val provider = draft.copy(name = draft.name.trim(), endpoint = draft.endpoint.trim(), enabled = storedEnabled)
        val effectiveKey = if (state.keyEdited) state.apiKey else state.apiKey.takeIf(String::isNotBlank)
            // Trust the vault when the field was cleared by a previous save:
            // renaming or re-pointing an enabled service must not force re-typing the key.
            val hasKey = state.keyEdited && !state.apiKey.isBlank() ||
                !state.keyEdited && (!state.apiKey.isBlank() || state.hasStoredKey)
            val validation = when {
            provider.name.isBlank() -> SearchProviderValidation.NAME
            !validEndpoint(provider.copy(enabled = storedEnabled)) -> SearchProviderValidation.ENDPOINT
            storedEnabled && provider.requiresApiKey && !hasKey -> SearchProviderValidation.KEY
            else -> null
        }
        if (validation != null) {
            _uiState.update { it.copy(validation = validation) }
            return
        }
        _uiState.update { it.copy(pending = true, saveFailed = false, validation = null) }
        viewModelScope.launch {
            try {
                repository.save(provider, if (state.keyEdited) state.apiKey else null)
                val keptKey = if (state.keyEdited) state.apiKey.isNotBlank() else state.hasStoredKey || state.apiKey.isNotBlank()
                _uiState.update {
                    it.copy(original = provider, draft = provider, apiKey = "", keyEdited = false,
                        hasStoredKey = keptKey, pending = false, finished = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(pending = false, saveFailed = true) }
            }
        }
    }

    /** Probe the draft endpoint/key with one real query; never persists or logs secrets. */
    fun testSearch() {
        testSearch(searchClient)
    }

    internal fun testSearch(client: cc.jaxy.bob.core.data.search.WebSearchClient) {
        val state = _uiState.value
        val draft = state.draft ?: return
        if (!state.canEdit || state.testResult == SearchTestResult.TESTING) return
        if (!validEndpoint(draft.copy(name = draft.name.trim(), endpoint = draft.endpoint.trim()))) {
            _uiState.update { it.copy(testResult = SearchTestResult.FAILED, testError = null) }
            return
        }
        _uiState.update { it.copy(testResult = SearchTestResult.TESTING, testError = null, saveFailed = false) }
        viewModelScope.launch {
            try {
                val key = if (state.keyEdited) state.apiKey
                    else state.apiKey.takeIf(String::isNotBlank) ?: repository.apiKey(draft.id)
                // Test the exact draft the user sees (enabled forced on for the probe only).
                client.search(draft.copy(enabled = true), key, "test")
                _uiState.update { it.copy(testResult = SearchTestResult.OK, testError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(testResult = SearchTestResult.FAILED, testError = null) }
            }
        }
    }

    fun requestDelete() {
        val state = _uiState.value
        if (state.canEdit && providerId != null && state.original?.builtin == false) {
            _uiState.update { it.copy(confirmDelete = true) }
        }
    }

    fun dismissDelete() {
        if (!_uiState.value.pending) _uiState.update { it.copy(confirmDelete = false) }
    }

    fun confirmDelete() {
        val state = _uiState.value
        val id = providerId ?: return
        if (!state.canEdit || !state.confirmDelete || state.original?.builtin != false) return
        _uiState.update { it.copy(pending = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                repository.delete(id)
                _uiState.update { it.copy(pending = false, confirmDelete = false, apiKey = "", finished = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(pending = false, confirmDelete = false, saveFailed = true) }
            }
        }
    }

    private fun validEndpoint(provider: WebSearchProvider): Boolean {
        if (provider.endpoint.isEmpty()) return !provider.enabled && provider.protocol == WebSearchProtocol.SEARXNG
        return try {
            val uri = URI(provider.endpoint)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
        } catch (_: Exception) {
            false
        }
    }
}
