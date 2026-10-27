package cc.jaxy.bob.feature.models

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.chat.ModelAbilities
import cc.jaxy.bob.core.data.chat.ReasoningConfig
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.EmptyBox
import cc.jaxy.bob.core.designsystem.component.ErrorBox
import cc.jaxy.bob.core.designsystem.component.RefreshBox
import cc.jaxy.bob.core.designsystem.component.SkeletonList
import cc.jaxy.bob.core.designsystem.component.SearchField
import cc.jaxy.bob.core.designsystem.text.UiText
import cc.jaxy.bob.core.designsystem.text.resolve
import cc.jaxy.bob.core.designsystem.theme.BobTheme
import cc.jaxy.bob.core.designsystem.theme.spacing

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    selectedModelId: String? = null,
    selectedProviderId: String? = null,
    filterProviderId: String? = null,
    onPick: (AIModel) -> Unit,
    onBack: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reasoning by viewModel.reasoning.collectAsStateWithLifecycle()
    val reasoningUi by viewModel.reasoningUi.collectAsStateWithLifecycle()
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = detailKey?.let { key -> uiState.models.find { reasoningKeyOf(it, viewModel.agentId) == key } }
    val scoped = remember(uiState.models, filterProviderId) {
        if (filterProviderId.isNullOrBlank()) uiState.models
        else uiState.models.filter { it.providerId == filterProviderId }
    }
    return ModelsContent(
        models = scoped,
        loading = uiState.loading,
        error = uiState.error,
        selectedModelId = selectedModelId ?: viewModel.selectedModelId,
        selectedProviderId = selectedProviderId ?: viewModel.selectedProviderId,
        detail = detail,
        reasoning = reasoning,
        reasoningUi = reasoningUi,
        reasoningScope = viewModel.agentId.orEmpty(),
        onRefresh = viewModel::refresh,
        onPick = onPick,
        onBack = onBack,
        onShowDetail = { detailKey = reasoningKeyOf(it, viewModel.agentId) },
        onDismissDetail = { detailKey = null },
        onLoadReasoning = viewModel::loadReasoning,
        onSaveReasoning = viewModel::saveReasoning,
        onConsumeReasoningSaved = viewModel::consumeReasoningSaved,
        onDismissReasoningError = viewModel::dismissReasoningError,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModelsContent(
    models: List<AIModel>,
    loading: Boolean,
    error: UiText?,
    selectedModelId: String?,
    selectedProviderId: String?,
    detail: AIModel?,
    reasoning: Map<String, ReasoningConfig>,
    reasoningUi: Map<String, ReasoningUiState>,
    reasoningScope: String = "",
    onRefresh: () -> Unit,
    onPick: (AIModel) -> Unit,
    onBack: () -> Unit,
    onShowDetail: (AIModel) -> Unit,
    onDismissDetail: () -> Unit,
    onLoadReasoning: (AIModel) -> Unit,
    onSaveReasoning: (AIModel, String, String?) -> Unit,
    onConsumeReasoningSaved: (AIModel) -> Unit,
    onDismissReasoningError: (AIModel) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    // The picker exposes only enabled models backed by a local chat adapter.
    val enabled = remember(models) { models.filter { it.enabled == true && it.providerEnabled && it.chatSupported } }
    val filtered = remember(enabled, query) {
        if (query.isBlank()) {
            enabled
        } else {
            enabled.filter {
                (it.displayName ?: "").contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true) ||
                    (it.providerId ?: "").contains(query, ignoreCase = true) ||
                    (it.providerName ?: "").contains(query, ignoreCase = true)
            }
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { it.providerName?.takeIf { name -> name.isNotBlank() } ?: it.providerId?.takeIf { id -> id.isNotBlank() } ?: "Unknown" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .mapValues { (_, list) -> list.sortedBy { it.displayName?.lowercase().orEmpty().ifBlank { it.id.lowercase() } } }
    }
    // Searching expands everything; clearing restores collapse state.
    fun isCollapsed(provider: String): Boolean = query.isBlank() && provider in collapsed
    Scaffold(
        topBar = {
            AnTopBar(
                title = stringResource(R.string.models_title),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.models_search_hint),
            )
            RefreshBox(
                refreshing = loading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                when {
                    loading && models.isEmpty() -> SkeletonList()
                    error != null && models.isEmpty() -> ErrorBox(text = error.resolve(), onRetry = onRefresh)
                    models.isEmpty() -> EmptyBox(
                        icon = Icons.Outlined.SmartToy,
                        title = stringResource(R.string.models_empty_title),
                        description = stringResource(R.string.models_empty_desc),
                    )
                    filtered.isEmpty() -> EmptyBox(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.models_no_match_title),
                        description = stringResource(R.string.models_no_match_desc),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        grouped.forEach { (provider, list) ->
                            val hidden = isCollapsed(provider)
                            stickyHeader(key = "header-$provider") {
                                Surface(
                                    onClick = {
                                        collapsed = if (hidden) collapsed - provider else collapsed + provider
                                    },
                                    color = MaterialTheme.colorScheme.surface,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = spacing.l, vertical = spacing.s),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(spacing.s),
                                    ) {
                                        Icon(
                                            imageVector = if (hidden) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = provider,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = list.size.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (!hidden) {
                                items(list, key = { (it.providerId.orEmpty()) + "\n" + it.id }) { model ->
                                    ModelRow(
                                        model = model,
                                        selected = selectedModelId != null &&
                                            model.id == selectedModelId &&
                                            (selectedProviderId == null || model.providerId == selectedProviderId),
                                        onPick = onPick,
                                        onShowDetail = onShowDetail,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (detail != null) {
            val key = reasoningKeyOf(detail, reasoningScope)
            ModelDetailSheet(
                model = detail,
                config = reasoning[key],
                ui = reasoningUi[key] ?: ReasoningUiState(),
                snackbar = snackbar,
                onLoad = onLoadReasoning,
                onSave = onSaveReasoning,
                onConsumeSaved = onConsumeReasoningSaved,
                onDismissError = onDismissReasoningError,
                onDismiss = onDismissDetail,
            )
        }
    }
}

data class AbilityBadge(val icon: ImageVector, val labelRes: Int, val enabled: Boolean)

fun abilityBadges(abilities: ModelAbilities): List<AbilityBadge> = listOf(
    AbilityBadge(Icons.Filled.Visibility, R.string.models_ability_vision, abilities.vision),
    AbilityBadge(Icons.Filled.Psychology, R.string.models_ability_reasoning, abilities.reasoning),
    AbilityBadge(Icons.Filled.Build, R.string.models_ability_tools, abilities.functionCall),
    AbilityBadge(Icons.Filled.AttachFile, R.string.models_ability_files, abilities.files),
    AbilityBadge(Icons.Filled.Audiotrack, R.string.models_ability_audio, abilities.audio),
    AbilityBadge(Icons.Filled.Videocam, R.string.models_ability_video, abilities.video),
    AbilityBadge(Icons.Filled.Image, R.string.models_ability_image, abilities.imageOutput),
    AbilityBadge(Icons.Filled.Search, R.string.models_ability_search, abilities.search),
    AbilityBadge(Icons.Filled.DataObject, R.string.models_ability_json, abilities.structuredOutput),
).filter { it.enabled }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AbilityChips(badges: List<AbilityBadge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        badges.forEach { badge ->
            // Compact enough that the common Reasoning/Tools/JSON trio fits on
            // one line; FlowRow still wraps only when the width is exhausted.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Row(
                    modifier = Modifier.height(24.dp).padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = badge.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = stringResource(badge.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val v = tokens / 1_000_000.0
        (if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)) + "M"
    }
    tokens >= 1_000 -> {
        val v = tokens / 1_000.0
        (if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)) + "K"
    }
    else -> tokens.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable

private fun ModelRow(
    model: AIModel,
    selected: Boolean,
    onPick: (AIModel) -> Unit,
    onShowDetail: (AIModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val badges = remember(model.abilities) { abilityBadges(model.abilities) }
    val isChat = model.type.isNullOrBlank() || model.type.equals("chat", ignoreCase = true)
    ListItem(
        headlineContent = {
            Text(
                model.displayName?.takeIf { it.isNotBlank() } ?: model.id,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        supportingContent = {
            Column {
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val extra = buildList {
                    if (!isChat) add(model.type!!.uppercase())
                    model.contextWindowTokens?.let { add(formatTokens(it)) }
                }
                if (badges.isNotEmpty() || extra.isNotEmpty()) {
                    AbilityChips(
                        badges = badges,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    extra.forEach { label ->
                        AssistChip(
                            onClick = {},
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp).padding(top = 2.dp),
                            shape = MaterialTheme.shapes.small,
                            enabled = false,
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { onShowDetail(model) }) {
                    Icon(imageVector = Icons.Filled.Info, contentDescription = stringResource(R.string.models_detail))
                }
            }
        },
        modifier = modifier.combinedClickable(
            onClick = { onPick(model) },
            onLongClick = { onShowDetail(model) },
        ),
    )
}

private val previewModels = listOf(
    AIModel(
        id = "gpt-5",
        displayName = "GPT-5",
        providerId = "openai",
        providerName = "OpenAI",
        type = "chat",
        description = "Flagship reasoning model",
        contextWindowTokens = 400_000,
        maxOutput = 128_000,
        abilities = ModelAbilities(vision = true, reasoning = true, functionCall = true, files = true, search = true, structuredOutput = true),
    ),
    AIModel(id = "gpt-4o-mini", displayName = "GPT-4o mini", providerId = "openai", providerName = "OpenAI", type = "chat", contextWindowTokens = 128_000),
    AIModel(
        id = "dall-e-3",
        displayName = "DALL-E 3",
        providerId = "openai",
        providerName = "OpenAI",
        type = "image",
        abilities = ModelAbilities(imageOutput = true),
    ),
    AIModel(id = "deepseek-chat", displayName = "DeepSeek Chat", providerId = "deepseek", providerName = "DeepSeek", type = "chat"),
)

@Preview(name = "Models-Light", showBackground = true)

@Composable
private fun ModelsLightPreview() {
    BobTheme(darkTheme = false) {
        ModelsContent(
            models = previewModels,
            loading = false,
            error = null,
            selectedModelId = "gpt-5",
            selectedProviderId = "openai",
            detail = null,
            reasoning = emptyMap(),
            reasoningUi = emptyMap(),
            onRefresh = {},
            onPick = {},
            onBack = {},
            onShowDetail = {},
            onDismissDetail = {},
            onLoadReasoning = {},
            onSaveReasoning = { _, _, _ -> },
            onConsumeReasoningSaved = {},
            onDismissReasoningError = {},
        )
    }
}

@Preview(
    name = "Models-Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ModelsDarkPreview() {
    BobTheme(darkTheme = true) {
        ModelsContent(
            models = previewModels,
            loading = false,
            error = null,
            selectedModelId = "dall-e-3",
            selectedProviderId = "openai",
            detail = null,
            reasoning = emptyMap(),
            reasoningUi = emptyMap(),
            onRefresh = {},
            onPick = {},
            onBack = {},
            onShowDetail = {},
            onDismissDetail = {},
            onLoadReasoning = {},
            onSaveReasoning = { _, _, _ -> },
            onConsumeReasoningSaved = {},
            onDismissReasoningError = {},
        )
    }
}
