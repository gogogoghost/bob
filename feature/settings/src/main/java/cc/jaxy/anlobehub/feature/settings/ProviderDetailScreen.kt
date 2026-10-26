package cc.jaxy.anlobehub.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.provider.CheckResult
import cc.jaxy.anlobehub.core.data.provider.ProviderDetail
import cc.jaxy.anlobehub.core.data.provider.ProviderRepository
import cc.jaxy.anlobehub.core.common.util.VAULT_API_KEY
import cc.jaxy.anlobehub.core.common.util.VAULT_BASE_URL
import cc.jaxy.anlobehub.core.common.util.providerConfigSpec
import cc.jaxy.anlobehub.core.designsystem.component.AnTopBar
import cc.jaxy.anlobehub.core.designsystem.component.ErrorBox
import cc.jaxy.anlobehub.core.designsystem.component.ProviderIcon
import cc.jaxy.anlobehub.core.designsystem.component.SectionTitle
import cc.jaxy.anlobehub.core.designsystem.component.SettingRow
import cc.jaxy.anlobehub.core.designsystem.component.SkeletonList
import cc.jaxy.anlobehub.core.designsystem.R as DsR
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.resolve
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import cc.jaxy.anlobehub.core.designsystem.theme.inputRowContainer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: ProviderDetail? = null,
        val error: UiText? = null,
        val saving: Boolean = false,
        /** Edited keyVaults values; absent key = unchanged. */
        val draftVaults: Map<String, String> = emptyMap(),
        val draftAuthMode: String? = null,
        val checking: Boolean = false,
        val checkResult: CheckResult? = null,
        val checkModelOverride: String? = null,
        val fetching: Boolean = false,
        val remoteModels: List<String>? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _event = MutableStateFlow<UiText?>(null)
    val event: StateFlow<UiText?> = _event.asStateFlow()

    private var providerId: String = ""

    fun bind(id: String) {
        if (providerId == id) return
        providerId = id
        load()
    }

    fun load() {
        viewModelScope.launch {
            val id = providerId
            if (id.isBlank()) return@launch
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val result = providerRepository.getDetail(id)) {
                is AnResult.Ok -> _uiState.value = UiState(loading = false, detail = result.value)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun consumeEvent() {
        _event.value = null
    }

    fun updateDraft(key: String, value: String) {
        _uiState.value = _uiState.value.copy(
            draftVaults = _uiState.value.draftVaults + (key to value),
        )
    }

    fun updateAuthMode(mode: String) {
        _uiState.value = _uiState.value.copy(draftAuthMode = mode)
    }


    /** Saves the draft (TopBar action on the credentials sub-page). */
    fun saveFromDraft() {
        val s = _uiState.value
        save(s.draftVaults)
    }

    /** Effective vault value: draft wins, stored second. */
    fun vaultValue(key: String): String =
        _uiState.value.draftVaults[key]
            ?: _uiState.value.detail?.keyVaults?.get(key).orEmpty()

    private fun save(draftVaults: Map<String, String>) {
        viewModelScope.launch {
            val id = providerId
            val detail = _uiState.value.detail
            _uiState.value = _uiState.value.copy(saving = true)
            // Only send changed values; a blanked credential means clear.
            val vaults = draftVaults.filter { (k, v) ->
                v != (detail?.keyVaults?.get(k).orEmpty())
            }
            when (
                val result = providerRepository.updateConfig(id = id, keyVaults = vaults)
            ) {
                is AnResult.Ok -> {
                    _uiState.value = _uiState.value.copy(
                        saving = false,
                        draftVaults = emptyMap(),
                        draftAuthMode = null,
                    )
                    _event.value = UiText.Res(R.string.provider_detail_saved)
                    load()
                }
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(saving = false)
                    _event.value = result.error.toUiText()
                }
            }
        }
    }

    fun check() {
        viewModelScope.launch {
            val id = providerId
            _uiState.value = _uiState.value.copy(checking = true, checkResult = null)
            val model = _uiState.value.checkModelOverride
                ?.takeIf { it.isNotBlank() }
                ?: _uiState.value.detail?.checkModel?.takeIf { it.isNotBlank() }
            if (model.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(checking = false)
                _event.value = UiText.Res(R.string.provider_detail_check_model_required)
                return@launch
            }
            when (val result = providerRepository.checkConnectivity(id, model)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    checking = false,
                    checkResult = result.value,
                )
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(checking = false)
                    _event.value = result.error.toUiText()
                }
            }
        }
    }

    fun setCheckModel(modelId: String) {
        _uiState.value = _uiState.value.copy(checkModelOverride = modelId, checkResult = null)
    }


    fun fetchRemoteModels() {
        viewModelScope.launch {
            val spec = providerConfigSpec(
                providerId, _uiState.value.detail?.source,
            )
            val baseURL = vaultValue(VAULT_BASE_URL).takeIf { it.isNotBlank() }
                ?: spec.endpoint?.placeholder.orEmpty()
            if (baseURL.isBlank()) {
                _event.value = UiText.Res(R.string.provider_detail_baseurl_required)
                return@launch
            }
            // The local vault provides stored credentials; a draft takes precedence.
            val apiKey = vaultValue(VAULT_API_KEY)
            val detail = _uiState.value.detail
            val requiresApiKey = detail?.let {
                it.source != "custom" && providerConfigSpec(it.id, it.source).showApiKey
            } ?: true
            if (requiresApiKey && apiKey.isBlank()) {
                _event.value = UiText.Res(R.string.provider_detail_apikey_required)
                return@launch
            }
            _uiState.value = _uiState.value.copy(fetching = true, remoteModels = null)
            when (val result = providerRepository.fetchRemoteModels(providerId, baseURL, apiKey)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    fetching = false,
                    remoteModels = result.value,
                )
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(fetching = false)
                    _event.value = result.error.toUiText()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    providerId: String,
    providerName: String? = null,
    onBack: () -> Unit,
    onModelsClick: (providerId: String, providerName: String?) -> Unit = { _, _ -> },
    onPickCheckModel: () -> Unit = {},
    viewModel: ProviderDetailViewModel = hiltViewModel(),
) {
    ProviderDetailContent(
        providerId = providerId,
        providerName = providerName,
        onBack = onBack,
        onModelsClick = onModelsClick,
        onPickCheckModel = onPickCheckModel,
        viewModel = viewModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailContent(
    providerId: String,
    providerName: String? = null,
    onBack: () -> Unit,
    onModelsClick: (providerId: String, providerName: String?) -> Unit = { _, _ -> },
    onPickCheckModel: () -> Unit = {},
    viewModel: ProviderDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = event?.resolve()
    var showCredentials by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(providerId) { viewModel.bind(providerId) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeEvent()
        }
    }
    Scaffold(
        topBar = {
            AnTopBar(
                title = if (showCredentials) {
                    stringResource(R.string.provider_detail_section_credentials)
                } else {
                    uiState.detail?.name?.takeIf { it.isNotBlank() }
                        ?: providerName?.takeIf { it.isNotBlank() }
                        ?: uiState.detail?.id ?: providerId
                },
                onBack = { if (showCredentials) showCredentials = false else onBack() },
                actions = {
                    val detail = uiState.detail
                    val spec = detail?.let {
                        providerConfigSpec(it.id, it.source)
                    }
                    val oauthRequired = spec?.isOAuth == true ||
                        detail?.authType.equals("oauthDeviceFlow", ignoreCase = true)
                    val editable = spec != null && spec.showConfig && !oauthRequired
                    if (showCredentials && editable) {
                        TextButton(
                            onClick = { viewModel.saveFromDraft() },
                            enabled = !uiState.saving,
                        ) {
                            if (uiState.saving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.provider_detail_save))
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            uiState.loading && uiState.detail == null ->
                SkeletonList(modifier = Modifier.fillMaxSize().padding(padding))
            uiState.error != null && uiState.detail == null ->
                ErrorBox(
                    text = uiState.error!!.resolve(),
                    onRetry = { viewModel.load() },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            uiState.detail != null ->
                ProviderDetailBody(
                    detail = uiState.detail!!,
                    providerName = providerName,
                    checking = uiState.checking,
                    checkResult = uiState.checkResult,
                    checkModelOverride = uiState.checkModelOverride,
                    fetching = uiState.fetching,
                    remoteModels = uiState.remoteModels,
                    fetchedCount = uiState.remoteModels?.size,
                    showCredentials = showCredentials,
                    draftVaults = uiState.draftVaults,
                    draftAuthMode = uiState.draftAuthMode,
                    onOpenCredentials = { showCredentials = true },
                    onDraftChange = viewModel::updateDraft,
                    onAuthModeChange = viewModel::updateAuthMode,
                    onCheck = { viewModel.check() },
                    onFetch = { viewModel.fetchRemoteModels() },
                    onModelsClick = {
                        val d = uiState.detail
                        if (d != null) onModelsClick(d.id, d.name ?: providerName)
                    },
                    onPickCheckModel = onPickCheckModel,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
        }
    }
}

@Composable
private fun ProviderDetailBody(
    detail: ProviderDetail,
    providerName: String? = null,
    checking: Boolean,
    checkResult: CheckResult?,
    checkModelOverride: String?,
    fetching: Boolean,
    remoteModels: List<String>?,
    fetchedCount: Int?,
    showCredentials: Boolean,
    draftVaults: Map<String, String>,
    draftAuthMode: String?,
    onOpenCredentials: () -> Unit,
    onDraftChange: (key: String, value: String) -> Unit,
    onAuthModeChange: (String) -> Unit,
    onCheck: () -> Unit,
    onFetch: () -> Unit,
    onModelsClick: () -> Unit = {},
    onPickCheckModel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val spec = remember(detail.id, detail.source) {
        providerConfigSpec(detail.id, detail.source)
    }
    val uriHandler = LocalUriHandler.current
    val showConnectivity = detail.chatSupported && detail.showChecker && spec.showChecker
    val showModelFetcher = detail.chatSupported && detail.showModelFetcher
    fun vaultValue(key: String): String =
        draftVaults[key] ?: detail.keyVaults[key].orEmpty()
    val configured = vaultValue(VAULT_API_KEY).isNotBlank() ||
        vaultValue(VAULT_BASE_URL).isNotBlank() ||
        detail.keyVaults.any { (k, v) ->
            k != VAULT_API_KEY && k != VAULT_BASE_URL && v.isNotBlank()
        }
    val checkModel = checkModelOverride?.takeIf { it.isNotBlank() }
        ?: detail.checkModel?.takeIf { it.isNotBlank() }
        ?: spec.checkModel?.takeIf { it.isNotBlank() }
    if (!showCredentials) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = spacing.s),
    ) {
        item(key = "basic-title") {
            SectionTitle(text = stringResource(R.string.provider_detail_section_basic))
        }
        item(key = "basic-card") {
            Column {
                ListItem(
                    headlineContent = { Text(detail.name?.takeIf { it.isNotBlank() } ?: detail.id) },
                    supportingContent = {
                        Column {
                            Text(detail.id)
                            detail.description?.takeIf(String::isNotBlank)?.let {
                                Text(
                                    text = it,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!detail.chatSupported) {
                                Text(
                                    text = stringResource(R.string.provider_detail_chat_unsupported),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        ProviderIcon(providerId = detail.id, name = detail.name ?: detail.id, logoUrl = detail.logo, size = 40.dp)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                HorizontalDivider()
                SettingRow(
                    icon = Icons.Filled.SmartToy,
                    title = stringResource(R.string.provider_detail_models),
                    subtitle = if (detail.modelCount > 0) {
                        stringResource(R.string.provider_detail_models_count, detail.modelCount)
                    } else {
                        stringResource(R.string.provider_detail_models_desc)
                    },
                    onClick = onModelsClick,
                )
                detail.websiteUrl?.takeIf(String::isNotBlank)?.let { url ->
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.provider_detail_website),
                        subtitle = url,
                        onClick = { runCatching { uriHandler.openUri(url) } },
                    )
                }
                detail.apiKeyUrl?.takeIf(String::isNotBlank)?.let { url ->
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Filled.Key,
                        title = stringResource(R.string.provider_detail_api_key_url),
                        subtitle = url,
                        onClick = { runCatching { uriHandler.openUri(url) } },
                    )
                }
                detail.modelsUrl?.takeIf(String::isNotBlank)?.let { url ->
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.provider_detail_models_url),
                        subtitle = url,
                        onClick = { runCatching { uriHandler.openUri(url) } },
                    )
                }
        }
        }
        if (spec.showConfig) {
            item(key = "credentials-title") {
                SectionTitle(text = stringResource(R.string.provider_detail_section_credentials))
            }
            item(key = "credentials-row") {
                SettingRow(
                    icon = Icons.Filled.Key,
                    title = stringResource(R.string.provider_detail_section_credentials),
                    subtitle = if (spec.isOAuth || detail.authType.equals("oauthDeviceFlow", ignoreCase = true)) {
                        stringResource(R.string.provider_oauth_description, detail.name ?: detail.id)
                    } else if (configured) {
                        stringResource(R.string.provider_detail_credentials_set)
                    } else {
                        stringResource(R.string.provider_detail_credentials_unset)
                    },
                    onClick = onOpenCredentials,
                )
            }
            if (showConnectivity || showModelFetcher) {
                item(key = "connectivity-title") {
                    SectionTitle(text = stringResource(R.string.provider_detail_section_connectivity))
                }
                item(key = "connectivity-card") {
                    Column {
                        if (showConnectivity) {
                            SettingRow(
                                icon = Icons.Filled.Psychology,
                                title = stringResource(R.string.provider_detail_check_model),
                                subtitle = checkModel ?: stringResource(R.string.provider_detail_no_check_model),
                                onClick = onPickCheckModel,
                            )
                            HorizontalDivider()
                            SettingRow(
                                icon = Icons.Filled.NetworkCheck,
                                title = stringResource(R.string.provider_detail_check),
                                subtitle = when {
                                    checking -> stringResource(R.string.provider_detail_checking)
                                    checkResult == null -> null
                                    checkResult.ok -> stringResource(R.string.provider_detail_check_ok)
                                    else -> checkResult.error?.takeIf(String::isNotBlank)
                                        ?: stringResource(R.string.provider_detail_check_failed)
                                },
                                onClick = onCheck,
                                trailing = {
                                    when {
                                        checking -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        checkResult == null -> null
                                        checkResult.ok -> Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        else -> Icon(
                                            imageVector = Icons.Filled.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                            if (showModelFetcher) HorizontalDivider()
                        }
                        if (showModelFetcher) {
                            SettingRow(
                                icon = Icons.Filled.CloudDownload,
                                title = stringResource(R.string.provider_detail_fetch),
                                subtitle = fetchedCount?.let {
                                    stringResource(R.string.provider_detail_remote_count, it)
                                },
                                onClick = onFetch,
                                trailing = {
                                    if (fetching) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (remoteModels != null && detail.chatSupported) {
                item(key = "remote-title") {
                    SectionTitle(text = stringResource(R.string.provider_detail_section_remote_models))
                }
                item(key = "remote-card") {
                    Surface(
                        color = MaterialTheme.inputRowContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.l, vertical = spacing.m),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            Text(
                                stringResource(R.string.provider_detail_remote_count, remoteModels.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            remoteModels.take(REMOTE_MODEL_PREVIEW_LIMIT).forEach { id ->
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                stringResource(R.string.provider_detail_remote_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        }
    } else {
        ProviderConfigForm(
            detail = detail,
            displayName = detail.name?.takeIf { it.isNotBlank() }
                ?: providerName?.takeIf { it.isNotBlank() }
                ?: detail.id,
            vaultValue = ::vaultValue,
            draftAuthMode = draftAuthMode,
            onDraftChange = onDraftChange,
            onAuthModeChange = onAuthModeChange,
            modifier = modifier,
        )
    }
}

private const val REMOTE_MODEL_PREVIEW_LIMIT = 50
