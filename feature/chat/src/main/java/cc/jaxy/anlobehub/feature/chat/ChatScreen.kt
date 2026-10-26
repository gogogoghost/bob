package cc.jaxy.anlobehub.feature.chat

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.core.data.chat.ChatMessage
import cc.jaxy.anlobehub.core.data.chat.ChatTopic
import cc.jaxy.anlobehub.core.data.chat.WebCitation
import cc.jaxy.anlobehub.core.data.chat.WebSearchActivity
import cc.jaxy.anlobehub.core.data.chat.WebSearchStage
import cc.jaxy.anlobehub.core.data.files.UploadedFile
import cc.jaxy.anlobehub.core.data.search.WebSearchMode
import cc.jaxy.anlobehub.core.data.search.WebSearchPolicy
import cc.jaxy.anlobehub.core.designsystem.component.AnTextField
import cc.jaxy.anlobehub.core.designsystem.component.AnTopBar
import cc.jaxy.anlobehub.core.designsystem.component.EmptyBox
import cc.jaxy.anlobehub.core.designsystem.component.InitialAvatar
import cc.jaxy.anlobehub.core.designsystem.component.MarkdownText
import cc.jaxy.anlobehub.core.designsystem.component.RefreshBox
import cc.jaxy.anlobehub.core.designsystem.component.SkeletonList
import cc.jaxy.anlobehub.core.designsystem.component.shimmer
import cc.jaxy.anlobehub.core.designsystem.component.relativeTimeText
import cc.jaxy.anlobehub.feature.models.ModelDetailSheet
import cc.jaxy.anlobehub.feature.models.ReasoningUiState
import cc.jaxy.anlobehub.core.designsystem.text.resolve
import cc.jaxy.anlobehub.core.designsystem.theme.AnlobehubTheme
import cc.jaxy.anlobehub.core.designsystem.theme.hapticTick
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ATTACH_BYTES = 10 * 1024 * 1024

@Composable
private fun ScrollToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.padding(MaterialTheme.spacing.l),
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.chat_scroll_to_bottom),
        )
    }
}

