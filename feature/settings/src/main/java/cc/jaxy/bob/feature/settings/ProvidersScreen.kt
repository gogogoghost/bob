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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cc.jaxy.bob.core.common.result.AnResult
import cc.jaxy.bob.core.data.provider.AiProvider
import cc.jaxy.bob.core.data.provider.ProviderRepository
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.EmptyBox
import cc.jaxy.bob.core.designsystem.component.ErrorBox
import cc.jaxy.bob.core.designsystem.component.ProviderIcon
import cc.jaxy.bob.core.designsystem.component.RefreshBox
import cc.jaxy.bob.core.designsystem.component.SkeletonList
import cc.jaxy.bob.core.designsystem.component.AnTextField
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
internal class ProvidersViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val providers: List<AiProvider> = emptyList(),
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _errorEvent = MutableStateFlow<UiText?>(null)
    val errorEvent: StateFlow<UiText?> = _errorEvent.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val result = providerRepository.listProviders()) {
                is AnResult.Ok -> _uiState.value = UiState(loading = false, providers = result.value)
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

    fun toggle(provider: AiProvider, enabled: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.providers
            _uiState.value = _uiState.value.copy(
                providers = current.map { if (it.id == provider.id) it.copy(enabled = enabled) else it },
            )
            when (val result = providerRepository.toggleProvider(provider.id, enabled)) {
                is AnResult.Ok -> Unit
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(providers = current)
                    _errorEvent.value = result.error.toUiText()
                }
            }
        }
    }

    fun createCustom(name: String, endpoint: String) {
        viewModelScope.launch {
            when (val result = providerRepository.createCustom(name, endpoint)) {
                is AnResult.Ok -> load()
                is AnResult.Err -> _errorEvent.value = result.error.toUiText()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    onProviderClick: (id: String, name: String) -> Unit,
    onConfigClick: (id: String, name: String?) -> Unit = { _, _ -> },
) {
    ProvidersContent(onBack = onBack, onProviderClick = onProviderClick, onConfigClick = onConfigClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersContent(
    onBack: () -> Unit,
    onProviderClick: (id: String, name: String) -> Unit,
    onConfigClick: (id: String, name: String?) -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorEvent by viewModel.errorEvent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val toggleError = errorEvent?.resolve()
    LaunchedEffect(toggleError) {
        if (toggleError != null) {
            snackbar.showSnackbar(toggleError)
            viewModel.consumeToggleError()
        }
    }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    if (showCreateDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var endpoint by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.provider_custom_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.provider_custom_direct_note))
                    AnTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.provider_custom_name),
                        singleLine = true,
                    )
                    AnTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = stringResource(R.string.provider_custom_endpoint),
                        placeholder = { Text(stringResource(R.string.provider_custom_endpoint_hint)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && endpoint.isNotBlank(),
                    onClick = {
                        viewModel.createCustom(name.trim(), endpoint.trim())
                        showCreateDialog = false
                    },
                ) { Text(stringResource(R.string.provider_custom_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(cc.jaxy.bob.core.designsystem.R.string.common_cancel))
                }
            },
        )
    }
    Scaffold(
        topBar = { AnTopBar(title = stringResource(R.string.providers_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            uiState.loading && uiState.providers.isEmpty() ->
                SkeletonList(modifier = Modifier.fillMaxSize().padding(padding))
            uiState.error != null && uiState.providers.isEmpty() ->
                ErrorBox(
                    text = uiState.error!!.resolve(),
                    onRetry = { viewModel.load() },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else ->
                ProvidersBody(
                    providers = uiState.providers,
                    refreshing = uiState.loading,
                    onRefresh = { viewModel.load() },
                    onProviderClick = onProviderClick,
                    onConfigClick = onConfigClick,
                    onToggle = { provider, enabled -> viewModel.toggle(provider, enabled) },
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onCreateClick = { showCreateDialog = true },
                )
        }
    }
}

@Composable
private fun ProvidersBody(
    providers: List<AiProvider>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onProviderClick: (id: String, name: String) -> Unit,
    onConfigClick: (id: String, name: String?) -> Unit,
    onToggle: (AiProvider, Boolean) -> Unit,
    onCreateClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(providers, query) {
        if (query.isBlank()) providers
        else providers.filter {
            it.id.contains(query, ignoreCase = true) ||
                (it.name?.contains(query, ignoreCase = true) == true)
        }
    }
    Column(modifier = modifier) {
        Button(
            onClick = onCreateClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.l, vertical = spacing.s),
        ) { Text(stringResource(R.string.provider_custom_add)) }
        SearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.providers_search_hint),
        )
        RefreshBox(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (providers.isEmpty()) {
                EmptyBox(
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.providers_empty_title),
                    description = stringResource(R.string.discovery_empty_desc),
                )
            } else if (filtered.isEmpty()) {
                EmptyBox(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.providers_search_empty),
                    description = query,
                )
            } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.l),
            ) {
                items(filtered, key = { it.id }) { provider ->
                    ProviderRow(
                        provider = provider,
                        onClick = {
                            onProviderClick(provider.id, provider.name ?: provider.id)
                        },
                        onConfigClick = { onConfigClick(provider.id, provider.name) },
                        onToggle = { onToggle(provider, it) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: AiProvider,
    onClick: () -> Unit,
    onConfigClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(bottom = spacing.s),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = provider.name?.takeIf { it.isNotBlank() } ?: provider.id,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = if (provider.chatSupported) {
                        provider.id
                    } else {
                        stringResource(R.string.provider_id_chat_unavailable, provider.id)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                ProviderIcon(providerId = provider.id, name = provider.name?.takeIf { it.isNotBlank() } ?: provider.id, logoUrl = provider.logo)
            },
            trailingContent = {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onConfigClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                        )
                    }
                    Switch(
                        checked = provider.enabled,
                        onCheckedChange = onToggle,
                    )
                }
            },
        )
    }
}

@Preview(name = "Providers", showBackground = true)
@Composable
private fun ProvidersBodyPreview() {
    BobTheme(darkTheme = false) {
        ProvidersBody(
            providers = listOf(
                AiProvider(id = "openai", name = "OpenAI", enabled = true),
                AiProvider(id = "anthropic", name = "Anthropic", enabled = false),
            ),
            refreshing = false,
            onRefresh = {},
            onProviderClick = { _, _ -> },
            onConfigClick = { _, _ -> },
            onToggle = { _, _ -> },
        )
    }
}
