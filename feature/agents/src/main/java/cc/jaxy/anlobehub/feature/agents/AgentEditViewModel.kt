package cc.jaxy.anlobehub.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.agent.AgentRepository
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.search.WebSearchPolicy
import cc.jaxy.anlobehub.core.data.search.WebSearchProvider
import cc.jaxy.anlobehub.core.data.search.WebSearchProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray

/**
 * Create/edit agent page state. `agentId == null` creates; otherwise edits.
 * Local agent configuration fields: avatar/title/description/model/provider/
 * systemRole/openingMessage/openingQuestions/webSearch.
 */
@HiltViewModel
class AgentEditViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val searchProviders: WebSearchProviderRepository,
) : ViewModel() {

    data class UiState(
        val agentId: String? = null,
        val avatar: String = "",
        val title: String = "",
        val description: String = "",
        val model: String = "",
        val modelDisplayName: String = "",
        val provider: String = "",
        val systemRole: String = "",
        val openingMessage: String = "",
        val openingQuestions: List<String> = emptyList(),
        val webSearch: WebSearchPolicy? = null,
        val searchProviders: List<WebSearchProvider> = emptyList(),
        val providersError: Boolean = false,
        val titleError: Boolean = false,
        val loading: Boolean = false,
        val loadFailed: Boolean = false,
        val saving: Boolean = false,
        val uploading: Boolean = false,
        val deleting: Boolean = false,
        val saved: Boolean = false,
        val createdId: String? = null,
        val deleted: Boolean = false,
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var providers: List<WebSearchProvider> = emptyList()
    private var providersFailed = false

    init {
        viewModelScope.launch {
            searchProviders.providers.catch { error ->
                if (error is CancellationException) throw error
                providersFailed = true
                edit { copy(providersError = true) }
            }.collect { values ->
                providers = values
                providersFailed = false
                edit { copy(searchProviders = values, providersError = false) }
            }
        }
    }

    private var boundId: String? = null
    private var boundDone = false
    private var loadJob: kotlinx.coroutines.Job? = null

    fun bind(agentId: String?) {
        if (boundDone && boundId == agentId) return
        loadJob?.cancel()
        boundDone = true
        boundId = agentId
        if (agentId.isNullOrBlank()) {
            _uiState.value = UiState(searchProviders = providers, providersError = providersFailed)
            return
        }
        _uiState.value = UiState(agentId = agentId, searchProviders = providers, providersError = providersFailed, loading = true)
        load()
    }

    fun load() {
        val id = _uiState.value.agentId ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val result = agentRepository.getConfig(id)) {
                is AnResult.Ok -> {
                    val c = result.value
                    _uiState.value = _uiState.value.copy(
                        avatar = c.avatar.orEmpty(),
                        title = c.title.orEmpty(),
                        description = c.description.orEmpty(),
                        model = c.model.orEmpty(),
                        provider = c.provider.orEmpty(),
                        systemRole = c.systemRole.orEmpty(),
                        openingMessage = c.openingMessage.orEmpty(),
                        openingQuestions = c.openingQuestions,
                        webSearch = c.webSearch,
                        loading = false,
                        loadFailed = false,
                        error = null,
                    )
                }
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    loadFailed = true,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun onAvatarChange(v: String) = edit { copy(avatar = v, saved = false) }
    fun onTitleChange(v: String) = edit { copy(title = v, titleError = false, saved = false) }
    fun onDescriptionChange(v: String) = edit { copy(description = v, saved = false) }
    fun onSystemRoleChange(v: String) = edit { copy(systemRole = v, saved = false) }
    fun onOpeningMessageChange(v: String) = edit { copy(openingMessage = v, saved = false) }
    fun onWebSearchChange(v: WebSearchPolicy?) {
        if (_uiState.value.loading || _uiState.value.saving) return
        edit { copy(webSearch = v, saved = false) }
    }

    fun onQuestionChange(index: Int, v: String) = edit {
        copy(
            openingQuestions = openingQuestions.toMutableList()
                .also { if (index in it.indices) it[index] = v },
            saved = false,
        )
    }

    fun addQuestion() = edit {
        copy(openingQuestions = openingQuestions + "", saved = false)
    }

    fun removeQuestion(index: Int) = edit {
        copy(
            openingQuestions = openingQuestions.filterIndexed { i, _ -> i != index },
            saved = false,
        )
    }

    fun setModel(model: AIModel?) {
        _uiState.value = _uiState.value.copy(
            model = model?.id.orEmpty(),
            modelDisplayName = model?.displayName?.takeIf { it.isNotBlank() }.orEmpty(),
            provider = model?.providerId.orEmpty(),
            saved = false,
        )
    }

    fun clearModel() {
        _uiState.value = _uiState.value.copy(
            model = "", modelDisplayName = "", provider = "", saved = false,
        )
    }

    fun dismissError() {
        if (_uiState.value.loadFailed) return
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun consumeCreated() {
        _uiState.value = _uiState.value.copy(createdId = null)
    }

    private inline fun edit(block: UiState.() -> UiState) {
        _uiState.value = _uiState.value.block()
    }

    fun save() {
        val s = _uiState.value
        if (s.loading || s.loadFailed || s.saving || s.title.isBlank()) {
            if (s.title.isBlank()) _uiState.value = s.copy(titleError = true)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null, saved = false)
            val cur = _uiState.value
            val questions = cur.openingQuestions.filter { it.isNotBlank() }
            if (cur.agentId == null) {
                when (
                    val result = agentRepository.create(
                        title = cur.title.trim(),
                        systemRole = cur.systemRole.trim().takeIf { it.isNotBlank() },
                        model = cur.model.trim().takeIf { it.isNotBlank() },
                        provider = cur.provider.trim().takeIf { it.isNotBlank() },
                        description = cur.description.trim().takeIf { it.isNotBlank() },
                        avatar = cur.avatar.trim().takeIf { it.isNotBlank() },
                        openingMessage = cur.openingMessage.trim().takeIf { it.isNotBlank() },
                        openingQuestions = questions,
                        webSearch = cur.webSearch,
                    )
                ) {
                    is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                        saving = false,
                        createdId = result.value,
                    )
                    is AnResult.Err -> _uiState.value = _uiState.value.copy(
                        saving = false,
                        error = result.error.toUiText(),
                    )
                }
                return@launch
            }
            val fields = buildMap {
                put("title", JsonPrimitive(cur.title.trim()))
                put("description", JsonPrimitive(cur.description.trim()))
                put("avatar", JsonPrimitive(cur.avatar.trim()))
                put("systemRole", JsonPrimitive(cur.systemRole))
                put("openingMessage", JsonPrimitive(cur.openingMessage))
                put(
                    "openingQuestions",
                    buildJsonArray { questions.forEach { add(JsonPrimitive(it)) } },
                )
                put("model", JsonPrimitive(cur.model.trim()))
                put("provider", JsonPrimitive(cur.provider.trim()))
                put("webSearch", cur.webSearch?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
            }
            when (val result = agentRepository.updateConfig(cur.agentId, fields)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(saving = false, saved = true)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun delete() {
        val id = _uiState.value.agentId ?: return
        if (_uiState.value.deleting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deleting = true, error = null)
            when (val result = agentRepository.remove(id)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(deleting = false, deleted = true)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    deleting = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    /** Saves a prepared avatar image to local storage. */
    fun uploadAvatar(bytes: ByteArray, mime: String) {
        if (_uiState.value.uploading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(uploading = true, error = null)
            when (
                val result = fileRepository.uploadFile(
                    name = "avatar.webp",
                    mime = mime,
                    bytes = bytes,
                )
            ) {
                is AnResult.Ok -> {
                    val url = result.value.url
                    _uiState.value = if (url.isNullOrBlank()) {
                        _uiState.value.copy(
                            uploading = false,
                            error = UiText.Res(R.string.agents_avatar_upload_failed),
                        )
                    } else {
                        _uiState.value.copy(uploading = false, avatar = url, saved = false)
                    }
                }
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    uploading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun duplicate(onDone: (String) -> Unit) {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = agentRepository.duplicate(id)) {
                is AnResult.Ok -> onDone(result.value)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    error = result.error.toUiText(),
                )
            }
        }
    }
}