@Composable
fun ChatScreen(
    onOpenModelPicker: (current: AIModel?) -> Unit = {},
    onOpenHistory: (agentId: String, activeTopicId: String?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    ChatScreenWired(
        onOpenModelPicker = onOpenModelPicker,
        onOpenHistory = onOpenHistory,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenWired(
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenModelPicker: (current: AIModel?) -> Unit = {},
    onOpenHistory: (agentId: String, activeTopicId: String?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Recreated per topic AND per load-generation: empty->loaded flips the
    // key, rebuilding state with the newest turn as the landing index so the
    // first frame already sits at the tail (no mid-history flash, no jump to
    // the last prompt). Streaming appends don't touch the key, preserving
    // scroll position.
    val loadedGen = if (uiState.messages.isEmpty()) 0 else 1
    val landingIndex = remember(uiState.activeTopicId, loadedGen) {
        if (uiState.messages.isEmpty()) 0 else uiState.messages.toTurns().lastIndex
    }
    val listState = androidx.compose.runtime.key(uiState.activeTopicId, loadedGen) {
        rememberLazyListState(initialFirstVisibleItemIndex = landingIndex)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // History opens at the tail, so every conversation starts pinned.
    val tail = rememberTailFollow(listState)

    val error = uiState.error
    val errorText = error?.resolve()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.clearError()
        }
    }

    // Item count is the LazyColumn's real content count (folding turns), not
    // messages.size — scroll targets index the visible list.
    val turnsCount = remember(uiState.messages) { uiState.messages.toTurns().size }
    val totalItems = turnsCount + if (uiState.streaming) 1 else 0
    // Follow the tail while pinned: token ticks, the send landing, and the
    // streaming->settled transition all funnel through here. Instant snap per
    // tick: restarting an animation at ~12fps never settles, and its
    // mid-flight offsets are wrong to branch on.
    LaunchedEffect(
        totalItems,
        uiState.streamingText,
        uiState.streamingReasoning,
        uiState.streaming,
        uiState.streamingSearchActivity,
        uiState.streamingCitations,
    ) {
        if (totalItems <= 0 || !tail.pinned) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect // gesture in flight
        tail.follow(animate = false)
    }

    val attachReadFailed = stringResource(R.string.chat_attach_read_failed)
    val attachTooLarge = stringResource(R.string.chat_attach_too_large_format, 10)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                snackbar.showSnackbar(attachReadFailed)
                return@launch
            }
            if (bytes.size > MAX_ATTACH_BYTES) {
                snackbar.showSnackbar(attachTooLarge)
                return@launch
            }
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "image/*"
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                }
            }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "image"
            viewModel.attach(bytes, name, mime)
        }
    }

    ChatContent(
        onBack = onBack,
        title = viewModel.agentTitle?.takeIf { it.isNotBlank() },
        messages = uiState.messages,
        messagesLoading = uiState.messagesLoading,
        streaming = uiState.streaming,
        streamingText = uiState.streamingText,
        streamingReasoning = uiState.streamingReasoning,
        streamingReasoningSecs = uiState.streamingReasoningSecs,
        streamingSearchActivity = uiState.streamingSearchActivity,
        streamingCitations = uiState.streamingCitations,
        webSearchPolicy = uiState.webSearchPolicy,
        webSearchAvailable = uiState.webSearchAvailable,
        webSearchLoading = uiState.webSearchLoading,
        webSearchReady = uiState.webSearchReady,
        webSearchInherited = uiState.webSearchOverride == null,
        webSearchProviderName = uiState.webSearchProviderName,
        topics = uiState.topics,
        topicsLoading = uiState.topicsLoading,
        activeTopicId = uiState.activeTopicId,
        models = uiState.models,
        activeModel = uiState.activeModel,
        modelsLoading = uiState.modelsLoading,
        agentAvatar = uiState.agentAvatar,
        openingMessage = uiState.openingMessage,
        openingQuestions = uiState.openingQuestions,
        reasoningConfig = uiState.reasoningConfig,
        reasoningLoading = uiState.reasoningLoading,
        reasoningSaving = uiState.reasoningSaving,
        onLoadReasoning = viewModel::loadReasoning,
        onSaveReasoning = viewModel::saveReasoning,
        pendingFiles = uiState.pendingFiles,
        uploading = uiState.uploading,
        listState = listState,
        snackbar = snackbar,
        onNewConversation = viewModel::newConversation,
        onOpenModelPicker = { onOpenModelPicker(uiState.activeModel) },
        onSend = { text, fileIds ->
            // Sending re-pins to the tail even from deep history: ChatGPT/
            // Gemini never leave your own fresh message off-screen.
            tail.pin()
            viewModel.send(text, fileIds)
        },
        onInputFocused = { tail.pin() },
        atBottom = tail.pinned,
        onScrollToBottom = {
            scope.launch {
                tail.pin()
                tail.follow(animate = true)
            }
        },
        onStop = viewModel::stop,
        onRetry = viewModel::retry,
        onAttachClick = { pickImage.launch("image/*") },
        onRemoveFile = viewModel::removePendingFile,
        onRefresh = viewModel::load,
        onOpenHistory = {
            onOpenHistory(viewModel.agentId, viewModel.uiState.value.activeTopicId)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    messages: List<ChatMessage>,
    messagesLoading: Boolean = false,
    streaming: Boolean,
    streamingText: String,
    streamingReasoning: String = "",
    streamingReasoningSecs: Double? = null,
    streamingSearchActivity: WebSearchActivity? = null,
    streamingCitations: List<WebCitation> = emptyList(),
    webSearchPolicy: WebSearchPolicy = WebSearchPolicy(),
    webSearchAvailable: Boolean = false,
    webSearchLoading: Boolean = false,
    webSearchReady: Boolean = true,
    webSearchInherited: Boolean = true,
    webSearchProviderName: String? = null,
    topics: List<ChatTopic>,
    topicsLoading: Boolean = false,
    activeTopicId: String?,
    models: List<AIModel>,
    activeModel: AIModel?,
    modelsLoading: Boolean = false,
    agentAvatar: String? = null,
    openingMessage: String? = null,
    openingQuestions: List<String> = emptyList(),
    reasoningConfig: cc.jaxy.anlobehub.core.data.chat.ReasoningConfig? = null,
    reasoningLoading: Boolean = false,
    reasoningSaving: Boolean = false,
    onLoadReasoning: (cc.jaxy.anlobehub.core.data.chat.AIModel) -> Unit = {},
    onSaveReasoning: (cc.jaxy.anlobehub.core.data.chat.AIModel, String, String?) -> Unit = { _, _, _ -> },
    pendingFiles: List<UploadedFile>,
    uploading: Boolean,
    title: String? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    atBottom: Boolean = true,
    onScrollToBottom: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onOpenHistory: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onOpenModelPicker: () -> Unit = {},
    onSend: (String, List<String>) -> Unit = { _, _ -> },
    onInputFocused: () -> Unit = {},
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAttachClick: () -> Unit = {},
    onRemoveFile: (UploadedFile) -> Unit = {},
    onRefresh: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    var input by rememberSaveable { mutableStateOf("") }

    var showModelDetail by remember { mutableStateOf(false) }
    val barTitle = title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_default_title)
    val searchAllowsSend = !webSearchLoading && webSearchReady && (webSearchPolicy.mode == WebSearchMode.OFF || webSearchAvailable)
    val sendEnabled = input.isNotBlank() && !streaming && searchAllowsSend

    fun doSend() {
        val text = input.trim()
        // OFF needs no search route: never trap a plain message behind prefs loading.
        val gate = if (webSearchPolicy.mode == WebSearchMode.OFF) !streaming else !streaming && searchAllowsSend
        if (text.isBlank() || !gate) return
        onSend(text, pendingFiles.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } })
        input = ""
        hapticTick(view)
    }

    Scaffold(
        topBar = {
            AnTopBar(
                title = barTitle,
                onBack = onBack,
                actions = {
                    IconButton(onClick = onNewConversation) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.chat_topic_new_conversation),
                        )
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            imageVector = Icons.Filled.Forum,
                            contentDescription = stringResource(R.string.chat_agent_history),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(
                            horizontal = MaterialTheme.spacing.l,
                            vertical = MaterialTheme.spacing.s,
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (modelsLoading && activeModel == null) {
                            Box(
                                modifier = Modifier
                                    .size(width = 120.dp, height = 32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .shimmer(),
                            )
                        } else {
                            @OptIn(ExperimentalFoundationApi::class)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.weight(1f, fill = false).combinedClickable(
                                    onClick = onOpenModelPicker,
                                    onLongClick = { showModelDetail = true },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = MaterialTheme.spacing.m,
                                        vertical = MaterialTheme.spacing.s,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SmartToy,
                                        contentDescription = null,
                                        modifier = Modifier.size(MaterialTheme.spacing.l),
                                    )
                                    Text(
                                        activeModel?.displayName?.takeIf { it.isNotBlank() }
                                            ?: activeModel?.id?.takeIf { it.isNotBlank() }
                                            ?: stringResource(R.string.chat_default_model),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = when {
                            webSearchLoading -> stringResource(R.string.chat_web_search_loading)
                            !webSearchReady -> stringResource(R.string.chat_web_search_preferences_error)
                            !webSearchAvailable && webSearchPolicy.mode == WebSearchMode.NATIVE ->
                                stringResource(R.string.chat_web_search_native_unavailable)
                            !webSearchAvailable && webSearchPolicy.mode == WebSearchMode.PROVIDER ->
                                stringResource(R.string.chat_web_search_provider_unavailable)
                            else -> stringResource(R.string.chat_web_search_route_status,
                                when (webSearchPolicy.mode) {
                                    WebSearchMode.OFF -> stringResource(R.string.chat_web_search_off)
                                    WebSearchMode.NATIVE -> stringResource(R.string.chat_web_search_native)
                                    WebSearchMode.PROVIDER -> webSearchProviderName ?: webSearchPolicy.providerId.orEmpty()
                                },
                                stringResource(if (webSearchInherited) R.string.chat_web_search_source_global else R.string.chat_web_search_source_agent),
                            )
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pendingFiles.isNotEmpty() || uploading) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                            items(
                                count = pendingFiles.size,
                                key = { index ->
                                    val file = pendingFiles[index]
                                    file.id.ifBlank { file.url.orEmpty() }.ifBlank { "pending-$index" }
                                },
                            ) { index ->
                                val file = pendingFiles[index]
                                val label = file.url?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                                    ?: file.id.take(8).takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.chat_attach_fallback)
                                AssistChip(
                                    onClick = { onRemoveFile(file) },
                                    label = { Text("× $label") },
                                )
                            }
                            if (uploading) {
                                item(key = "uploading") {
                                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.chat_attach_uploading)) })
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            IconButton(
                                onClick = onAttachClick,
                                enabled = !streaming && !uploading,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = stringResource(R.string.chat_action_attach),
                                )
                            }
                        }
                        AnTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = stringResource(R.string.chat_input_hint),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { state -> if (state.isFocused) onInputFocused() },
                            maxLines = 6,
                            enabled = !streaming,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                        )
                        if (streaming) {
                            SmallFloatingActionButton(onClick = onStop) {
                                Icon(imageVector = Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_action_stop))
                            }
                        } else {
                            SmallFloatingActionButton(
                                onClick = { doSend() },
                                modifier = Modifier.alpha(if (sendEnabled) 1f else 0.5f),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.chat_action_send),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            RefreshBox(
                refreshing = false,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (messagesLoading && messages.isEmpty()) {
                    SkeletonList(modifier = Modifier.fillMaxSize())
                } else if (messages.isEmpty() && !streaming) {
                    if (activeTopicId == null && (openingMessage?.isNotBlank() == true || openingQuestions.isNotEmpty())) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(MaterialTheme.spacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Forum,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                text = stringResource(R.string.chat_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = MaterialTheme.spacing.m),
                            )
                            Text(
                                text = openingMessage?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.chat_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = MaterialTheme.spacing.s),
                            )
                            openingQuestions.forEach { question ->
                                AssistChip(
                                    onClick = { onSend(question, emptyList()) },
                                    label = { Text(question) },
                                    modifier = Modifier.padding(top = MaterialTheme.spacing.s),
                                )
                            }
                        }
                    } else {
                        EmptyBox(
                            icon = Icons.Filled.Forum,
                            title = stringResource(R.string.chat_empty_title),
                            description = stringResource(R.string.chat_empty_desc),
                        )
                    }
                } else {
                    val turns = remember(messages) { messages.toTurns() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = MaterialTheme.spacing.l),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                    ) {
                        items(
                            turns,
                            key = {
                                when (it) {
                                    is ChatTurn.User -> it.message.id.ifBlank { it.hashCode().toString() }
                                    is ChatTurn.AssistantGroup ->
                                        it.answer?.id.orEmpty().ifBlank { it.hashCode().toString() }
                                }
                            },
                            contentType = { if (it is ChatTurn.User) "user" else "group" },
                        ) { turn ->
                            when (turn) {
                                is ChatTurn.User -> MessageRow(message = turn.message)
                                is ChatTurn.AssistantGroup -> {
                                    turn.answer?.let { answer ->
                                        AssistantMessageRow(
                                            message = answer,
                                            agentName = title,
                                            agentAvatar = agentAvatar,
                                                onRetry = onRetry,
                                        )
                                    }
                                }
                            }
                        }
                        if (streaming) {
                            item(key = "streaming", contentType = "streaming") {
                                StreamingTurn(
                                    agentName = title,
                                    streamingText = streamingText,
                                    streamingReasoning = streamingReasoning,
                                    streamingReasoningSecs = streamingReasoningSecs,
                                    agentAvatar = agentAvatar,
                                    streamingSearchActivity = streamingSearchActivity,
                                    streamingCitations = streamingCitations,
                                )
                            }
                        }
                    }
                }
            }
            if (!atBottom) {
                ScrollToBottomButton(
                    onClick = onScrollToBottom,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }

    val detailModel = activeModel
    if (showModelDetail && detailModel != null) {
        ModelDetailSheet(
            model = detailModel,
            config = reasoningConfig,
            ui = ReasoningUiState(loading = reasoningLoading, saving = reasoningSaving),
            snackbar = snackbar,
            onLoad = onLoadReasoning,
            onSave = onSaveReasoning,
            onConsumeSaved = {},
            onDismissError = {},
            onDismiss = { showModelDetail = false },
        )
    }
}


