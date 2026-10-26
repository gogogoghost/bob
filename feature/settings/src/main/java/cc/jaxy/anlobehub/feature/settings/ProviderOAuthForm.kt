package cc.jaxy.anlobehub.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.data.provider.ProviderDeviceAuthorization
import cc.jaxy.anlobehub.core.data.provider.ProviderOAuthException
import cc.jaxy.anlobehub.core.data.provider.ProviderOAuthRepository
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
internal class ProviderOAuthViewModel @Inject constructor(private val repository: ProviderOAuthRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val connected: Boolean = false,
        val account: String? = null,
        val authorization: ProviderDeviceAuthorization? = null,
        val error: ProviderOAuthException.Reason? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var providerId = ""

    fun bind(id: String) {
        if (providerId == id && !mutableState.value.loading) return
        operation?.cancel()
        providerId = id
        mutableState.value = mutableState.value.copy(loading = true)
        operation = viewModelScope.launch {
            try {
                val connection = repository.connection(id)
                mutableState.value = State(loading = false, connected = connection.connected, account = connection.account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = State(loading = false, error = reason(e))
            }
        }
    }

    fun connect() {
        if (mutableState.value.loading || mutableState.value.busy) return
        operation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val authorization = repository.begin(providerId)
                mutableState.value = mutableState.value.copy(authorization = authorization)
                repository.awaitAuthorization(authorization)
                val connection = repository.connection(providerId)
                mutableState.value = State(loading = false, connected = connection.connected, account = connection.account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, authorization = null, error = reason(e))
            }
        }
    }

    fun cancel() {
        operation?.cancel()
        operation = null
        mutableState.value = mutableState.value.copy(busy = false, authorization = null)
    }

    fun cancelAndReload() {
        val id = providerId
        cancel()
        providerId = ""
        bind(id)
    }

    fun unbind() {
        cancel()
        providerId = ""
    }

    fun disconnect() {
        cancel()
        operation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                repository.disconnect(providerId)
                mutableState.value = State(loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = reason(e))
            }
        }
    }

    private fun reason(error: Exception): ProviderOAuthException.Reason =
        (error as? ProviderOAuthException)?.reason ?: ProviderOAuthException.Reason.REQUEST_FAILED
}

/** Device sign-in persists immediately; it does not share the API-key form's Save draft. */
@Composable
internal fun ProviderOAuthForm(providerId: String, displayName: String, modifier: Modifier = Modifier) {
    val viewModel: ProviderOAuthViewModel = hiltViewModel(key = "provider-oauth-$providerId")
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(providerId) { viewModel.bind(providerId) }
    DisposableEffect(viewModel) { onDispose { viewModel.unbind() } }
    val spacing = MaterialTheme.spacing
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var browserFailed by remember(providerId) { mutableStateOf(false) }
    var confirmDisconnect by remember(providerId) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Text(stringResource(R.string.provider_oauth_description, displayName), style = MaterialTheme.typography.bodyMedium)
        if (providerId == "supergrok") {
            Text(stringResource(R.string.provider_oauth_supergrok_entitlement), style = MaterialTheme.typography.bodySmall)
        }
        if (state.loading) {
            CircularProgressIndicator()
        } else {
            Text(
                stringResource(if (state.connected) R.string.provider_oauth_connected else R.string.provider_oauth_disconnected),
                style = MaterialTheme.typography.titleMedium,
            )
            state.account?.let { account ->
                SelectionContainer { Text(stringResource(R.string.provider_oauth_account, account)) }
            }
            val authorization = state.authorization
            if (authorization != null) {
                Text(stringResource(R.string.provider_oauth_instruction), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                        Text(authorization.userCode, style = MaterialTheme.typography.headlineMedium)
                        Text(authorization.verificationUri, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(authorization.userCode)) }) {
                    Text(stringResource(R.string.provider_oauth_copy_code))
                }
                Button(onClick = {
                    browserFailed = false
                    try {
                        uriHandler.openUri(authorization.verificationUriComplete ?: authorization.verificationUri)
                    } catch (_: Exception) {
                        browserFailed = true
                    }
                }) { Text(stringResource(R.string.provider_oauth_open_browser)) }
                Text(
                    stringResource(R.string.provider_oauth_expires, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(authorization.expiresAt))),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (browserFailed) {
                    Text(stringResource(R.string.provider_oauth_browser_failed), color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.busy) {
                CircularProgressIndicator()
                Text(stringResource(if (authorization == null) R.string.provider_oauth_working else R.string.provider_oauth_waiting))
                TextButton(onClick = viewModel::cancelAndReload) { Text(stringResource(R.string.provider_oauth_cancel)) }
            } else if (state.connected) {
                OutlinedButton(onClick = { confirmDisconnect = true }) { Text(stringResource(R.string.provider_oauth_disconnect)) }
            } else {
                Button(onClick = { browserFailed = false; viewModel.connect() }) {
                    Text(stringResource(R.string.provider_oauth_connect, displayName))
                }
            }
            state.error?.let { error ->
                val message = when (error) {
                    ProviderOAuthException.Reason.NOT_CONNECTED -> R.string.provider_oauth_error_not_connected
                    ProviderOAuthException.Reason.EXPIRED -> R.string.provider_oauth_error_expired
                    ProviderOAuthException.Reason.DENIED -> R.string.provider_oauth_error_denied
                    ProviderOAuthException.Reason.RECONNECT -> R.string.provider_oauth_error_reconnect
                    ProviderOAuthException.Reason.SUBSCRIPTION_REQUIRED -> R.string.provider_oauth_error_subscription
                    ProviderOAuthException.Reason.INVALID_RESPONSE -> R.string.provider_oauth_error_response
                    ProviderOAuthException.Reason.REQUEST_FAILED -> R.string.provider_oauth_error_request
                }
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            }
        }
        Text(stringResource(R.string.provider_oauth_immediate_save), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.provider_cfg_aesgcm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.provider_oauth_disconnect)) },
            text = { Text(stringResource(R.string.provider_oauth_disconnect_description)) },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; viewModel.disconnect() }) {
                    Text(stringResource(R.string.provider_oauth_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.provider_oauth_cancel)) }
            },
        )
    }
}
