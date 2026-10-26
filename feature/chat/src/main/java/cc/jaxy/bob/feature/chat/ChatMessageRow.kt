package cc.jaxy.bob.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import cc.jaxy.bob.core.data.chat.ChatMessage
import cc.jaxy.bob.core.data.chat.ChatCompletionStatus
import cc.jaxy.bob.core.data.chat.WebCitation
import cc.jaxy.bob.core.data.chat.WebSearchActivity
import cc.jaxy.bob.core.data.chat.WebSearchStage
import androidx.compose.material3.CircularProgressIndicator
import cc.jaxy.bob.core.designsystem.component.InitialAvatar
import cc.jaxy.bob.core.designsystem.component.SearchProviderIcon
import cc.jaxy.bob.core.designsystem.component.MarkdownText
import cc.jaxy.bob.core.designsystem.component.relativeTimeText
import cc.jaxy.bob.core.designsystem.theme.spacing

/**
 * Assistant message: borderless, with expandable reasoning and markdown.
 *
 */
@Composable
fun AssistantMessageRow(
    message: ChatMessage,
    agentName: String?,
    agentAvatar: String? = null,
    isStreaming: Boolean = false,
    streamingText: String? = null,
    streamingReasoning: String? = null,
    streamingReasoningSecs: Double? = null,
    modifier: Modifier = Modifier,
    streamingSearchActivity: WebSearchActivity? = null,
    streamingCitations: List<WebCitation> = emptyList(),
    onRetry: (() -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    val body = streamingText ?: message.content
    val reasoning = streamingReasoning ?: message.reasoning
    val reasoningSecs = streamingReasoningSecs ?: message.reasoningDuration
    val completionStatus = message.completion?.status
    val completionText = completionStatus?.messageResource()
    val searchActivity = streamingSearchActivity ?: message.searchActivity
    val nativeCitations = if (isStreaming) streamingCitations else message.citations
    val searching = searchActivity?.stage == WebSearchStage.SEARCHING || searchActivity?.stage == WebSearchStage.READING
    val presentation = remember(body, nativeCitations) { presentCitations(body, nativeCitations) }
    val citations = remember(body, presentation.sources, presentation.usedSources, searchActivity) {
        // Cards mirror the rendered text: only sources actually linked from the
        // answer body. Unreferenced search hits never render as cards (no
        // cards-before-answer while streaming, no orphan list when settled).
        if (searchActivity != null || nativeCitations.isNotEmpty()) presentation.usedSources
        else parseCitations(body)
    }
    val citationByUrl = remember(citations) { citations.associateBy { it.url } }
    var linkPreview by remember(message.id) { mutableStateOf<ChatCitation?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.s)
            // No animateContentSize while streaming: per-token layout
            // animation reads as flicker at 12fps updates.
            .then(if (isStreaming) Modifier else Modifier.animateContentSize()),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            InitialAvatar(
                name = agentName?.takeIf { it.isNotBlank() } ?: "AI",
                avatarUrl = agentAvatar?.takeIf { it.isNotBlank() },
                size = 28.dp,
            )
            Text(
                text = agentName?.takeIf { it.isNotBlank() }
                    ?: message.model?.takeIf { it.isNotBlank() }
                    ?: "AI",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val timeLabel = relativeTimeText(message.createdAt)
            if (timeLabel.isNotBlank()) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Local-agent feel: no fixed engine banner. Search appears only when the
        // model actually searched, inside the thinking trace (collapsed or not).
        val showThinking = !reasoning.isNullOrBlank() || searchActivity != null ||
            (isStreaming && body.isBlank() && !searching)
        if (showThinking) {
            ThinkingCard(
                content = reasoning.orEmpty(),
                thinking = isStreaming && body.isBlank(),
                durationSecs = reasoningSecs,
                searchActivity = searchActivity,
                sourceCount = citations.size,
                active = isStreaming,
            )
        }
        if (body.isNotBlank()) {
            // Intercept all markdown link taps: show preview sheet first.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides remember(citationByUrl, message.id) {
                    object : androidx.compose.ui.platform.UriHandler {
                        override fun openUri(uri: String) {
                            if (isSafeWebUrl(uri)) {
                                linkPreview = citationByUrl[uri]
                                    ?: ChatCitation(index = "", title = uri, url = uri)
                            }
                        }
                    }
                },
            ) {
                MarkdownText(markdown = presentation.markdown, streaming = isStreaming)
            }
            if (isStreaming) StreamingCursor()
        } else if (!isStreaming && completionText == null && searchActivity == null && citations.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (isStreaming && reasoning.isNullOrBlank() && searchActivity == null) {
            // Reasoning-only phase with nothing yet: show cursor alone.
            StreamingCursor()
        }
        if (citations.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chat_web_search_sources),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CitationRow(citations = citations, onOpen = { linkPreview = it })
        }
        linkPreview?.let { citation ->
            LinkPreviewSheet(citation = citation, onDismiss = { linkPreview = null })
        }
        if (!isStreaming && completionText != null) {
            Text(
                text = stringResource(completionText),
                style = MaterialTheme.typography.bodySmall,
                color = if (completionStatus == ChatCompletionStatus.STOPPED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            message.completion?.finishReason?.takeIf(String::isNotBlank)?.let { reason ->
                Text(
                    text = stringResource(R.string.chat_response_finish_reason, reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!message.error.isNullOrBlank()) {
            Text(
                text = message.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!isStreaming && (body.isNotBlank() || onRetry != null)) {
            val retryable = completionStatus == ChatCompletionStatus.FAILED ||
                completionStatus == ChatCompletionStatus.INTERRUPTED ||
                completionStatus == ChatCompletionStatus.STOPPED ||
                message.error?.isNotBlank() == true
            MessageActions(text = body, showRetry = retryable && onRetry != null, onRetry = { onRetry?.invoke() })
        }
    }
}

/** Unified thinking trace: reasoning + live search activity in one collapsible card.
 *
 * Local-first: no standalone engine banner. When the model did not search,
 * this is just the reasoning trace. When it did, the header shows the live
 * search state while streaming (and a one-line summary when settled), and the
 * expanded body lists queries/engine/sources above the reasoning text.
 */
@Composable
private fun ThinkingCard(
    content: String,
    thinking: Boolean,
    durationSecs: Double? = null,
    modifier: Modifier = Modifier,
    searchActivity: WebSearchActivity? = null,
    sourceCount: Int = 0,
    active: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    val searching = searchActivity?.stage == WebSearchStage.SEARCHING ||
        searchActivity?.stage == WebSearchStage.READING
    val liveSearch = active && searching
    // Collapsed header: live search state wins while streaming; settled shows a
    // one-line search summary next to the thinking state.
    val header: String = when {
        liveSearch && searchActivity?.stage == WebSearchStage.SEARCHING ->
            stringResource(R.string.chat_web_search_searching)
        liveSearch -> stringResource(R.string.chat_web_search_reading)
        searchActivity != null && searchActivity.stage == WebSearchStage.FAILED ->
            stringResource(R.string.chat_web_search_failed)
        searchActivity != null -> searchSummary(searchActivity, sourceCount)
        thinking -> stringResource(R.string.chat_thinking)
        durationSecs != null -> stringResource(R.string.chat_thought_secs, durationSecs)
        else -> stringResource(R.string.chat_thought_done)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.m, vertical = spacing.s)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                if (thinking || liveSearch) {
                    ThinkingDots()
                } else if (searchActivity != null) {
                    SearchProviderIcon(
                        providerId = searchActivity.providerId,
                        name = searchActivity.providerName,
                        builtin = searchActivity.providerBuiltin,
                        size = 18.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (thinking || liveSearch) {
                        MaterialTheme.colorScheme.primary
                    } else if (searchActivity?.stage == WebSearchStage.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                searchActivity?.let { activity ->
                    SearchDetail(
                        activity = activity,
                        sourceCount = sourceCount,
                        modifier = Modifier.padding(top = spacing.s),
                    )
                }
                if (content.isNotBlank()) {
                    MarkdownText(
                        markdown = content,
                        modifier = Modifier.padding(top = spacing.s),
                    )
                }
            }
        }
    }
}

/** Settled one-liner: engine + query + result count (or failure). */
@Composable
private fun searchSummary(activity: WebSearchActivity, sourceCount: Int): String {
    val engine = activity.providerName?.takeIf(String::isNotBlank)
        ?: activity.providerId?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.chat_web_search_native)
    val query = activity.queries.firstOrNull { it.isNotBlank() }
    return when {
        sourceCount > 0 && query != null ->
            stringResource(R.string.chat_search_summary_results, engine, sourceCount, query)
        query != null -> stringResource(R.string.chat_search_summary_query, engine, query)
        sourceCount > 0 -> stringResource(R.string.chat_search_summary_count, engine, sourceCount)
        else -> stringResource(R.string.chat_search_summary_done, engine)
    }
}

/** Expanded search trace: engine, queries, result count, error. */
@Composable
private fun SearchDetail(activity: WebSearchActivity, sourceCount: Int, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        activity.providerId?.let { id ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                SearchProviderIcon(providerId = id, name = activity.providerName,
                    builtin = activity.providerBuiltin, size = 20.dp)
                Text(
                    text = activity.providerName?.takeIf(String::isNotBlank) ?: id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        activity.queries.distinct().filter(String::isNotBlank).forEach { query ->
            Text(
                text = stringResource(R.string.chat_web_search_query, query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sourceCount > 0) {
            Text(
                text = stringResource(R.string.chat_search_sources_count, sourceCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        activity.error?.takeIf(String::isNotBlank)?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}


@Composable
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingAlpha",
    )
    Text(
        text = "● ● ●",
        modifier = modifier.alpha(alpha),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StreamingCursor(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        modifier = modifier.alpha(alpha),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MessageActions(
    text: String,
    modifier: Modifier = Modifier,
    showRetry: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        IconButton(
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) } },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.chat_action_copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (showRetry) {
            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.chat_action_retry),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}



private val FOOTNOTE_DEF = Regex("""(?m)^\[\^(\d+)\]:\s*(.+)$""")
private val MD_LINK = Regex("""\[([^\]]*)\]\((https?://[^)]+)\)""")

/** Extracts citations; URLs double as the inline-link map. */
fun parseCitations(markdown: String): List<ChatCitation> {
    if (!markdown.contains("[^")) return emptyList()
    return FOOTNOTE_DEF.findAll(markdown).mapNotNull { match ->
        val raw = match.groupValues[2].trim()
        val link = MD_LINK.find(raw)
        val url = link?.groupValues?.getOrNull(2)
            ?: raw.split(Regex("""\s+""")).firstOrNull().orEmpty()
        if (!isSafeWebUrl(url)) return@mapNotNull null
        val title = link?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: url
        ChatCitation(index = match.groupValues[1], title = title, url = url)
    }.toList()
}

/**
 * Horizontal citation cards under the message, mirroring web search cards:
 * title + host, tapping opens the URL.
 */
@Composable
fun CitationRow(
    citations: List<ChatCitation>,
    modifier: Modifier = Modifier,
    onOpen: (ChatCitation) -> Unit = {},
) {
    if (citations.isEmpty()) return
    val spacing = MaterialTheme.spacing
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(citations.size, key = { citations[it].url }) { i ->
            val citation = citations[i]
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onOpen(citation) },
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = spacing.m, vertical = spacing.s),
                ) {
                    Text(
                        text = citation.index.takeIf(String::isNotBlank)?.let { "$it. ${citation.title}" } ?: citation.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = citation.host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Bottom preview for any tapped link: title, URL, host; jump on confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPreviewSheet(
    citation: ChatCitation,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.m),
            ) {
                InitialAvatar(name = citation.host, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = citation.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = citation.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = citation.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = {
                    if (isSafeWebUrl(citation.url)) runCatching { uriHandler.openUri(citation.url) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.chat_link_open))
            }
        }
    }
}

/** Renders the active assistant response with its live reasoning trace. */
@Composable
fun StreamingTurn(
    agentName: String?,
    agentAvatar: String? = null,
    streamingText: String,
    streamingReasoning: String,
    streamingReasoningSecs: Double?,
    modifier: Modifier = Modifier,
    streamingSearchActivity: WebSearchActivity? = null,
    streamingCitations: List<WebCitation> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AssistantMessageRow(
            message = ChatMessage(role = "assistant"),
            agentName = agentName,
            agentAvatar = agentAvatar,
            isStreaming = true,
            streamingText = streamingText,
            streamingReasoning = streamingReasoning,
            streamingReasoningSecs = streamingReasoningSecs,
            streamingSearchActivity = streamingSearchActivity,
            streamingCitations = streamingCitations,
        )
    }
}