@Composable
private fun MessageRow(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 4.dp,
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.l)) {
                // Container wraps the whole bubble so tapping padding or the
                // timestamp clears the selection (matches assistant side).
                SelectionContainer {
                    Column {
                        if (message.content.isNotBlank()) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        message.attachments.filter { it.name.isNotBlank() || it.uri.isNotBlank() }.forEach { attachment ->
                            Text(
                                text = "📎 " + (attachment.name.takeIf(String::isNotBlank) ?: stringResource(R.string.chat_attach_fallback)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (message.content.isBlank() && message.attachments.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_empty_message),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Timestamp(createdAt = message.createdAt)
                    }
                }
            }
        }
    }
}

@Composable
private fun Timestamp(createdAt: Long?) {
    val label = relativeTimeText(createdAt)
    if (label.isNotBlank()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
        )
    }
}

@Preview(name = "Chat-Light")
@Composable
private fun ChatLightPreview() {
    AnlobehubTheme(darkTheme = false) {
        ChatContent(
            messages = listOf(
                ChatMessage(id = "u1", role = "user", content = "Hello, introduce yourself", createdAt = System.currentTimeMillis() - 5 * 60 * 1000),
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "Hello! I am **Anlo**\n\n- Markdown supported\n- `code` highlighting\n\n```kotlin\nprintln(\"hi\")\n```",
                    createdAt = System.currentTimeMillis() - 4 * 60 * 1000,
                ),
                ChatMessage(
                    id = "a2",
                    role = "assistant",
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    error = "Network hiccup, last message cut off",
                ),
            ),
            streaming = true,
            streamingText = "Typing a **Markdown** reply...",
            streamingSearchActivity = WebSearchActivity(WebSearchStage.READING, listOf("Anlobehub local-first chat")),
            webSearchPolicy = WebSearchPolicy(WebSearchMode.NATIVE),
            webSearchAvailable = true,
            topics = listOf(ChatTopic(id = "t1", title = "Chit-chat")),
            activeTopicId = "t1",
            models = listOf(AIModel(id = "gpt-4o", displayName = "GPT-4o")),
            activeModel = AIModel(id = "gpt-4o", displayName = "GPT-4o"),
            pendingFiles = emptyList(),
            uploading = false,
        )
    }
}

