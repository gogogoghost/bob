package cc.jaxy.anlobehub.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import cc.jaxy.anlobehub.core.common.util.ApiKeyField
import cc.jaxy.anlobehub.core.common.util.EndpointField
import cc.jaxy.anlobehub.core.common.util.FieldType
import cc.jaxy.anlobehub.core.common.util.ProviderConfigSpec
import cc.jaxy.anlobehub.core.common.util.SpecialAuthMode
import cc.jaxy.anlobehub.core.common.util.SpecialField
import cc.jaxy.anlobehub.core.common.util.SpecialFields
import cc.jaxy.anlobehub.core.common.util.VAULT_API_KEY
import cc.jaxy.anlobehub.core.common.util.VAULT_BASE_URL
import cc.jaxy.anlobehub.core.common.util.inferBedrockAuthMode
import cc.jaxy.anlobehub.core.common.util.providerConfigSpec
import cc.jaxy.anlobehub.core.common.util.specialFieldsFor
import cc.jaxy.anlobehub.core.data.provider.ProviderDetail
import cc.jaxy.anlobehub.core.designsystem.component.AnTextField
import cc.jaxy.anlobehub.core.designsystem.component.PasswordField
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import cc.jaxy.anlobehub.core.designsystem.R as DsR

/** Provider-specific local credential form; secret values are protected by Android Keystore. */
@Composable
fun ProviderConfigForm(
    detail: ProviderDetail,
    displayName: String,
    vaultValue: (String) -> String,
    draftAuthMode: String?,
    onDraftChange: (key: String, value: String) -> Unit,
    onAuthModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = remember(detail.id, detail.source) {
        providerConfigSpec(detail.id, detail.source)
    }
    val special = remember(detail.id) { specialFieldsFor(detail.id) }
    val endpoint = remember(spec.endpoint, detail.endpointPlaceholder) {
        val placeholder = detail.endpointPlaceholder?.takeIf(String::isNotBlank)
        if (placeholder == null) spec.endpoint
        else (spec.endpoint ?: EndpointField(placeholder)).copy(placeholder = placeholder)
    }
    val showApiKey = detail.showApiKey ?: spec.showApiKey
    val oauthRequired = spec.isOAuth || detail.authType.equals("oauthDeviceFlow", ignoreCase = true)
    val spacing = MaterialTheme.spacing

    if (!spec.showConfig) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(spacing.l),
        ) {
            Text(
                text = stringResource(R.string.provider_cfg_noconfig),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (oauthRequired) {
        ProviderOAuthForm(
            providerId = detail.id,
            displayName = displayName,
            modifier = modifier,
        )
        return
    }

    // Effective (draft > stored) values for visibility rules.
    fun effective(key: String): String = vaultValue(key)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (special?.replacesApiKey == true) {
            SpecialFieldsBlock(
                detail = detail,
                special = special,
                spec = spec,
                effective = ::effective,
                draftAuthMode = draftAuthMode,
                onDraftChange = onDraftChange,
                onAuthModeChange = onAuthModeChange,
            )
        } else {
            if (showApiKey && spec.apiKey == ApiKeyField.SHOW_DEFAULT) {
                PasswordField(
                    value = effective(VAULT_API_KEY),
                    onValueChange = { onDraftChange(VAULT_API_KEY, it) },
                    label = stringResource(R.string.provider_detail_apikey),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.provider_cfg_apikey_desc,
                                displayName,
                            ),
                        )
                    },
                )
            }
            endpoint?.let { endpointField ->
                EndpointInput(
                    detailId = detail.id,
                    value = effective(VAULT_BASE_URL),
                    endpoint = endpointField,
                    onValueChange = { onDraftChange(VAULT_BASE_URL, it) },
                )
            }
            special?.apiKeyItems.orEmpty().forEach { field ->
                // Non-replacing specials only add trailing fields (none today,
                // but keeps the contract open for future cards).
                VaultFieldInput(
                    field = field,
                    value = effective(field.key),
                    onValueChange = { onDraftChange(field.key, it) },
                )
            }
        }

        Text(
            text = stringResource(R.string.provider_cfg_aesgcm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpecialFieldsBlock(
    detail: ProviderDetail,
    special: SpecialFields,
    spec: ProviderConfigSpec,
    effective: (String) -> String,
    draftAuthMode: String?,
    onDraftChange: (key: String, value: String) -> Unit,
    onAuthModeChange: (String) -> Unit,
) {
    // comfyui-style: base fields always render, mode-gated fields below.
    val alwaysFields: List<SpecialField>
    val modes: List<SpecialAuthMode>
    if (detail.id.equals("comfyui", ignoreCase = true)) {
        alwaysFields = special.apiKeyItems
        modes = special.authModes
    } else if (detail.id.equals("bedrock", ignoreCase = true)) {
        alwaysFields = special.apiKeyItems
        modes = special.authModes
    } else {
        alwaysFields = special.apiKeyItems
        modes = emptyList()
    }
    // bedrock: region renders after the auth-mode fields on web; comfyui:
    // baseURL + auth selector first. Emit mode selector + fields first for
    // bedrock, base fields first for comfyui.
    if (detail.id.equals("bedrock", ignoreCase = true)) {
        BedrockAuthBlock(
            modes = modes,
            effective = effective,
            draftAuthMode = draftAuthMode,
            onDraftChange = onDraftChange,
            onAuthModeChange = onAuthModeChange,
        )
        alwaysFields.forEach { field ->
            VaultFieldInput(
                field = field,
                value = effective(field.key),
                onValueChange = { onDraftChange(field.key, it) },
            )
        }
    } else if (modes.isNotEmpty()) {
        alwaysFields.forEach { field ->
            VaultFieldInput(
                field = field,
                value = effective(field.key),
                onValueChange = { onDraftChange(field.key, it) },
            )
        }
        ComfyAuthBlock(
            modes = modes,
            effective = effective,
            onDraftChange = onDraftChange,
        )
    } else {
        val specEndpoint = spec.endpoint
        alwaysFields.forEach { field ->
            if (field.key == VAULT_BASE_URL && specEndpoint != null) {
                EndpointInput(
                    detailId = detail.id,
                    value = effective(field.key),
                    endpoint = specEndpoint,
                    labelOverride = specialString(field.labelKey),
                    descOverride = specialString(field.descKey),
                    onValueChange = { onDraftChange(field.key, it) },
                )
            } else {
                VaultFieldInput(
                    field = field,
                    value = effective(field.key),
                    onValueChange = { onDraftChange(field.key, it) },
                )
            }
        }
        // Generic endpoint for specials that only replace the key (none
        // today: azure carries its endpoint inside apiKeyItems).
        if (alwaysFields.none { it.key == VAULT_BASE_URL }) {
            spec.endpoint?.let { endpoint ->
                EndpointInput(
                    detailId = detail.id,
                    value = effective(VAULT_BASE_URL),
                    endpoint = endpoint,
                    onValueChange = { onDraftChange(VAULT_BASE_URL, it) },
                )
            }
        }
    }
}

@Composable
private fun BedrockAuthBlock(
    modes: List<SpecialAuthMode>,
    effective: (String) -> String,
    draftAuthMode: String?,
    onDraftChange: (key: String, value: String) -> Unit,
    onAuthModeChange: (String) -> Unit,
) {
    val stored = remember(effective) {
        inferBedrockAuthMode(
            mapOf(
                "accessKeyId" to effective("accessKeyId"),
                "secretAccessKey" to effective("secretAccessKey"),
                VAULT_API_KEY to effective(VAULT_API_KEY),
            ),
        )
    }
    val active = draftAuthMode ?: stored
    Text(
        text = stringResource(R.string.provider_cfg_bedrock_mode_title),
        style = MaterialTheme.typography.titleSmall,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = active == mode.id,
                onClick = { onAuthModeChange(mode.id) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) {
                Text(
                    when (mode.id) {
                        "apiKey" -> stringResource(R.string.provider_cfg_bedrock_mode_apikey)
                        else -> stringResource(R.string.provider_cfg_bedrock_mode_aws)
                    },
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.provider_cfg_bedrock_mode_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    modes.firstOrNull { it.id == active }?.fields.orEmpty().forEach { field ->
        VaultFieldInput(
            field = field,
            value = effective(field.key),
            onValueChange = { onDraftChange(field.key, it) },
        )
    }
}

@Composable
private fun ComfyAuthBlock(
    modes: List<SpecialAuthMode>,
    effective: (String) -> String,
    onDraftChange: (key: String, value: String) -> Unit,
) {
    val active = effective("authType").ifBlank { "none" }
    val noneLabel = stringResource(R.string.provider_cfg_comfy_auth_none)
    val basicLabel = stringResource(R.string.provider_cfg_comfy_auth_basic)
    val bearerLabel = stringResource(R.string.provider_cfg_comfy_auth_bearer)
    val customLabel = stringResource(R.string.provider_cfg_comfy_auth_custom)
    VaultSelectInput(
        label = stringResource(R.string.provider_cfg_comfy_auth_title),
        desc = stringResource(R.string.provider_cfg_comfy_auth_desc),
        placeholder = stringResource(R.string.provider_cfg_comfy_auth_hint),
        value = active,
        options = modes.map { it.id },
        optionLabel = { id ->
            when (id) {
                "none" -> noneLabel
                "basic" -> basicLabel
                "bearer" -> bearerLabel
                else -> customLabel
            }
        },
        onSelect = { onDraftChange("authType", it) },
    )
    modes.firstOrNull { it.id == active }?.fields.orEmpty().forEach { field ->
        VaultFieldInput(
            field = field,
            value = effective(field.key),
            onValueChange = { onDraftChange(field.key, it) },
        )
    }
    if (active == "custom") {
        Text(
            text = stringResource(R.string.provider_cfg_comfy_custom_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EndpointInput(
    detailId: String,
    value: String,
    endpoint: EndpointField,
    onValueChange: (String) -> Unit,
    labelOverride: String? = null,
    descOverride: String? = null,
    modifier: Modifier = Modifier,
) {
    val (title, desc) = when {
        labelOverride != null -> labelOverride to (descOverride.orEmpty())
        detailId.equals("ollama", ignoreCase = true) ->
            stringResource(R.string.provider_cfg_ollama_endpoint_title) to
                stringResource(R.string.provider_cfg_ollama_endpoint_desc)
        detailId.equals("newapi", ignoreCase = true) ->
            stringResource(R.string.provider_cfg_newapi_endpoint_title) to
                stringResource(R.string.provider_cfg_newapi_endpoint_desc)
        else ->
            stringResource(R.string.provider_cfg_baseurl_title) to
                stringResource(R.string.provider_cfg_baseurl_desc)
    }
    AnTextField(
        value = value,
        onValueChange = onValueChange,
        label = title,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(endpoint.placeholder) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(DsR.string.common_clear),
                    )
                }
            }
        },
        supportingText = {
            Text(
                if (desc.isNotBlank()) {
                    "$desc\n" + stringResource(
                        R.string.provider_detail_baseurl_default,
                        endpoint.placeholder,
                    )
                } else {
                    stringResource(
                        R.string.provider_detail_baseurl_default,
                        endpoint.placeholder,
                    )
                },
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
private fun VaultFieldInput(
    field: SpecialField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (field.type) {
        FieldType.SECRET -> PasswordField(
            value = value,
            onValueChange = onValueChange,
            label = specialString(field.labelKey),
            modifier = modifier.fillMaxWidth(),
            supportingText = {
                val desc = specialString(field.descKey)
                if (desc.isNotBlank()) Text(desc)
            },
        )
        FieldType.TEXT -> AnTextField(
            value = value,
            onValueChange = onValueChange,
            label = specialString(field.labelKey),
            modifier = modifier.fillMaxWidth(),
            placeholder = {
                val hint = specialString(field.placeholderKey)
                if (hint.isNotBlank()) Text(hint)
            },
            trailingIcon = {
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(DsR.string.common_clear),
                        )
                    }
                }
            },
            supportingText = {
                val desc = specialString(field.descKey)
                if (desc.isNotBlank()) Text(desc)
            },
        )
        FieldType.SELECT -> VaultSelectInput(
            label = specialString(field.labelKey),
            desc = specialString(field.descKey),
            placeholder = specialString(field.placeholderKey),
            value = value,
            options = field.options,
            optionLabel = { it },
            onSelect = onValueChange,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultSelectInput(
    label: String,
    desc: String,
    placeholder: String,
    value: String,
    options: List<String>,
    optionLabel: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        AnTextField(
            value = if (value.isBlank()) "" else optionLabel(value),
            onValueChange = {},
            label = label,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            supportingText = { if (desc.isNotBlank()) Text(desc) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}


/** Resolve a `SpecialField` label/placeholder/desc key to localized text. */
@Composable
private fun specialString(key: String): String = when (key) {
    "azure.token.title" -> stringResource(R.string.provider_cfg_azure_token_title)
    "azure.token.placeholder" -> stringResource(R.string.provider_cfg_azure_token_hint)
    "azure.token.desc" -> stringResource(R.string.provider_cfg_azure_token_desc)
    "azure.endpoint.title" -> stringResource(R.string.provider_cfg_azure_endpoint_title)
    "azure.endpoint.placeholder" -> stringResource(R.string.provider_cfg_azure_endpoint_hint)
    "azure.endpoint.desc" -> stringResource(R.string.provider_cfg_azure_endpoint_desc)
    "azureai.token.title" -> stringResource(R.string.provider_cfg_azureai_token_title)
    "azureai.token.placeholder" -> stringResource(R.string.provider_cfg_azureai_token_hint)
    "azureai.token.desc" -> stringResource(R.string.provider_cfg_azureai_token_desc)
    "azureai.endpoint.title" -> stringResource(R.string.provider_cfg_azureai_endpoint_title)
    "azureai.endpoint.placeholder" -> stringResource(R.string.provider_cfg_azureai_endpoint_hint)
    "azureai.endpoint.desc" -> stringResource(R.string.provider_cfg_azureai_endpoint_desc)
    "bedrock.apiKey.title" -> stringResource(R.string.provider_cfg_bedrock_apikey_title)
    "bedrock.apiKey.placeholder" -> stringResource(R.string.provider_cfg_bedrock_apikey_hint)
    "bedrock.apiKey.desc" -> stringResource(R.string.provider_cfg_bedrock_apikey_desc)
    "bedrock.accessKeyId.title" -> stringResource(R.string.provider_cfg_bedrock_aki_title)
    "bedrock.accessKeyId.placeholder" -> stringResource(R.string.provider_cfg_bedrock_aki_hint)
    "bedrock.accessKeyId.desc" -> stringResource(R.string.provider_cfg_bedrock_aki_desc)
    "bedrock.secretAccessKey.title" -> stringResource(R.string.provider_cfg_bedrock_sak_title)
    "bedrock.secretAccessKey.placeholder" -> stringResource(R.string.provider_cfg_bedrock_sak_hint)
    "bedrock.secretAccessKey.desc" -> stringResource(R.string.provider_cfg_bedrock_sak_desc)
    "bedrock.sessionToken.title" -> stringResource(R.string.provider_cfg_bedrock_st_title)
    "bedrock.sessionToken.placeholder" -> stringResource(R.string.provider_cfg_bedrock_st_hint)
    "bedrock.sessionToken.desc" -> stringResource(R.string.provider_cfg_bedrock_st_desc)
    "bedrock.region.title" -> stringResource(R.string.provider_cfg_bedrock_region_title)
    "bedrock.region.placeholder" -> stringResource(R.string.provider_cfg_bedrock_region_hint)
    "bedrock.region.desc" -> stringResource(R.string.provider_cfg_bedrock_region_desc)
    "cloudflare.apiKey.title" -> stringResource(R.string.provider_cfg_cf_key_title)
    "cloudflare.apiKey.placeholder" -> stringResource(R.string.provider_cfg_cf_key_hint)
    "cloudflare.apiKey.desc" -> stringResource(R.string.provider_cfg_cf_key_desc)
    "cloudflare.baseURLOrAccountID.title" -> stringResource(R.string.provider_cfg_cf_acct_title)
    "cloudflare.baseURLOrAccountID.placeholder" -> stringResource(R.string.provider_cfg_cf_acct_hint)
    "cloudflare.baseURLOrAccountID.desc" -> stringResource(R.string.provider_cfg_cf_acct_desc)
    "comfyui.baseURL.title" -> stringResource(R.string.provider_cfg_comfy_url_title)
    "comfyui.baseURL.placeholder" -> stringResource(R.string.provider_cfg_comfy_url_hint)
    "comfyui.baseURL.desc" -> stringResource(R.string.provider_cfg_comfy_url_desc)
    "comfyui.username.title" -> stringResource(R.string.provider_cfg_comfy_user_title)
    "comfyui.username.placeholder" -> stringResource(R.string.provider_cfg_comfy_user_hint)
    "comfyui.username.desc" -> stringResource(R.string.provider_cfg_comfy_user_desc)
    "comfyui.password.title" -> stringResource(R.string.provider_cfg_comfy_pass_title)
    "comfyui.password.placeholder" -> stringResource(R.string.provider_cfg_comfy_pass_hint)
    "comfyui.password.desc" -> stringResource(R.string.provider_cfg_comfy_pass_desc)
    "comfyui.apiKey.title" -> stringResource(R.string.provider_cfg_comfy_key_title)
    "comfyui.apiKey.placeholder" -> stringResource(R.string.provider_cfg_comfy_key_hint)
    "comfyui.apiKey.desc" -> stringResource(R.string.provider_cfg_comfy_key_desc)
    "github.personalAccessToken.title" -> stringResource(R.string.provider_cfg_github_pat_title)
    "github.personalAccessToken.placeholder" -> stringResource(R.string.provider_cfg_github_pat_hint)
    "github.personalAccessToken.desc" -> stringResource(R.string.provider_cfg_github_pat_desc)
    "vertexai.apiKey.title" -> stringResource(R.string.provider_cfg_vertex_key_title)
    "vertexai.apiKey.placeholder" -> stringResource(R.string.provider_cfg_vertex_key_hint)
    "vertexai.apiKey.desc" -> stringResource(R.string.provider_cfg_vertex_key_desc)
    "vertexai.region.title" -> stringResource(R.string.provider_cfg_vertex_region_title)
    "vertexai.region.placeholder" -> stringResource(R.string.provider_cfg_vertex_region_hint)
    "vertexai.region.desc" -> stringResource(R.string.provider_cfg_vertex_region_desc)
    else -> ""
}
