package cc.jaxy.bob.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.SearchProviderIcon
import cc.jaxy.bob.core.designsystem.component.SectionTitle
import cc.jaxy.bob.core.designsystem.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsScreen(
    onBack: () -> Unit,
    onProviderClick: (String) -> Unit,
    onAddProvider: () -> Unit,
) {
    val viewModel: WebSearchOverviewViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDefaults by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    LaunchedEffect(state.configureProviderId) {
        state.configureProviderId?.let {
            viewModel.consumeConfigurationRequest()
            onProviderClick(it)
        }
    }
    Scaffold(topBar = {
        AnTopBar(title = stringResource(R.string.section_web_search), onBack = onBack,
            actions = {
                IconButton(onClick = onAddProvider, enabled = state.canEdit) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.web_search_add_provider))
                }
            })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.l, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            if (state.loading || state.pending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.loadFailed) item {
                SearchFailure(R.string.web_search_load_failed, viewModel::load)
            }
            if (state.saveFailed) item { SearchErrorText(R.string.web_search_write_failed) }
            item {
                Card {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.web_search_default_title)) },
                        supportingContent = { Text(searchPolicyLabel(state.policy, state.providers)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = state.canEdit) { showDefaults = true },
                    )
                }
            }
            if (state.selectedProviderUnavailable) item {
                SearchErrorText(R.string.web_search_selected_unavailable)
            }
            if (!state.loading && !state.loadFailed) {
                item { SectionTitle(stringResource(R.string.web_search_builtin_title)) }
                items(state.providers.filter { it.builtin }, key = { it.id }) { provider ->
                    SearchProviderRow(provider, state.canEdit, onProviderClick, viewModel::setProviderEnabled)
                }
                if (state.providers.any { !it.builtin }) item { SectionTitle(stringResource(R.string.web_search_custom_title)) }
                items(state.providers.filterNot { it.builtin }, key = { it.id }) { provider ->
                    SearchProviderRow(provider, state.canEdit, onProviderClick, viewModel::setProviderEnabled)
                }
            }
        }
    }
    if (showDefaults) {
        AlertDialog(
            onDismissRequest = { showDefaults = false },
            title = { Text(stringResource(R.string.web_search_default_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.web_search_default_scope), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val options = listOf(WebSearchPolicy(), WebSearchPolicy(WebSearchMode.NATIVE)) +
                        state.providers.filter { it.enabled }.map { WebSearchPolicy(WebSearchMode.PROVIDER, it.id) }
                    options.forEach { policy ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = state.policy == policy,
                                enabled = state.canEdit,
                                role = Role.RadioButton,
                                onClick = { viewModel.selectPolicy(policy); showDefaults = false },
                            ).padding(vertical = spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.policy == policy, onClick = null)
                            Text(searchPolicyLabel(policy, state.providers), Modifier.padding(start = spacing.s))
                        }
                    }
                    Text(stringResource(R.string.web_search_native_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaults = false }) { Text(stringResource(R.string.web_search_close)) }
            },
        )
    }
}

@Composable
private fun SearchProviderRow(
    provider: WebSearchProvider,
    enabled: Boolean,
    onProviderClick: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val switchDescription = stringResource(R.string.web_search_enable_named, provider.name)
    Card {
        ListItem(
            leadingContent = { SearchProviderIcon(providerId = provider.id, name = provider.name, builtin = provider.builtin) },
            headlineContent = { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (!provider.builtin) {
                { Text(provider.protocol.displayName) }
            } else {
                null
            },
            trailingContent = {
                Switch(checked = provider.enabled, onCheckedChange = { onToggle(provider.id, it) },
                    enabled = enabled, modifier = Modifier.semantics { contentDescription = switchDescription })
            },
            modifier = Modifier.clickable(enabled = enabled) { onProviderClick(provider.id) },
        )
    }
}

@Composable
internal fun searchPolicyLabel(policy: WebSearchPolicy, providers: List<WebSearchProvider>): String = when (policy.mode) {
    WebSearchMode.OFF -> stringResource(R.string.web_search_off)
    WebSearchMode.NATIVE -> stringResource(R.string.web_search_native)
    WebSearchMode.PROVIDER -> providers.firstOrNull { it.id == policy.providerId }?.name
        ?: stringResource(R.string.web_search_missing_provider)
}

@Composable
internal fun SearchErrorText(message: Int) {
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun SearchFailure(message: Int, onRetry: () -> Unit) {
    Column {
        SearchErrorText(message)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.web_search_retry)) }
    }
}