@Preview(
    name = "Chat-Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatDarkPreview() {
    AnlobehubTheme(darkTheme = true) {
        ChatContent(
            messages = listOf(
                ChatMessage(id = "u1", role = "user", content = "Write me a bubble sort", createdAt = System.currentTimeMillis() - 60 * 60 * 1000),
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "Sure:\n\n```kotlin\nfun sort(a: IntArray) { a.sort() }\n```",
                    createdAt = System.currentTimeMillis() - 30 * 60 * 1000,
                ),
            ),
            streaming = true,
            streamingText = "Generating code...",
            topics = emptyList(),
            activeTopicId = null,
            models = listOf(AIModel(id = "gpt-4o", displayName = "GPT-4o")),
            activeModel = AIModel(id = "gpt-4o", displayName = "GPT-4o"),
            pendingFiles = emptyList(),
            uploading = false,
        )
    }
}

@Preview(name = "Web search sources")
@Preview(name = "联网搜索来源", locale = "zh-rCN")
@Composable
private fun WebSearchSourcesPreview() {
    AnlobehubTheme {
        ChatContent(
            messages = listOf(
                ChatMessage(id = "question", role = "user", content = "What did the source say?"),
                ChatMessage(
                    id = "answer",
                    role = "assistant",
                    content = "A sourced answer.\n\n```kotlin\nval raw = true\n```",
                    searchActivity = WebSearchActivity(WebSearchStage.COMPLETED, listOf("example source")),
                    citations = listOf(WebCitation("https://example.com", "Example source", 0, 17)),
                ),
                ChatMessage(id = "next", role = "user", content = "Find more"),
                ChatMessage(
                    id = "stopped",
                    role = "assistant",
                    searchActivity = WebSearchActivity(WebSearchStage.SEARCHING, listOf("more examples")),
                ),
            ),
            streaming = false,
            streamingText = "",
            webSearchPolicy = WebSearchPolicy(WebSearchMode.NATIVE),
            webSearchAvailable = true,
            topics = emptyList(),
            activeTopicId = null,
            models = emptyList(),
            activeModel = AIModel(id = "gpt-5", displayName = "GPT-5"),
            pendingFiles = emptyList(),
            uploading = false,
        )
    }
}

