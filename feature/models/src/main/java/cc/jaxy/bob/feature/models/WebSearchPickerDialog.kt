package cc.jaxy.bob.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.jaxy.bob.core.data.chat.AIModel
import cc.jaxy.bob.core.data.search.WebSearchMode
import cc.jaxy.bob.core.data.search.WebSearchPolicy
import cc.jaxy.bob.core.data.search.WebSearchProvider
import cc.jaxy.bob.core.designsystem.component.ModelIcon
import cc.jaxy.bob.core.designsystem.component.SearchProviderIcon
import cc.jaxy.bob.core.designsystem.theme.spacing

/**
 * The single web-search chooser used by chat and agent settings. Every row
 * shares the same radio + icon + copy; the only context-specific addition is
 * the pure-prompt notice at the top.
 */
@Composable
fun WebSearchPickerDialog(
    policy: WebSearchPolicy,
    providers: List<WebSearchProvider>,
    providersError: Boolean,
    nativeSupported: Boolean,
    nativeModel: AIModel? = null,
    purePrompt: Boolean = false,
    onSelect: (WebSearchPolicy) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val selected = providers.firstOrNull { it.id == policy.providerId }
    val unavailable = policy.mode == WebSearchMode.PROVIDER && selected?.enabled != true
    val options = listOf(
        WebSearchPolicy() to stringResource(R.string.web_search_picker_disable),
        WebSearchPolicy(WebSearchMode.NATIVE) to stringResource(R.string.web_search_picker_native),
    ) + providers.filter { it.enabled }.map {
        WebSearchPolicy(WebSearchMode.PROVIDER, it.id) to it.name
    } + if (unavailable) {
        listOf(policy to (selected?.name ?: policy.providerId.orEmpty()))
    } else {
        emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_search_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                if (purePrompt) {
                    Text(
                        stringResource(R.string.web_search_picker_pure_prompt_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (providersError) {
                    Text(
                        stringResource(R.string.web_search_picker_settings_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                options.forEach { (option, title) ->
                    val optionProvider = providers.firstOrNull { it.id == option.providerId }
                    val enabled = when {
                        option.mode == WebSearchMode.NATIVE -> nativeSupported
                        option.mode == WebSearchMode.PROVIDER ->
                            providers.any { it.id == option.providerId && it.enabled }
                        else -> true
                    }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                            ) {
                                when (option.mode) {
                                    WebSearchMode.OFF -> Text("🚫", style = MaterialTheme.typography.titleSmall)
                                    WebSearchMode.NATIVE ->
                                        if (nativeModel != null) {
                                            ModelIcon(
                                                modelId = nativeModel.id,
                                                providerId = nativeModel.providerId,
                                                label = nativeModel.displayName ?: nativeModel.id,
                                                size = 24.dp,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.SmartToy,
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    else -> SearchProviderIcon(
                                        providerId = optionProvider?.id ?: option.providerId,
                                        name = title,
                                        builtin = optionProvider?.builtin == true,
                                        size = 24.dp,
                                    )
                                }
                                Text(title)
                            }
                        },
                        supportingContent = if (!enabled) ({
                            Text(
                                stringResource(
                                    if (option.mode == WebSearchMode.NATIVE) {
                                        R.string.web_search_picker_native_unavailable
                                    } else {
                                        R.string.web_search_picker_provider_unavailable
                                    },
                                ),
                            )
                        }) else null,
                        leadingContent = {
                            RadioButton(selected = policy == option, onClick = null, enabled = enabled)
                        },
                        modifier = Modifier.selectable(
                            selected = policy == option,
                            enabled = enabled,
                            role = Role.RadioButton,
                        ) {
                            onSelect(option)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onManage) {
                Text(stringResource(R.string.web_search_picker_manage))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.web_search_picker_close))
            }
        },
    )
}
