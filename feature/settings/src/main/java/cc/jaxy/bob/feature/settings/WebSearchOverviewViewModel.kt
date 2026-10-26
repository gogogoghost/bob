package cc.jaxy.bob.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebSearchOverviewState(
    val providers: List<WebSearchProvider> = emptyList(),
    val loading: Boolean = true,
    val pending: Boolean = false,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val configureProviderId: String? = null,
) {
    val canEdit: Boolean get() = !loading && !loadFailed && !pending
}

@HiltViewModel
class WebSearchOverviewViewModel @Inject constructor(
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
                repository.providers.collect { providers ->
                    _uiState.update {
                        it.copy(providers = providers, loading = false, loadFailed = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
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
