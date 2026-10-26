package cc.jaxy.bob.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.agent.Agent
import cc.jaxy.bob.core.data.agent.AgentRepository
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.chat.ModelRepository
import cc.jaxy.bob.core.designsystem.text.UiText
import cc.jaxy.bob.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    data class UiState(
        val agents: List<Agent> = emptyList(),
        val models: List<AIModel> = emptyList(),
        val loading: Boolean = false,
        val operatingIds: Set<String> = emptySet(),
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
            // Agents + models in parallel; models only back the create sheet.
            val agentsDeferred = async { agentRepository.listAgents() }
            val modelsDeferred = async { modelRepository.listModels() }
            when (val result = agentsDeferred.await()) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    agents = result.value,
                    error = null,
                )
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = result.error.toUiText(),
                )
            }
            when (val models = modelsDeferred.await()) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(models = models.value)
                is AnResult.Err -> Unit
            }
        }
    }

    fun deleteAgent(id: String) {
        if (id in _uiState.value.operatingIds) return
        viewModelScope.launch {
            val snapshot = _uiState.value.agents
            _uiState.value = _uiState.value.copy(
                agents = snapshot.filterNot { it.id == id },
                operatingIds = _uiState.value.operatingIds + id,
                error = null,
            )
            when (val result = agentRepository.remove(id)) {
                is AnResult.Ok -> {
                    _uiState.value = _uiState.value.copy(
                        operatingIds = _uiState.value.operatingIds - id,
                    )
                    refresh()
                }
                is AnResult.Err ->
                    _uiState.value = _uiState.value.copy(
                        agents = snapshot,
                        operatingIds = _uiState.value.operatingIds - id,
                        error = result.error.toUiText(),
                    )
            }
        }
    }

    fun duplicateAgent(id: String) {
        if (id in _uiState.value.operatingIds) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                operatingIds = _uiState.value.operatingIds + id,
                error = null,
            )
            when (val result = agentRepository.duplicate(id)) {
                is AnResult.Ok -> {
                    _uiState.value = _uiState.value.copy(
                        operatingIds = _uiState.value.operatingIds - id,
                    )
                    refresh()
                }
                is AnResult.Err ->
                    _uiState.value = _uiState.value.copy(
                        operatingIds = _uiState.value.operatingIds - id,
                        error = result.error.toUiText(),
                    )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
