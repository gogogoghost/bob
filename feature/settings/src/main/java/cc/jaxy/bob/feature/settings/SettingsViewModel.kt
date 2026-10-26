package cc.jaxy.bob.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.common.preferences.AppLanguage
import cc.jaxy.bob.core.common.preferences.AppTheme
import cc.jaxy.bob.core.data.preferences.UiPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val uiPreferencesStore: UiPreferencesStore,
    searchRepository: cc.jaxy.bob.core.data.search.WebSearchProviderRepository,
    providerRepository: cc.jaxy.bob.core.data.provider.ProviderRepository,
) : ViewModel() {
    val language: StateFlow<AppLanguage> = uiPreferencesStore.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.SYSTEM)

    val theme: StateFlow<AppTheme> = uiPreferencesStore.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM)

    val dynamicColor: StateFlow<Boolean> = uiPreferencesStore.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { uiPreferencesStore.setLanguage(language) }
    }

    val searchSummary: StateFlow<String?> = searchRepository.providers
        .map { providers -> providers.count { it.enabled }.toString() as String? }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val providerSummary: StateFlow<String?> = kotlinx.coroutines.flow.flow {
        emit(null)
        when (val r = providerRepository.listProviders()) {
            is cc.jaxy.bob.core.common.result.AnResult.Ok ->
                emit(r.value.count { it.enabled }.toString())
            else -> emit(null)
        }
    }.catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { uiPreferencesStore.setTheme(theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { uiPreferencesStore.setDynamicColor(enabled) }
    }
}
