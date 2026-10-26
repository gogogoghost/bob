package cc.jaxy.bob.feature.models

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
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

/** Shared model detail bottom sheet (model picker + chat long-press). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailSheet(
    model: AIModel,
    config: ReasoningConfig?,
    ui: ReasoningUiState,
    snackbar: SnackbarHostState,
    onLoad: (AIModel) -> Unit,
    onSave: (AIModel, String, String?) -> Unit,
    onConsumeSaved: (AIModel) -> Unit,
    onDismissError: (AIModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    LaunchedEffect(model.id, model.providerId) { onLoad(model) }
    val errorText = ui.error?.resolve()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            onDismissError(model)
        }
    }
    val savedText = stringResource(R.string.models_detail_saved)
    LaunchedEffect(ui.saved) {
        if (ui.saved) {
            snackbar.showSnackbar(savedText)
            onConsumeSaved(model)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.l).padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Text(
                model.displayName?.takeIf { it.isNotBlank() } ?: model.id,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                listOfNotNull(model.id, model.providerName ?: model.providerId).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            val specs = buildList {
                model.contextWindowTokens?.let { add(stringResource(R.string.models_detail_context) to formatTokens(it)) }
                model.maxOutput?.let { add(stringResource(R.string.models_detail_output) to formatTokens(it)) }
                model.type?.takeIf { t -> t.isNotBlank() }?.let { add(stringResource(R.string.models_detail_type) to it) }
                model.family?.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.models_detail_family) to it) }
                model.generation?.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.models_detail_generation) to it) }
                model.knowledgeCutoff?.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.models_detail_knowledge_cutoff) to it) }
            }
            if (specs.isNotEmpty()) {
                specs.forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val badges = remember(model.abilities) { abilityBadges(model.abilities) }
            AbilityChips(badges = badges)
            if (model.abilities.reasoning) {
                HorizontalDivider()
                ReasoningSection(
                    config = config,
                    loading = ui.loading,
                    saving = ui.saving,
                    onSelect = { key, value -> onSave(model, key, value) },
                )
            }
        }
    }
}

@Composable
private fun ReasoningSection(
    config: ReasoningConfig?,
    loading: Boolean,
    saving: Boolean,
    onSelect: (String, String?) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
            Text(stringResource(R.string.models_detail_reasoning_title), style = MaterialTheme.typography.titleSmall)
            if (loading || saving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            if (saving) Text(stringResource(R.string.models_detail_saving), style = MaterialTheme.typography.bodySmall)
        }
        ReasoningChoiceRow(
            label = stringResource(R.string.models_detail_effort),
            options = listOf("low", "medium", "high", "max"),
            selected = config?.effort,
            enabled = !loading && !saving,
            onSelect = { onSelect(ReasoningKeys.EFFORT, it) },
        )
        ReasoningChoiceRow(
            label = stringResource(R.string.models_detail_reasoning_effort),
            options = listOf("low", "medium", "high"),
            selected = config?.reasoningEffort,
            enabled = !loading && !saving,
            onSelect = { onSelect(ReasoningKeys.REASONING_EFFORT, it) },
        )
        ReasoningChoiceRow(
            label = stringResource(R.string.models_detail_thinking_level),
            options = listOf("minimal", "low", "medium", "high"),
            selected = config?.thinkingLevel,
            enabled = !loading && !saving,
            onSelect = { onSelect(ReasoningKeys.THINKING_LEVEL, it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningChoiceRow(
    label: String,
    options: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    val unset = stringResource(R.string.models_detail_unset)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val items = listOf<String?>(null) + options
            items.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == selected || (value == null && selected.isNullOrBlank()),
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, items.size),
                    enabled = enabled,
                    label = { Text(value ?: unset, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                )
            }
        }
    }
}
