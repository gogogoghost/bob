package cc.jaxy.bob.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.chat.supportsWebSearch
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.designsystem.component.AnTextField
import cc.jaxy.bob.core.designsystem.component.AvatarImage
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.ErrorBox
import cc.jaxy.bob.core.designsystem.component.InitialAvatar
import cc.jaxy.bob.core.designsystem.component.SearchProviderIcon
import cc.jaxy.bob.core.designsystem.component.SectionTitle
import cc.jaxy.bob.core.designsystem.component.SkeletonList
import cc.jaxy.bob.core.designsystem.text.resolve
import cc.jaxy.bob.core.designsystem.theme.spacing
import cc.jaxy.bob.core.designsystem.theme.inputRowContainer
import cc.jaxy.bob.core.designsystem.R as DsR

/**
 * Standalone create/edit page. `agentId == null` creates a new agent.
 * Edits local avatar/title/description/model/systemRole/openingMessage/
 * openingQuestions/webSearch fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    agentId: String?,
    onBack: () -> Unit,
    onOpenModelPicker: (current: AIModel?) -> Unit,
    onCreated: (String) -> Unit = {},
    onMutated: () -> Unit = {},
    onOpenWebSearchSettings: () -> Unit = {},
    viewModel: AgentEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val errorText = uiState.error?.resolve()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(agentId) { viewModel.bind(agentId) }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(uiState.createdId) {
        uiState.createdId?.let {
            viewModel.consumeCreated()
            onCreated(it)
        }
    }
    val savedText = stringResource(R.string.agents_saved)
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            snackbar.showSnackbar(savedText)
            onMutated()
        }
    }
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) {
            onMutated()
            onBack()
        }
    }
    Scaffold(
        topBar = {
            AnTopBar(
                title = if (agentId == null) {
                    stringResource(R.string.agents_create_title)
                } else {
                    stringResource(R.string.agents_edit_title)
                },
                onBack = onBack,
                actions = {
                    if (agentId != null) {
                        IconButton(onClick = { viewModel.duplicate { onMutated(); onBack() } }) {
                            Icon(
                                imageVector = Icons.Filled.FileCopy,
                                contentDescription = stringResource(R.string.agents_duplicate),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.agents_delete),
                            )
                        }
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = !uiState.saving && !uiState.loading && !uiState.loadFailed,
                    ) {
                        if (uiState.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.agents_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            uiState.loading -> SkeletonList(
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            uiState.loadFailed && uiState.error != null ->
                ErrorBox(
                    text = uiState.error!!.resolve(),
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else -> AgentEditBody(
                state = uiState,
                onAvatarChange = viewModel::onAvatarChange,
                onAvatarSave = viewModel::saveAvatar,
                onTitleChange = viewModel::onTitleChange,
                onDescriptionChange = viewModel::onDescriptionChange,
                onSystemRoleChange = viewModel::onSystemRoleChange,
                onOpeningMessageChange = viewModel::onOpeningMessageChange,
                onQuestionChange = viewModel::onQuestionChange,
                onWebSearchChange = viewModel::onWebSearchChange,
                onPurePromptChange = viewModel::onPurePromptChange,
                onOpenWebSearchSettings = onOpenWebSearchSettings,
                onAddQuestion = viewModel::addQuestion,
                onRemoveQuestion = viewModel::removeQuestion,
                onOpenModelPicker = {
                    val current = uiState.model.takeIf { it.isNotBlank() }?.let {
                        AIModel(
                            id = it,
                            displayName = uiState.modelDisplayName.takeIf { n -> n.isNotBlank() },
                            providerId = uiState.provider.takeIf { p -> p.isNotBlank() },
                        )
                    }
                    onOpenModelPicker(current)
                },
                onModelClear = viewModel::clearModel,
                onUploadAvatar = { uri ->
                    val prepared = AvatarImage.prepare(context, uri)
                    android.util.Log.d(
                        "AgentEdit",
                        "prepared=${prepared?.bytes?.size}",
                    )
                    if (prepared != null) {
                        viewModel.uploadAvatar(prepared.bytes, prepared.mime)
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agents_delete_title)) },
            text = { Text(stringResource(R.string.agents_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                    enabled = !uiState.deleting,
                ) {
                    Text(stringResource(R.string.agents_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(DsR.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun AgentEditBody(
    state: AgentEditViewModel.UiState,
    onAvatarChange: (String) -> Unit,
    onAvatarSave: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSystemRoleChange: (String) -> Unit,
    onOpeningMessageChange: (String) -> Unit,
    onWebSearchChange: (WebSearchPolicy) -> Unit,
    onPurePromptChange: (Boolean) -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    onQuestionChange: (Int, String) -> Unit,
    onAddQuestion: () -> Unit,
    onRemoveQuestion: (Int) -> Unit,
    onOpenModelPicker: () -> Unit,
    onModelClear: () -> Unit,
    onUploadAvatar: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) onUploadAvatar(uri) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        SectionTitle(text = stringResource(R.string.agents_edit_section_identity))
        var showAvatarPicker by remember { mutableStateOf(false) }
        LaunchedEffect(state.avatar, state.uploading) {
            if (!state.uploading && (state.avatar.startsWith("http") || state.avatar.startsWith("file://"))) {
                showAvatarPicker = false
            }
        }
        // Avatar + name in one tappable row: no labels needed, the affordance
        // is the avatar itself plus the chevron.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAvatarPicker = true }
                .padding(horizontal = spacing.l, vertical = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            InitialAvatar(
                name = state.title.takeIf { it.isNotBlank() } ?: "?",
                avatarUrl = state.avatar.takeIf { it.isNotBlank() },
                size = 56.dp,
            )
            Text(
                text = state.title.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.agents_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showAvatarPicker) {
            AvatarPickerSheet(
                uploading = state.uploading,
                onPick = {
                    onAvatarSave(it)
                    showAvatarPicker = false
                },
                onUploadClick = { imagePicker.launch("image/*") },
                onDismiss = { if (!state.uploading) showAvatarPicker = false },
            )
        }
        Column(
            modifier = Modifier.padding(vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            AnTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = stringResource(R.string.agents_create_title_label),
                modifier = Modifier.fillMaxWidth(),
                isError = state.titleError,
                supportingText = if (state.titleError) {
                    { Text(stringResource(R.string.agents_create_title_required)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            AnTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = stringResource(R.string.agents_edit_description_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
        }
        SectionTitle(text = stringResource(R.string.agents_edit_section_model))
        Surface(
            color = MaterialTheme.inputRowContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
        ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text(stringResource(R.string.agents_create_model_label)) },
            supportingContent = {
                Text(
                    state.modelDisplayName.takeIf { it.isNotBlank() }
                        ?: state.model.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.agents_create_model_auto),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.model.isNotBlank()) {
                        IconButton(onClick = onModelClear) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(DsR.string.common_clear),
                            )
                        }
                    }
                    Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenModelPicker),
        )
        }
        AgentWebSearchSelector(state, onWebSearchChange, onOpenWebSearchSettings)
        SectionTitle(text = stringResource(R.string.agents_edit_section_prompt))
        Surface(
            color = MaterialTheme.inputRowContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListItem(
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = { Text(stringResource(R.string.agents_pure_prompt_title)) },
                supportingContent = { Text(stringResource(R.string.agents_pure_prompt_desc)) },
                trailingContent = {
                    Switch(
                        checked = state.purePrompt,
                        onCheckedChange = onPurePromptChange,
                        enabled = !state.loading && !state.saving,
                    )
                },
            )
        }
        Column(
            modifier = Modifier.padding(vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            AnTextField(
                value = state.systemRole,
                onValueChange = onSystemRoleChange,
                label = stringResource(R.string.agents_create_system_role_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 6,
            )
        }
        SectionTitle(text = stringResource(R.string.agents_edit_section_opening))
        Column(
            modifier = Modifier.padding(vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            AnTextField(
                value = state.openingMessage,
                onValueChange = onOpeningMessageChange,
                label = stringResource(R.string.agents_edit_opening_message_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            state.openingQuestions.forEachIndexed { index, question ->
                AnTextField(
                    value = question,
                    onValueChange = { onQuestionChange(index, it) },
                    label = stringResource(R.string.agents_edit_opening_question_label, index + 1),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { onRemoveQuestion(index) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(DsR.string.common_delete),
                            )
                        }
                    },
                )
            }
            FilledTonalButton(onClick = onAddQuestion, modifier = Modifier.padding(horizontal = spacing.l)) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.agents_edit_add_question))
            }
        }
    }
}

@Composable
private fun AgentWebSearchSelector(
    state: AgentEditViewModel.UiState,
    onChange: (WebSearchPolicy) -> Unit,
    onManage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val nativeSupported = supportsWebSearch(state.provider, state.model)
    val selected = state.searchProviders.find { it.id == state.webSearch.providerId }
    val unavailable = state.webSearch.mode == WebSearchMode.PROVIDER && selected?.enabled != true
    val label = when (state.webSearch.mode) {
        WebSearchMode.OFF -> stringResource(R.string.agents_web_search_disable)
        WebSearchMode.NATIVE -> stringResource(R.string.agents_web_search_native)
        WebSearchMode.PROVIDER -> selected?.name ?: state.webSearch.providerId.orEmpty()
    }
    val warning = when {
        state.providersError -> stringResource(R.string.agents_web_search_load_error)
        unavailable -> stringResource(R.string.agents_web_search_unavailable)
        state.webSearch.mode == WebSearchMode.NATIVE && !nativeSupported -> stringResource(R.string.agents_web_search_native_unavailable)
        else -> null
    }
    Surface(
        color = MaterialTheme.inputRowContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
    ListItem(
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(stringResource(R.string.agents_edit_section_web_search)) },
        supportingContent = {
            Column {
                Text(label)
                warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.loading && !state.saving) { open = true },
    )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.agents_edit_section_web_search)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    val options = listOf(
                        WebSearchPolicy() to stringResource(R.string.agents_web_search_disable),
                        WebSearchPolicy(WebSearchMode.NATIVE) to stringResource(R.string.agents_web_search_native),
                    ) + state.searchProviders.filter { it.enabled }.map {
                        WebSearchPolicy(WebSearchMode.PROVIDER, it.id) to it.name
                    } + if (unavailable) listOf(state.webSearch to label) else emptyList()
                    options.forEach { (policy, title) ->
                        val optionProvider = state.searchProviders.firstOrNull { it.id == policy.providerId }
                            ?: state.webSearch.takeIf { it.mode == WebSearchMode.PROVIDER && it.providerId == policy.providerId }
                                ?.let { cc.jaxy.bob.core.data.search.WebSearchProvider(it.providerId!!, title, cc.jaxy.bob.core.data.search.WebSearchProtocol.SEARXNG, "", enabled = false) }
                        val enabled = when {
                            policy.mode == WebSearchMode.NATIVE -> nativeSupported
                            policy.mode == WebSearchMode.PROVIDER -> state.searchProviders.any { it.id == policy.providerId && it.enabled }
                            else -> true
                        }
                        ListItem(
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            headlineContent = { Text(title) },
                            supportingContent = if (!enabled) ({ Text(stringResource(
                                if (policy.mode == WebSearchMode.NATIVE) R.string.agents_web_search_native_unavailable
                                else R.string.agents_web_search_unavailable,
                            )) }) else null,
                            leadingContent = {
                                if (policy.mode == WebSearchMode.PROVIDER) {
                                    SearchProviderIcon(providerId = optionProvider?.id, name = title, builtin = (optionProvider?.builtin == true), size = 32.dp)
                                } else {
                                    RadioButton(selected = state.webSearch == policy, onClick = null, enabled = enabled)
                                }
                            },
                            modifier = Modifier.selectable(selected = state.webSearch == policy, enabled = enabled, role = Role.RadioButton) {
                                onChange(policy)
                                open = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false; onManage() }) { Text(stringResource(R.string.agents_web_search_manage)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(DsR.string.common_cancel)) } },
        )
    }
}
