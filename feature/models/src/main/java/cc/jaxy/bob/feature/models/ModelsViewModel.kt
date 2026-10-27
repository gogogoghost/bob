package cc.jaxy.bob.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.chat.ModelRepository
import cc.jaxy.bob.core.data.chat.ReasoningConfig
import cc.jaxy.bob.core.designsystem.text.UiText
import cc.jaxy.bob.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ModelsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    val selectedModelId: String? = savedStateHandle.get<String>("selectedModelId")?.takeIf { it.isNotBlank() }
    val selectedProviderId: String? = savedStateHandle.get<String>("selectedProviderId")?.takeIf { it.isNotBlank() }
    private val filterProviderId: String? = savedStateHandle.get<String>("filterProviderId")?.takeIf { it.isNotBlank() }
    /** Agent scope for reasoning settings; null edits the shared fallback. */
    val agentId: String? = savedStateHandle.get<String>("agentId")?.takeIf { it.isNotBlank() }

    data class UiState(
        val models: List<AIModel> = emptyList(),
        val loading: Boolean = false,
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val result = if (filterProviderId != null) modelRepository.listProviderModels(filterProviderId) else modelRepository.listModels()) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    models = result.value,
                    error = null,
                )
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // --- Reasoning config cache (per model) ---

    private val _reasoning = MutableStateFlow<Map<String, ReasoningConfig>>(emptyMap())
    val reasoning: StateFlow<Map<String, ReasoningConfig>> = _reasoning.asStateFlow()

    private val _reasoningUi = MutableStateFlow<Map<String, ReasoningUiState>>(emptyMap())
    val reasoningUi: StateFlow<Map<String, ReasoningUiState>> = _reasoningUi.asStateFlow()


    fun loadReasoning(model: AIModel) {
        val key = reasoningKeyOf(model, agentId)
        if (_reasoning.value.containsKey(key) || _reasoningUi.value[key]?.loading == true) return
        viewModelScope.launch {
            _reasoningUi.value = _reasoningUi.value + (key to ReasoningUiState(loading = true))
            when (val result = modelRepository.getReasoningConfig(agentId.orEmpty(), model.id, model.providerId.orEmpty())) {
                is AnResult.Ok -> {
                    _reasoning.value = _reasoning.value + (key to result.value)
                    _reasoningUi.value = _reasoningUi.value + (key to ReasoningUiState())
                }
                is AnResult.Err -> _reasoningUi.value = _reasoningUi.value + (key to ReasoningUiState(error = result.error.toUiText()))
            }
        }
    }

    /**
     * Save one reasoning key. Merges with cached [ReasoningConfig.raw] so
     * vendor-specific keys are preserved. A null [value] clears the key.
     */
    fun saveReasoning(model: AIModel, key: String, value: String?) {
        val cacheKey = reasoningKeyOf(model, agentId)
        viewModelScope.launch {
            val current = _reasoning.value[cacheKey] ?: ReasoningConfig()
            _reasoningUi.value = _reasoningUi.value + (cacheKey to ReasoningUiState(saving = true))
            val merged = current.raw.toMutableMap()
            if (value.isNullOrBlank()) merged.remove(key) else merged[key] = value
            when (val result = modelRepository.updateReasoningConfig(agentId.orEmpty(), model.id, model.providerId.orEmpty(), merged)) {
                is AnResult.Ok -> {
                    _reasoning.value = _reasoning.value + (
                        cacheKey to current.copy(
                            effort = if (key == ReasoningKeys.EFFORT) value else current.effort,
                            reasoningEffort = if (key == ReasoningKeys.REASONING_EFFORT) value else current.reasoningEffort,
                            thinkingLevel = if (key == ReasoningKeys.THINKING_LEVEL) value else current.thinkingLevel,
                            raw = merged.toMap(),
                        )
                        )
                    _reasoningUi.value = _reasoningUi.value + (cacheKey to ReasoningUiState(saved = true))
                }
                is AnResult.Err -> _reasoningUi.value = _reasoningUi.value + (cacheKey to ReasoningUiState(error = result.error.toUiText()))
            }
        }
    }

    fun consumeReasoningSaved(model: AIModel) {
        val key = reasoningKeyOf(model, agentId)
        _reasoningUi.value[key]?.let { _reasoningUi.value = _reasoningUi.value + (key to it.copy(saved = false)) }
    }

    fun dismissReasoningError(model: AIModel) {
        val key = reasoningKeyOf(model, agentId)
        _reasoningUi.value[key]?.let { _reasoningUi.value = _reasoningUi.value + (key to it.copy(error = null)) }
    }
}

/** Cache key for per-model reasoning config: provider + model id. */
fun reasoningKeyOf(model: AIModel, agentId: String? = null): String =
    agentId.orEmpty() + "\n" + model.providerId.orEmpty() + "\n" + model.id

/** Reasoning value keys merged with [ReasoningConfig.raw]. */
object ReasoningKeys {
    const val EFFORT = "effort"
    const val REASONING_EFFORT = "reasoningEffort"
    const val THINKING_LEVEL = "thinkingLevel"
}

data class ReasoningUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: UiText? = null,
    val saved: Boolean = false,
)
