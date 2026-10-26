package cc.jaxy.bob.feature.agents

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.bob.core.data.agent.Agent
import cc.jaxy.bob.core.designsystem.R as DsR
import cc.jaxy.bob.core.designsystem.component.AnTopBar
import cc.jaxy.bob.core.designsystem.component.EmptyBox
import cc.jaxy.bob.core.designsystem.component.ErrorBox
import cc.jaxy.bob.core.designsystem.component.InitialAvatar
import cc.jaxy.bob.core.designsystem.component.RefreshBox
import cc.jaxy.bob.core.designsystem.component.SkeletonList
import cc.jaxy.bob.core.designsystem.component.SearchField
import cc.jaxy.bob.core.designsystem.text.UiText
import cc.jaxy.bob.core.designsystem.text.resolve
import cc.jaxy.bob.core.designsystem.theme.BobTheme
import cc.jaxy.bob.core.designsystem.theme.hapticClick
import cc.jaxy.bob.core.designsystem.theme.inputRowContainer
import cc.jaxy.bob.core.designsystem.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onAgentClick: (String, String?) -> Unit,
    onSettingsClick: () -> Unit,
    onManageClick: (String) -> Unit,
    onCreateClick: () -> Unit,
) {
    AgentsContent(
        onAgentClick = onAgentClick,
        onSettingsClick = onSettingsClick,
        onManageClick = onManageClick,
        onCreateClick = onCreateClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsContent(
    onAgentClick: (String, String?) -> Unit,
    onSettingsClick: () -> Unit,
    onManageClick: (String) -> Unit,
    onCreateClick: () -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    // Non-empty list errors are transient hints; show once.
    val errorText = uiState.error?.resolve()
    LaunchedEffect(errorText, uiState.agents.isEmpty()) {
        if (errorText != null && uiState.agents.isNotEmpty()) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.dismissError()
        }
    }
    Scaffold(
        topBar = {
            AnTopBar(
                title = stringResource(R.string.agents_title),
                onBack = null,
                actions = {
                    IconButton(onClick = onCreateClick) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.agents_create),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(DsR.string.common_settings_title),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AgentsBody(
            agents = uiState.agents,
            loading = uiState.loading,
            operatingIds = uiState.operatingIds,
            error = uiState.error,
            query = query,
            onQueryChange = { query = it },
            onRefresh = { viewModel.refresh() },
            onCreate = onCreateClick,
            onAgentClick = onAgentClick,
            onManageClick = onManageClick,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Pure UI content: search + refresh + list states, no ViewModel dependency, previewable. */
@Composable
private fun AgentsBody(
    agents: List<Agent>,
    loading: Boolean,
    operatingIds: Set<String>,
    error: UiText?,
    query: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onAgentClick: (String, String?) -> Unit,
    onManageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val filtered = remember(agents, query) {
        if (query.isBlank()) {
            agents
        } else {
            agents.filter {
                (it.displayName() ?: "").contains(query, ignoreCase = true) ||
                    (it.description ?: "").contains(query, ignoreCase = true)
            }
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            hint = stringResource(R.string.agents_search_hint),
        )
        RefreshBox(
            refreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when {
                loading && agents.isEmpty() -> SkeletonList()
                error != null && agents.isEmpty() -> ErrorBox(text = error.resolve(), onRetry = onRefresh)
                agents.isEmpty() -> EmptyBox(
                    icon = Icons.Outlined.SmartToy,
                    title = stringResource(R.string.agents_empty_title),
                    description = stringResource(R.string.agents_empty_desc),
                    actionLabel = stringResource(R.string.agents_create),
                    onAction = onCreate,
                )
                filtered.isEmpty() -> EmptyBox(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.agents_no_match_title),
                    description = stringResource(R.string.agents_no_match_desc),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = spacing.l, vertical = spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    items(filtered, key = { it.id }) { agent ->
                        AgentRow(
                            agent = agent,
                            busy = agent.id in operatingIds,
                            onClick = onAgentClick,
                            onManageClick = onManageClick,
                        )
                    }
                }
            }
        }
    }
}

internal fun Agent.displayName(): String? =
    name?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }

/**
 * Single-column row: avatar + name fill the row; tap opens the chat,
 * long-press opens the editor. No trailing actions.
 */
@Composable
private fun AgentRow(
    agent: Agent,
    busy: Boolean,
    onClick: (String, String?) -> Unit,
    onManageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val name = agent.displayName() ?: stringResource(R.string.agents_untitled)
    val spacing = MaterialTheme.spacing
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.inputRowContainer,
        ),
        onClick = {
            hapticClick(view)
            onClick(agent.id, agent.displayName())
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        hapticClick(view)
                        onClick(agent.id, agent.displayName())
                    },
                    onLongClick = { onManageClick(agent.id) },
                )
                .padding(horizontal = spacing.l, vertical = spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            InitialAvatar(name = name, avatarUrl = agent.avatar, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                agent.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

private val previewAgents = listOf(
    Agent(id = "a1", name = "Translator", description = "Translate anything", avatar = null),
    Agent(id = "a2", name = null, title = "Draft", description = "Draft agent", avatar = null),
)

@Preview(name = "Agents-Light", showBackground = true)
@Composable
private fun AgentsBodyLightPreview() {
    BobTheme(darkTheme = false) {
        AgentsBody(
            agents = previewAgents,
            loading = false,
            operatingIds = emptySet(),
            error = null,
            query = "",
            onQueryChange = {},
            onRefresh = {},
            onCreate = {},
            onAgentClick = { _, _ -> },
            onManageClick = {},
        )
    }
}

@Preview(
    name = "Agents-Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AgentsBodyDarkPreview() {
    BobTheme(darkTheme = true) {
        AgentsBody(
            agents = previewAgents,
            loading = false,
            operatingIds = emptySet(),
            error = null,
            query = "",
            onQueryChange = {},
            onRefresh = {},
            onCreate = {},
            onAgentClick = { _, _ -> },
            onManageClick = {},
        )
    }
}
