package cc.jaxy.bob.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.data.preferences.WebSearchPreferences
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebSearchOverviewState(
    val policy: WebSearchPolicy = WebSearchPolicy(),
    val providers: List<WebSearchProvider> = emptyList(),
    val loading: Boolean = true,
    val pending: Boolean = false,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val configureProviderId: String? = null,
) {
    val canEdit: Boolean get() = !loading && !loadFailed && !pending
    val selectedProviderUnavailable: Boolean get() = policy.mode == WebSearchMode.PROVIDER &&
        providers.none { it.id == policy.providerId && it.enabled }
}

@HiltViewModel
class WebSearchOverviewViewModel @Inject constructor(
    private val preferences: WebSearchPreferences,
    private val repository: WebSearchProviderRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WebSearchOverviewState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        if (_uiState.value.pending) return
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                combine(preferences.webSearchSettings, repository.providers) { settings, providers ->
                    settings.policy to providers
                }.collect { (policy, providers) ->
                    _uiState.update {
                        it.copy(policy = policy, providers = providers, loading = false, loadFailed = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun selectPolicy(policy: WebSearchPolicy) {
        val state = _uiState.value
        if (!state.canEdit || policy == state.policy) return
        if (policy.mode == WebSearchMode.PROVIDER &&
            state.providers.none { it.id == policy.providerId && it.enabled }) return
        _uiState.update { it.copy(pending = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                preferences.setWebSearchPolicy(policy)
                _uiState.update { it.copy(policy = policy, pending = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(pending = false, saveFailed = true) }
            }
        }
    }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        val state = _uiState.value
        val provider = state.providers.firstOrNull { it.id == id } ?: return
        if (!state.canEdit || provider.enabled == enabled) return
        _uiState.update { it.copy(pending = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                if (enabled && (provider.endpoint.isBlank() ||
                        (provider.requiresApiKey && repository.apiKey(id).isNullOrBlank()))) {
                    _uiState.update { it.copy(pending = false, configureProviderId = id) }
                    return@launch
                }
                repository.save(provider.copy(enabled = enabled))
                _uiState.update {
                    it.copy(pending = false, providers = it.providers.map { current ->
                        if (current.id == id) current.copy(enabled = enabled) else current
                    })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Never show repository exception messages: they may contain a credential or URL.
                _uiState.update { it.copy(pending = false, saveFailed = true) }
            }
        }
    }

    fun consumeConfigurationRequest() {
        _uiState.update { it.copy(configureProviderId = null) }
    }
}
