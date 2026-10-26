package cc.jaxy.bob.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.chat.ModelRepository
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.EmptyBox
import cc.jaxy.bob.core.designsystem.component.ErrorBox
import cc.jaxy.bob.core.designsystem.component.ModelIcon
import cc.jaxy.bob.core.designsystem.component.RefreshBox
import cc.jaxy.bob.core.designsystem.component.SkeletonList
import cc.jaxy.bob.core.designsystem.component.SearchField
import cc.jaxy.bob.core.designsystem.text.UiText
import cc.jaxy.bob.core.designsystem.text.resolve
import cc.jaxy.bob.core.designsystem.text.toUiText
import cc.jaxy.bob.core.designsystem.theme.BobTheme
import cc.jaxy.bob.core.designsystem.theme.spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
internal class ProviderModelsViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val models: List<AIModel> = emptyList(),
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _errorEvent = MutableStateFlow<UiText?>(null)
    val errorEvent: StateFlow<UiText?> = _errorEvent.asStateFlow()

    private var providerId: String = ""

    fun bind(providerId: String) {
        if (this.providerId == providerId) return
        this.providerId = providerId
        load()
    }

    fun load() {
        viewModelScope.launch {
            val id = providerId
            if (id.isBlank()) return@launch
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val result = modelRepository.listProviderModels(id)) {
                is AnResult.Ok -> _uiState.value = UiState(loading = false, models = result.value)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun consumeToggleError() {
        _errorEvent.value = null
    }

    fun toggle(model: AIModel, enabled: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.models
            val targetProviderId = model.providerId ?: providerId
            _uiState.value = _uiState.value.copy(
                models = current.map { if (it.id == model.id) it.copy(enabled = enabled) else it },
            )
            when (val result = modelRepository.toggleModel(model.id, targetProviderId, enabled)) {
                is AnResult.Ok -> Unit
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(models = current)
                    _errorEvent.value = result.error.toUiText()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderModelsScreen(
    providerId: String,
    providerName: String?,
    onBack: () -> Unit,
) {
    ProviderModelsContent(
        providerId = providerId,
        providerName = providerName,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelsContent(
    providerId: String,
    providerName: String?,
    onBack: () -> Unit,
    viewModel: ProviderModelsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorEvent by viewModel.errorEvent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val toggleError = errorEvent?.resolve()
    LaunchedEffect(providerId) { viewModel.bind(providerId) }
    LaunchedEffect(toggleError) {
        if (toggleError != null) {
            snackbar.showSnackbar(toggleError)
            viewModel.consumeToggleError()
        }
    }
    Scaffold(
        topBar = {
            AnTopBar(
                title = providerName?.takeIf { it.isNotBlank() } ?: providerId,
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            uiState.loading && uiState.models.isEmpty() ->
                SkeletonList(modifier = Modifier.fillMaxSize().padding(padding))
            uiState.error != null && uiState.models.isEmpty() ->
                ErrorBox(
                    text = uiState.error!!.resolve(),
                    onRetry = { viewModel.load() },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else ->
                ProviderModelsBody(
                    providerId = providerId,
                    models = uiState.models,
                    refreshing = uiState.loading,
                    onRefresh = { viewModel.load() },
                    onToggle = { model, enabled -> viewModel.toggle(model, enabled) },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
        }
    }
}

@Composable
private fun ProviderModelsBody(
    providerId: String,
    models: List<AIModel>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onToggle: (AIModel, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(models, query) {
        if (query.isBlank()) models
        else models.filter {
            it.id.contains(query, ignoreCase = true) ||
                (it.displayName?.contains(query, ignoreCase = true) == true)
        }
    }
    Column(modifier = modifier) {
        SearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.provider_models_search_hint),
        )
        RefreshBox(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (models.isEmpty()) {
                EmptyBox(
                    icon = Icons.Filled.SmartToy,
                    title = stringResource(R.string.provider_models_empty_title),
                    description = stringResource(R.string.discovery_empty_desc),
                )
            } else if (filtered.isEmpty()) {
                EmptyBox(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.provider_models_search_empty),
                    description = query,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.l),
                ) {
                    items(filtered, key = { it.id }) { model ->
                        ProviderModelRow(
                            providerId = providerId,
                            model = model,
                            onToggle = { onToggle(model, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderModelRow(
    providerId: String,
    model: AIModel,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = spacing.s),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = model.displayName?.takeIf { it.isNotBlank() } ?: model.id,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = model.id,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!model.chatSupported) {
                        Text(
                            text = stringResource(R.string.provider_model_no_chat_adapter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        model.type?.takeIf(String::isNotBlank)?.let { type ->
                            Text(
                                text = type.uppercase(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            leadingContent = {
                ModelIcon(modelId = model.id, providerId = providerId, label = model.displayName?.takeIf { it.isNotBlank() } ?: model.id, size = 40.dp)
            },
            trailingContent = {
                Switch(
                    checked = model.enabled == true,
                    onCheckedChange = onToggle,
                    enabled = model.chatSupported,
                )
            },
        )
    }
}

@Preview(name = "ProviderModels", showBackground = true)
@Composable
private fun ProviderModelsBodyPreview() {
    BobTheme(darkTheme = false) {
        ProviderModelsBody(
            providerId = "openai",
            models = listOf(
                AIModel(id = "gpt-4o", displayName = "GPT-4o", enabled = true),
                AIModel(id = "o1-mini", displayName = "o1-mini", enabled = false),
            ),
            refreshing = false,
            onRefresh = {},
            onToggle = { _, _ -> },
        )
    }
}
