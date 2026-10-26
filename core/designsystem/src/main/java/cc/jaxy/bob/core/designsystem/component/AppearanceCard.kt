package cc.jaxy.bob.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.jaxy.bob.core.common.preferences.AppLanguage
import cc.jaxy.bob.core.common.preferences.AppTheme
import cc.jaxy.bob.core.designsystem.R
import cc.jaxy.bob.core.designsystem.theme.BobTheme

@Composable
fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.SYSTEM -> stringResource(R.string.ds_theme_system)
    AppTheme.LIGHT -> stringResource(R.string.ds_theme_light)
    AppTheme.DARK -> stringResource(R.string.ds_theme_dark)
}

@Composable
fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.ds_language_system)
    AppLanguage.CHINESE -> stringResource(R.string.ds_language_chinese)
    AppLanguage.ENGLISH -> stringResource(R.string.ds_language_english)
}

/**
 * Theme + language picker card with single-choice dialogs.
 *
 * Shared by Settings (appearance section) and the Server screen (first-run
 * discoverability). Callers persist via UiPreferencesStore; theme applies
 * immediately, language recreates the activity.
 */
@Composable
fun AppearanceCard(
    theme: AppTheme,
    language: AppLanguage,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    Card(modifier = modifier) {
        Column {
            SettingRow(
                icon = Icons.Filled.BrightnessMedium,
                title = stringResource(R.string.ds_theme_title),
                subtitle = themeLabel(theme),
                onClick = { showThemeDialog = true },
            )
            HorizontalDivider()
            SettingRow(
                icon = Icons.Filled.ColorLens,
                title = stringResource(R.string.ds_dynamic_color_title),
                subtitle = stringResource(R.string.ds_dynamic_color_subtitle),
                onClick = { onDynamicColorChange(!dynamicColor) },
                trailing = {
                    Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                },
            )
            HorizontalDivider()
            SettingRow(
                icon = Icons.Filled.Translate,
                title = stringResource(R.string.ds_language_title),
                subtitle = languageLabel(language),
                onClick = { showLanguageDialog = true },
            )
        }
    }
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.ds_theme_title)) },
            text = {
                Column {
                    AppTheme.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onThemeChange(option)
                                showThemeDialog = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option == theme,
                                onClick = {
                                    onThemeChange(option)
                                    showThemeDialog = false
                                },
                            )
                            Text(themeLabel(option))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.ds_language_title)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onLanguageChange(option)
                                showLanguageDialog = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option == language,
                                onClick = {
                                    onLanguageChange(option)
                                    showLanguageDialog = false
                                },
                            )
                            Text(languageLabel(option))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Preview(name = "Appearance", showBackground = true)
@Composable
private fun AppearanceCardPreview() {
    BobTheme {
        AppearanceCard(
            theme = AppTheme.SYSTEM,
            language = AppLanguage.SYSTEM,
            onThemeChange = {},
            onLanguageChange = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Appearance-Dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AppearanceCardDarkPreview() {
    BobTheme(darkTheme = true) {
        AppearanceCard(
            theme = AppTheme.DARK,
            language = AppLanguage.CHINESE,
            onThemeChange = {},
            onLanguageChange = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
