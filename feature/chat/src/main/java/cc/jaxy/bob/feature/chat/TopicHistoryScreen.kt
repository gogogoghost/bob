package cc.jaxy.bob.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.bob.core.designsystem.R as DsR
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.EmptyBox
import cc.jaxy.bob.core.designsystem.component.ErrorBox
import cc.jaxy.bob.core.designsystem.component.SearchField
import cc.jaxy.bob.core.designsystem.component.SkeletonList
import cc.jaxy.bob.core.designsystem.component.relativeTimeText
import cc.jaxy.bob.core.designsystem.text.resolve
import cc.jaxy.bob.core.designsystem.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicHistoryScreen(
    activeTopicId: String?,
    onSelectTopic: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TopicHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = uiState.error?.resolve()
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(errorText) {
        if (errorText != null) snackbar.showSnackbar(errorText)
    }
    // Auto-load next page when scrolling near the end.
    val loadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(loadMore, uiState.topics.size) {
        if (loadMore) viewModel.loadMore()
    }
    val filtered = remember(uiState.topics, query) {
        if (query.isBlank()) uiState.topics
        else uiState.topics.filter { it.title?.contains(query, ignoreCase = true) == true }
    }
    Scaffold(
        topBar = {
            AnTopBar(
                title = stringResource(R.string.chat_agent_history),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.chat_topic_search_hint),
            )
            when {
                uiState.loading && uiState.topics.isEmpty() ->
                    SkeletonList(modifier = Modifier.fillMaxSize())
                uiState.error != null && uiState.topics.isEmpty() ->
                    ErrorBox(
                        text = uiState.error!!.resolve(),
                        onRetry = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                filtered.isEmpty() && query.isNotBlank() ->
                    EmptyBox(
                        icon = Icons.Filled.Forum,
                        title = stringResource(R.string.chat_topic_no_match),
                        description = query,
                    )
                uiState.topics.isEmpty() ->
                    EmptyBox(
                        icon = Icons.Filled.Forum,
                        title = stringResource(R.string.chat_topic_empty),
                        description = null,
                    )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { topic ->
                        val selected = activeTopicId == topic.id
                        val time = relativeTimeText(topic.updatedAt ?: topic.createdAt)
                        ListItem(
                            headlineContent = {
                                Text(
                                    topic.title?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.chat_topic_untitled),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            supportingContent = { if (time.isNotBlank()) Text(time) },
                            trailingContent = {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    IconButton(onClick = { pendingDelete = topic.id }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(DsR.string.common_delete),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onSelectTopic(topic.id) },
                        )
                        HorizontalDivider()
                    }
                    if (uiState.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    if (uiState.endReached && uiState.topics.isNotEmpty()) {
                        item(key = "end") {
                            Text(
                                text = stringResource(R.string.chat_topic_end),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_topic_delete_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTopic(id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(DsR.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(DsR.string.common_cancel))
                }
            },
        )
    }
}
