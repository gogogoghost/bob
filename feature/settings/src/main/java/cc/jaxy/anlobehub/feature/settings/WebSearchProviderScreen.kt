package cc.jaxy.anlobehub.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.anlobehub.core.data.search.WebSearchProtocol
import cc.jaxy.anlobehub.core.designsystem.component.AnTextField
import cc.jaxy.anlobehub.core.designsystem.component.AnTopBar
import cc.jaxy.anlobehub.core.designsystem.component.PasswordField
import cc.jaxy.anlobehub.core.designsystem.component.SearchProviderIcon
import cc.jaxy.anlobehub.core.designsystem.component.SectionTitle
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import cc.jaxy.anlobehub.core.designsystem.theme.inputRowContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchProviderScreen(providerId: String?, onBack: () -> Unit) {
    val viewModel: WebSearchProviderViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = MaterialTheme.spacing
    val uriHandler = LocalUriHandler.current
    var advanced by remember(providerId) { mutableStateOf(false) }
    var protocolMenu by remember(providerId) { mutableStateOf(false) }
    var discard by remember(providerId) { mutableStateOf(false) }
    var docsFailed by remember(providerId) { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!state.pending) {
            if (state.dirty && !state.finished) discard = true else onBack()
        }
    }
    LaunchedEffect(providerId) { viewModel.bind(providerId) }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LaunchedEffect(state.validation) {
        if (state.validation == SearchProviderValidation.ENDPOINT) advanced = true
    }
    BackHandler(enabled = state.pending || state.dirty, onBack = leave)
    Scaffold(topBar = {
        AnTopBar(
            title = if (providerId == null) stringResource(R.string.web_search_add_provider)
                else state.draft?.name ?: stringResource(R.string.section_web_search),
            onBack = leave,
            actions = {
                TextButton(onClick = viewModel::save, enabled = state.canEdit) {
                    Text(stringResource(R.string.web_search_save))
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (state.loading || state.pending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.loadFailed) item { SearchFailure(R.string.web_search_provider_load_failed, viewModel::load) }
            if (state.missing) item { SearchErrorText(R.string.web_search_provider_not_found) }
            if (state.saveFailed) item { SearchErrorText(R.string.web_search_provider_save_failed) }
            val draft = state.draft
            if (draft != null) {
                item {
                    Surface(
                        color = MaterialTheme.inputRowContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ListItem(
                            leadingContent = { SearchProviderIcon(providerId = draft.id, name = draft.name, builtin = draft.builtin, size = 48.dp) },
                            headlineContent = { Text(draft.name) },
                            supportingContent = { Text(draft.protocol.displayName) },
                            trailingContent = {
                                if (state.hasStoredKey) {
                                    Text(stringResource(R.string.web_search_saved_key),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
                item { SectionTitle(text = stringResource(R.string.section_web_search)) }
                item {
                    Surface(
                        color = MaterialTheme.inputRowContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ListItem(
                            leadingContent = {
                                Icon(imageVector = Icons.Filled.Link, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            headlineContent = { Text(stringResource(R.string.web_search_test)) },
                            supportingContent = {
                                Text(
                                    when (state.testResult) {
                                        null -> draft.endpoint.ifBlank { stringResource(R.string.web_search_endpoint) }
                                        cc.jaxy.anlobehub.feature.settings.SearchTestResult.TESTING -> stringResource(R.string.web_search_testing)
                                        cc.jaxy.anlobehub.feature.settings.SearchTestResult.OK -> stringResource(R.string.web_search_test_ok)
                                        cc.jaxy.anlobehub.feature.settings.SearchTestResult.FAILED -> stringResource(R.string.web_search_test_failed)
                                    },
                                    maxLines = 2,
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = viewModel::testSearch, enabled = state.canEdit) {
                                    Text(stringResource(R.string.web_search_test))
                                }
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
                if (!draft.builtin) {
                    item { SectionTitle(text = stringResource(R.string.web_search_provider_name)) }
                    item {
                        AnTextField(
                            value = draft.name, onValueChange = viewModel::setName,
                            label = stringResource(R.string.web_search_provider_name),
                            singleLine = true, enabled = state.canEdit,
                            isError = state.validation == SearchProviderValidation.NAME,
                            supportingText = {
                                if (state.validation == SearchProviderValidation.NAME)
                                    Text(stringResource(R.string.web_search_name_required))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        ExposedDropdownMenuBox(expanded = protocolMenu,
                            onExpandedChange = { if (state.canEdit) protocolMenu = it }) {
                            AnTextField(
                                value = draft.protocol.displayName, onValueChange = {}, readOnly = true,
                                enabled = state.canEdit,
                                label = stringResource(R.string.web_search_protocol),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protocolMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                                WebSearchProtocol.entries.forEach { protocol ->
                                    DropdownMenuItem(text = { Text(protocol.displayName) }, onClick = {
                                        viewModel.setProtocol(protocol)
                                        protocolMenu = false
                                    })
                                }
                            }
                        }
                    }
                }
                if (draft.requiresApiKey) {
                    item { SectionTitle(text = stringResource(R.string.provider_detail_apikey)) }
                    item {
                        PasswordField(
                            value = state.apiKey, onValueChange = viewModel::setApiKey,
                            label = stringResource(R.string.provider_detail_apikey),
                            enabled = state.canEdit,
                            isError = state.validation == SearchProviderValidation.KEY,
                            supportingText = {
                                Text(stringResource(if (state.validation == SearchProviderValidation.KEY)
                                    R.string.web_search_key_required else R.string.web_search_key_help))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Text(stringResource(R.string.provider_cfg_aesgcm), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = spacing.l))
                    }
                } else {
                    item {
                        Text(stringResource(R.string.web_search_searxng_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = spacing.l))
                    }
                }
                if (draft.docsUrl.isNotBlank()) item {
                    TextButton(onClick = {
                        docsFailed = try { uriHandler.openUri(draft.docsUrl); false } catch (_: Exception) { true }
                    }) { Text(stringResource(R.string.web_search_docs)) }
                    if (docsFailed) SearchErrorText(R.string.web_search_docs_failed)
                }
                item { SectionTitle(text = stringResource(R.string.web_search_advanced)) }
                item {
                    Surface(
                        color = MaterialTheme.inputRowContainer,
                        modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced },
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.web_search_endpoint)) },
                            supportingContent = {
                                Text(if (advanced) stringResource(R.string.web_search_endpoint_help)
                                    else draft.endpoint.ifBlank { stringResource(R.string.web_search_endpoint) }, maxLines = 2)
                            },
                            trailingContent = {
                                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
                if (advanced) {
                    val official = cc.jaxy.anlobehub.core.data.search.WebSearchCatalog.providers
                        .firstOrNull { it.id == draft.id }?.endpoint.orEmpty()
                    if (draft.builtin && official.isNotBlank() && draft.endpoint != official) {
                        item {
                            TextButton(onClick = { viewModel.setEndpoint(official) }, enabled = state.canEdit) {
                                Text(stringResource(R.string.web_search_reset_endpoint))
                            }
                        }
                    }
                    item {
                        AnTextField(
                            value = draft.endpoint, onValueChange = viewModel::setEndpoint,
                            label = stringResource(R.string.web_search_endpoint),
                            singleLine = true, enabled = state.canEdit,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            isError = state.validation == SearchProviderValidation.ENDPOINT,
                            supportingText = {
                                Text(stringResource(if (state.validation == SearchProviderValidation.ENDPOINT)
                                    R.string.web_search_endpoint_invalid else R.string.web_search_endpoint_help))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (providerId != null && !draft.builtin) item {
                    TextButton(onClick = viewModel::requestDelete, enabled = state.canEdit) {
                        Text(stringResource(R.string.web_search_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (state.confirmDelete) AlertDialog(
        onDismissRequest = viewModel::dismissDelete,
        title = { Text(stringResource(R.string.web_search_delete_title)) },
        text = { Text(stringResource(R.string.web_search_delete_message)) },
        confirmButton = {
            TextButton(onClick = viewModel::confirmDelete, enabled = !state.pending) {
                Text(stringResource(R.string.web_search_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDelete, enabled = !state.pending) {
                Text(stringResource(R.string.web_search_cancel))
            }
        },
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.web_search_discard_title)) },
        text = { Text(stringResource(R.string.web_search_discard_message)) },
        confirmButton = {
            TextButton(onClick = { discard = false; onBack() }) { Text(stringResource(R.string.web_search_discard)) }
        },
        dismissButton = {
            TextButton(onClick = { discard = false }) { Text(stringResource(R.string.web_search_cancel)) }
        },
    )
}
