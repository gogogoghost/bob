package cc.jaxy.anlobehub.app

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cc.jaxy.anlobehub.core.common.model.PICKED_MODEL_KEY
import cc.jaxy.anlobehub.core.common.model.formatPickedModel
import cc.jaxy.anlobehub.core.common.model.parsePickedModel
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.feature.agents.AgentEditScreen
import cc.jaxy.anlobehub.feature.agents.AgentEditViewModel
import cc.jaxy.anlobehub.feature.agents.AgentsScreen
import cc.jaxy.anlobehub.feature.agents.AgentsViewModel
import cc.jaxy.anlobehub.feature.chat.ChatScreen
import cc.jaxy.anlobehub.feature.chat.TopicHistoryScreen
import cc.jaxy.anlobehub.feature.chat.ChatViewModel
import cc.jaxy.anlobehub.feature.models.ModelsScreen
import cc.jaxy.anlobehub.feature.settings.ProviderDetailScreen
import cc.jaxy.anlobehub.feature.settings.ProviderDetailViewModel
import cc.jaxy.anlobehub.feature.settings.ProviderModelsScreen
import cc.jaxy.anlobehub.feature.settings.ProvidersScreen
import cc.jaxy.anlobehub.feature.settings.SettingsScreen
import cc.jaxy.anlobehub.feature.settings.WebSearchSettingsScreen
import cc.jaxy.anlobehub.feature.settings.WebSearchProviderScreen
import kotlinx.serialization.Serializable


const val AGENTS_DIRTY_KEY = "agents_dirty_tick"

@Serializable
data object AgentsRoute

@Serializable
data class AgentEditRoute(val agentId: String? = null)

@Serializable
data class ChatRoute(
    val agentId: String,
    val agentTitle: String? = null,
    val topicId: String? = null,
)

@Serializable
data class TopicHistoryRoute(
    val agentId: String,
    val activeTopicId: String? = null,
)

@Serializable
data object SettingsRoute

@Serializable
data object WebSearchSettingsRoute

@Serializable
data class WebSearchProviderRoute(val providerId: String? = null)


@Serializable
data object ProvidersRoute

@Serializable
data class ProviderModelsRoute(val providerId: String, val providerName: String? = null)

@Serializable
data class ProviderDetailRoute(val providerId: String, val providerName: String? = null)


@Serializable
data class ModelPickerRoute(
    val selectedModelId: String? = null,
    val selectedProviderId: String? = null,
    val filterProviderId: String? = null,
)

