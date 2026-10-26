package cc.jaxy.bob.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.bob.core.designsystem.R as DsR
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.AppearanceCard
import cc.jaxy.bob.core.designsystem.component.SectionTitle
import cc.jaxy.bob.core.designsystem.component.SettingRow
import cc.jaxy.bob.core.designsystem.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onProvidersClick: () -> Unit = {},
    onWebSearchClick: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val searchSummary by viewModel.searchSummary.collectAsStateWithLifecycle()
    val providerSummary by viewModel.providerSummary.collectAsStateWithLifecycle()
    val spacing = MaterialTheme.spacing
    Scaffold(topBar = {
        AnTopBar(title = stringResource(DsR.string.common_settings_title), onBack = null)
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = spacing.s),
        ) {
            item { SectionTitle(text = stringResource(R.string.section_appearance)) }
            item {
                AppearanceCard(
                    theme = theme,
                    language = language,
                    onThemeChange = viewModel::setTheme,
                    onLanguageChange = viewModel::setLanguage,
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = viewModel::setDynamicColor,
                    modifier = Modifier.padding(horizontal = spacing.l),
                )
            }
            item { SectionTitle(text = stringResource(R.string.section_web_search)) }
            item {
                Card(modifier = Modifier.padding(horizontal = spacing.l)) {
                    SettingRow(
                        icon = Icons.Filled.TravelExplore,
                        title = stringResource(R.string.section_web_search),
                        subtitle = searchSummary?.let { stringResource(R.string.web_search_enabled_format, it) }
                            ?: stringResource(R.string.web_search_entry_description),
                        onClick = onWebSearchClick,
                    )
                }
            }
            item { SectionTitle(text = stringResource(R.string.providers_title)) }
            item {
                Card(modifier = Modifier.padding(horizontal = spacing.l)) {
                    SettingRow(
                        icon = Icons.Filled.Cloud,
                        title = stringResource(R.string.providers_title),
                        subtitle = providerSummary?.let { stringResource(R.string.providers_enabled_format, it) }
                            ?: stringResource(R.string.providers_local_description),
                        onClick = onProvidersClick,
                    )
                }
            }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val version = androidx.compose.runtime.remember(context) {
                    runCatching {
                        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            context.packageManager.getPackageInfo(
                                context.packageName,
                                android.content.pm.PackageManager.PackageInfoFlags.of(0),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        }
                        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                            @Suppress("DEPRECATION")
                            info.versionCode.toLong()
                        }
                        "${info.versionName} ($code)"
                    }.getOrDefault("?")
                }
                Text(
                    text = stringResource(R.string.version_format, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xl),
                )
            }
        }
    }
}

