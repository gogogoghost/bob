package cc.jaxy.bob.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.chat.ChatTopic
import cc.jaxy.bob.core.data.chat.TopicRepository
import cc.jaxy.bob.core.designsystem.text.UiText
import cc.jaxy.bob.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TopicHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val topicRepository: TopicRepository,
) : ViewModel() {

    val agentId: String = savedStateHandle.get<String>("agentId").orEmpty()

    data class UiState(
        val topics: List<ChatTopic> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val endReached: Boolean = false,
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // The local repository uses zero-based paging.
    private var page = 0

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = UiState(loading = true)
            page = 0
            loadPage(0, initial = true)
        }
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore || s.endReached) return
        viewModelScope.launch {
            _uiState.value = s.copy(loadingMore = true, error = null)
            loadPage(page + 1, initial = false)
        }
    }

    fun deleteTopic(id: String) {
        viewModelScope.launch {
            val kept = _uiState.value.topics
            _uiState.value = _uiState.value.copy(topics = kept.filterNot { it.id == id })
            when (val result = topicRepository.deleteTopic(id)) {
                is AnResult.Ok -> Unit
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    topics = kept,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    private suspend fun loadPage(page: Int, initial: Boolean) {
        when (
            val result = topicRepository.pageTopics(
                page = page,
                pageSize = PAGE_SIZE,
                agentId = agentId.takeIf { it.isNotBlank() },
            )
        ) {
            is AnResult.Ok -> {
                val items = result.value.items
                val total = result.value.total
                val merged = if (initial) items else _uiState.value.topics + items
                this.page = page
                _uiState.value = _uiState.value.copy(
                    topics = merged,
                    loading = false,
                    loadingMore = false,
                    endReached = items.size < PAGE_SIZE ||
                        (total != null && merged.size >= total),
                    error = null,
                )
            }
            is AnResult.Err -> _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                error = result.error.toUiText(),
            )
        }
    }

    private companion object { const val PAGE_SIZE = 50 }
}