@Composable
fun AnlobehubNavHost() {
    val navController = rememberNavController()
    // Themed backdrop: prevents white flash behind slide/fade transitions.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    NavHost(
        navController = navController,
        startDestination = AgentsRoute,
        // Forward push + tap-back share these; the back *gesture* uses the
        // predictive* pair below (seek-driven, with swipeEdge direction).
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(350))
        },
        exitTransition = {
            scaleOut(
                targetScale = 0.9f,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            scaleIn(
                initialScale = 0.9f,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(200))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(350)) +
                scaleOut(
                    targetScale = 0.9f,
                    animationSpec = tween(350, easing = FastOutSlowInEasing),
                )
        },
        predictivePopEnterTransition = {
            scaleIn(
                initialScale = 0.9f,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(200))
        },
        predictivePopExitTransition = {
            val dir = if (it == BackEventCompat.EDGE_RIGHT) -1 else 1
            slideOutHorizontally(
                targetOffsetX = { dir * it / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(350)) +
                scaleOut(
                    targetScale = 0.9f,
                    animationSpec = tween(350, easing = FastOutSlowInEasing),
                )
        },
    ) {
        composable<AgentsRoute> { entry ->
            val agentsViewModel: AgentsViewModel = hiltViewModel(entry)
            // Refresh only when the editor reports a mutation (create/edit/
            // delete success); plain returns and first load don't refetch.
            // Manual refresh stays on pull-to-refresh.
            val dirtyTick by entry.savedStateHandle.getStateFlow(AGENTS_DIRTY_KEY, 0)
                .collectAsStateWithLifecycle()
            LaunchedEffect(dirtyTick) {
                if (dirtyTick > 0) {
                    entry.savedStateHandle[AGENTS_DIRTY_KEY] = 0
                    agentsViewModel.refresh()
                }
            }
            AgentsScreen(
                onAgentClick = { agentId, agentTitle ->
                    navController.navigate(ChatRoute(agentId = agentId, agentTitle = agentTitle))
                },
                onSettingsClick = { navController.navigate(SettingsRoute) },
                onManageClick = { agentId ->
                    navController.navigate(AgentEditRoute(agentId = agentId))
                },
                onCreateClick = { navController.navigate(AgentEditRoute()) },
            )
        }
        composable<AgentEditRoute> { entry ->
            val route = entry.toRoute<AgentEditRoute>()
            val editViewModel: AgentEditViewModel = hiltViewModel(entry)
            val pickedRaw by entry.savedStateHandle.getStateFlow<String?>(PICKED_MODEL_KEY, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(pickedRaw) {
                parsePickedModel(pickedRaw)?.let { picked ->
                    editViewModel.setModel(
                        AIModel(
                            id = picked.modelId,
                            displayName = picked.displayName,
                            providerId = picked.providerId,
                        ),
                    )
                    entry.savedStateHandle[PICKED_MODEL_KEY] = null
                }
            }
            AgentEditScreen(
                agentId = route.agentId,
                onBack = { navController.popBackStack() },
                onOpenWebSearchSettings = { navController.navigate(WebSearchSettingsRoute) },
                onOpenModelPicker = { current ->
                    navController.navigate(
                        ModelPickerRoute(
                            selectedModelId = current?.id,
                            selectedProviderId = current?.providerId,
                        ),
                    )
                },
                onCreated = { id ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(AGENTS_DIRTY_KEY, System.currentTimeMillis().toInt())
                    navController.popBackStack()
                },
                onMutated = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(AGENTS_DIRTY_KEY, System.currentTimeMillis().toInt())
                },
                viewModel = editViewModel,
            )
        }
        composable<ChatRoute> { entry ->
            val chatViewModel: ChatViewModel = hiltViewModel(entry)
            val pickedRaw by entry.savedStateHandle.getStateFlow<String?>(PICKED_MODEL_KEY, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(pickedRaw) {
                parsePickedModel(pickedRaw)?.let { picked ->
                    val match = chatViewModel.uiState.value.models.firstOrNull {
                        it.id == picked.modelId && (picked.providerId == null || it.providerId == picked.providerId)
                    } ?: AIModel(
                        id = picked.modelId,
                        displayName = picked.displayName,
                        providerId = picked.providerId,
                    )
                    chatViewModel.selectModel(match)
                    entry.savedStateHandle[PICKED_MODEL_KEY] = null
                }
            }
            ChatScreen(
                onOpenModelPicker = { current ->
                    navController.navigate(
                        ModelPickerRoute(
                            selectedModelId = current?.id,
                            selectedProviderId = current?.providerId,
                        ),
                    )
                },
                onOpenHistory = { agentId, activeTopicId ->
                    navController.navigate(
                        TopicHistoryRoute(agentId = agentId, activeTopicId = activeTopicId),
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<TopicHistoryRoute> { entry ->
            val route = entry.toRoute<TopicHistoryRoute>()
            // Chat back stack entry shares the ChatViewModel for topic select.
            val chatEntry = remember(entry) {
                navController.getBackStackEntry(ChatRoute::class)
            }
            val chatViewModel: ChatViewModel = hiltViewModel(chatEntry)
            TopicHistoryScreen(
                activeTopicId = route.activeTopicId,
                onSelectTopic = { topicId: String ->
                    chatViewModel.selectTopic(topicId)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onProvidersClick = { navController.navigate(ProvidersRoute) },
                onWebSearchClick = { navController.navigate(WebSearchSettingsRoute) },
            )
        }
        composable<WebSearchSettingsRoute> {
            WebSearchSettingsScreen(
                onBack = { navController.popBackStack() },
                onProviderClick = { id -> navController.navigate(WebSearchProviderRoute(id)) },
                onAddProvider = { navController.navigate(WebSearchProviderRoute()) },
            )
        }
        composable<WebSearchProviderRoute> { entry ->
            val route = entry.toRoute<WebSearchProviderRoute>()
            WebSearchProviderScreen(providerId = route.providerId, onBack = { navController.popBackStack() })
        }
        composable<ProvidersRoute> {
            ProvidersScreen(
                onBack = { navController.popBackStack() },
                onProviderClick = { id, name ->
                    navController.navigate(ProviderModelsRoute(providerId = id, providerName = name))
                },
                onConfigClick = { id, name ->
                    navController.navigate(ProviderDetailRoute(providerId = id, providerName = name))
                },
            )
        }
        composable<ProviderDetailRoute> { entry ->
            val route = entry.toRoute<ProviderDetailRoute>()
            val detailViewModel: ProviderDetailViewModel = hiltViewModel(entry)
            val pickedRaw by entry.savedStateHandle.getStateFlow<String?>(PICKED_MODEL_KEY, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(pickedRaw) {
                // Only consume picks aimed at this screen (provider-scoped).
                parsePickedModel(pickedRaw)?.let { picked ->
                    if (picked.providerId == route.providerId) {
                        detailViewModel.setCheckModel(picked.modelId)
                        entry.savedStateHandle[PICKED_MODEL_KEY] = null
                    }
                }
            }
            ProviderDetailScreen(
                providerId = route.providerId,
                providerName = route.providerName,
                onBack = { navController.popBackStack() },
                onModelsClick = { id, name ->
                    navController.navigate(ProviderModelsRoute(providerId = id, providerName = name))
                },
                onPickCheckModel = {
                    navController.navigate(
                        ModelPickerRoute(filterProviderId = route.providerId),
                    )
                },
                viewModel = detailViewModel,
            )
        }
        composable<ProviderModelsRoute> { entry ->
            val route = entry.toRoute<ProviderModelsRoute>()
            ProviderModelsScreen(
                providerId = route.providerId,
                providerName = route.providerName,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ModelPickerRoute> { entry ->
            val route = entry.toRoute<ModelPickerRoute>()
            ModelsScreen(
                selectedModelId = route.selectedModelId,
                selectedProviderId = route.selectedProviderId,
                filterProviderId = route.filterProviderId,
                onPick = { model ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        PICKED_MODEL_KEY,
                        formatPickedModel(model.providerId, model.id, model.displayName),
                    )
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
    }
}
