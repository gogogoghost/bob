package cc.jaxy.anlobehub.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import cc.jaxy.anlobehub.core.common.result.AnError
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.agent.AgentRepository
import cc.jaxy.anlobehub.core.data.agent.AgentConfig
import cc.jaxy.anlobehub.core.data.chat.*
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.preferences.WebSearchPreferences
import cc.jaxy.anlobehub.core.data.preferences.WebSearchSettings
import cc.jaxy.anlobehub.core.data.search.WebSearchMode
import cc.jaxy.anlobehub.core.data.search.WebSearchPolicy
import cc.jaxy.anlobehub.core.data.search.WebSearchProtocol
import cc.jaxy.anlobehub.core.data.search.WebSearchProvider
import cc.jaxy.anlobehub.core.data.search.WebSearchProviderRepository
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @After
    fun resetDispatcher() { Dispatchers.resetMain() }

    @Test
    fun `a stopped response keeps its original binding and delayed save cannot alter a newer conversation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persisted = mutableListOf<Pair<String, ChatMessage>>()
        val releaseOldSave = CompletableDeferred<Unit>()
        val channels = mutableListOf<Channel<ChatStreamChunk>>()
        var nextTopic = 0
        val models = dependency(ModelRepository::class.java) { name ->
            when (name) {
                "listModels" -> AnResult.Ok(listOf(AIModel(id = "original-model", providerId = "original-provider", enabled = true)))
                else -> error("Unexpected model operation $name")
            }
        }
        val topics = dependency(TopicRepository::class.java) { name ->
            when (name) {
                "createTopic" -> AnResult.Ok("topic-${++nextTopic}")
                "listTopics" -> AnResult.Ok((1..nextTopic).map { ChatTopic(id = "topic-$it") })
                else -> error("Unexpected topic operation $name")
            }
        }
        val messages = object : MessageRepository {
            override suspend fun listMessages(topicId: String?, agentId: String?) = AnResult.Ok(persisted.filter { it.first == topicId }.map { it.second })
            override suspend fun saveMessage(topicId: String, message: ChatMessage): AnResult<Unit> {
                if (topicId == "topic-1" && message.role == "assistant") releaseOldSave.await()
                persisted += topicId to message
                return AnResult.Ok(Unit)
            }
        }
        val completions = object : ChatCompletionRepository {
            override fun stream(providerId: String, modelId: String, systemPrompt: String, history: List<ChatMessage>, temperature: Double?, sessionId: String?, searchPolicy: WebSearchPolicy, purePrompt: Boolean): Flow<ChatStreamChunk> {
                val channel = Channel<ChatStreamChunk>(Channel.UNLIMITED)
                channels += channel
                return flow { for (chunk in channel) emit(chunk) }
            }
        }
        val agents = dependency(AgentRepository::class.java) { error("Unexpected agent operation $it") }
        val files = dependency(FileRepository::class.java) { error("Unexpected file operation $it") }
        val vm = ChatViewModel(SavedStateHandle(), messages, topics, models, agents, files, completions, TestSearchPreferences(), TestSearchProviders())
        val store = ViewModelStore().apply { put("chat", vm) }
        try {
            runCurrent()
            vm.send("First question")
            vm.send("Duplicate rapid click")
            runCurrent()
            assertEquals(1, channels.size)
            channels[0].send(ChatStreamChunk(textDelta = "First"))
            runCurrent()
            channels[0].send(ChatStreamChunk(textDelta = " tail"))
            runCurrent()
            vm.selectModel(AIModel(id = "different-model", providerId = "different-provider", enabled = true))
            vm.stop()
            val stopped = vm.uiState.value.messages.last()
            assertEquals("First tail", stopped.content)
            assertEquals("original-provider", stopped.provider)
            assertEquals("original-model", stopped.model)
            assertEquals(ChatCompletionStatus.STOPPED, stopped.completion?.status)
            runCurrent()
            vm.newConversation()
            vm.send("Second question")
            runCurrent()
            releaseOldSave.complete(Unit)
            runCurrent()
            assertEquals("topic-2", vm.uiState.value.activeTopicId)
            assertTrue(vm.uiState.value.streaming)
            assertEquals(listOf("Second question"), vm.uiState.value.messages.map { it.content })
            assertEquals("First tail", persisted.single { it.first == "topic-1" && it.second.role == "assistant" }.second.content)
            channels[1].send(ChatStreamChunk(textDelta = "Second answer"))
            channels[1].send(ChatStreamChunk(completion = ChatCompletion(ChatCompletionStatus.COMPLETED, "stop")))
            channels[1].close()
            runCurrent()
            assertFalse(vm.uiState.value.streaming)
            assertEquals(listOf("Second question", "Second answer"), vm.uiState.value.messages.map { it.content })
            assertEquals(ChatCompletionStatus.COMPLETED, vm.uiState.value.messages.last().completion?.status)
        } finally {
            releaseOldSave.complete(Unit)
            channels.forEach { it.close() }
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun `stopping before answer text preserves search activity and sources in history`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = TestSearchPreferences(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"))
        val source = WebCitation("https://example.com/news", "Source report")
        val activity = WebSearchActivity(WebSearchStage.READING, listOf("latest local agent"))
        val fixture = searchFixture(preferences, flow {
            emit(ChatStreamChunk(searchActivity = activity, citations = listOf(source)))
            awaitCancellation()
        })
        try {
            runCurrent()
            assertEquals(WebSearchMode.PROVIDER, fixture.vm.uiState.value.webSearchPolicy.mode)
            assertTrue(fixture.vm.uiState.value.webSearchAvailable)
            fixture.vm.send("latest local agent")
            runCurrent()
            assertEquals(activity, fixture.vm.uiState.value.streamingSearchActivity)
            assertEquals(listOf(source), fixture.vm.uiState.value.streamingCitations)
            fixture.vm.stop()
            runCurrent()
            fixture.vm.newConversation()
            fixture.vm.selectTopic("search-topic")
            runCurrent()
            val answer = fixture.vm.uiState.value.messages.last()
            assertEquals("", answer.content)
            assertEquals(activity, answer.searchActivity)
            assertEquals(listOf(source), answer.citations)
            assertEquals(ChatCompletionStatus.STOPPED, answer.completion?.status)
            assertFalse(fixture.vm.uiState.value.streaming)
            assertNull(fixture.vm.uiState.value.streamingSearchActivity)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `Agent overrides stay fixed while inherited search follows global changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val off = WebSearchPolicy()
        val native = WebSearchPolicy(WebSearchMode.NATIVE)
        val external = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
        val preferences = TestSearchPreferences(off)
        val fixtures = listOf(null, native, external, off).map { preference ->
            searchFixture(preferences, flow { error("Policy changes must not start chat") },
                agentConfig = { AnResult.Ok(AgentConfig(webSearch = preference)) })
        }
        try {
            runCurrent()
            assertEquals(listOf(off, native, external, off), fixtures.map { it.vm.uiState.value.webSearchPolicy })
            preferences.webSearchSettings.value = WebSearchSettings(external)
            runCurrent()
            assertEquals(listOf(external, native, external, off), fixtures.map { it.vm.uiState.value.webSearchPolicy })
            preferences.webSearchSettings.value = WebSearchSettings(native)
            runCurrent()
            assertEquals(listOf(native, native, external, off), fixtures.map { it.vm.uiState.value.webSearchPolicy })
            assertTrue(fixtures.all { it.vm.uiState.value.webSearchReady })
        } finally {
            fixtures.forEach { it.store.clear() }
            runCurrent()
        }
    }

    @Test
    fun `pure prompt agent collapses search to off without blocking send`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = TestSearchPreferences(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"))
        var seenPolicy = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
        var seenPure = false
        val chunks = flow {
            emit(ChatStreamChunk(textDelta = "Pure answer"))
            emit(ChatStreamChunk(completion = ChatCompletion(ChatCompletionStatus.COMPLETED, "stop")))
        }
        val persisted = mutableListOf<ChatMessage>()
        val models = dependency(ModelRepository::class.java) { _ -> AnResult.Ok(listOf(AIModel(id = "local-model", providerId = "custom-local", enabled = true))) }
        val topics = dependency(TopicRepository::class.java) { name ->
            when (name) {
                "createTopic" -> AnResult.Ok("search-topic")
                "listTopics" -> AnResult.Ok(listOf(ChatTopic(id = "search-topic")))
                else -> error("Unexpected topic operation $name")
            }
        }
        val messages = object : MessageRepository {
            override suspend fun listMessages(topicId: String?, agentId: String?) = AnResult.Ok(persisted.toList())
            override suspend fun saveMessage(topicId: String, message: ChatMessage): AnResult<Unit> {
                persisted += message
                return AnResult.Ok(Unit)
            }
        }
        val completions = object : ChatCompletionRepository {
            override fun stream(providerId: String, modelId: String, systemPrompt: String, history: List<ChatMessage>,
                temperature: Double?, sessionId: String?, searchPolicy: WebSearchPolicy, purePrompt: Boolean): Flow<ChatStreamChunk> {
                seenPolicy = searchPolicy
                seenPure = purePrompt
                assertEquals("", systemPrompt)
                return chunks
            }
        }
        val agents = dependency(AgentRepository::class.java) { name ->
            if (name == "getConfig") AnResult.Ok(AgentConfig(webSearch = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"), purePrompt = true)) else error("Unexpected $name")
        }
        val files = dependency(FileRepository::class.java) { error("Unexpected file operation $it") }
        val vm = ChatViewModel(SavedStateHandle(mapOf("agentId" to "search-agent")),
            messages, topics, models, agents, files, completions, preferences, TestSearchProviders())
        val store = ViewModelStore().apply { put("pure", vm) }
        try {
            runCurrent()
            assertEquals(WebSearchMode.OFF, vm.uiState.value.webSearchPolicy.mode)
            assertTrue(vm.uiState.value.webSearchReady)
            assertTrue(vm.uiState.value.webSearchAvailable)
            vm.send("Just answer")
            runCurrent()
            assertEquals(WebSearchMode.OFF, seenPolicy.mode)
            assertTrue(seenPure)
            assertEquals(listOf("Just answer", "Pure answer"), vm.uiState.value.messages.map { it.content })
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun `requested Agent search blocks sending until a search route is configured`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = TestSearchPreferences()
        val providers = TestSearchProviders(emptyList())
        val fixture = searchFixture(preferences, flow {
            emit(ChatStreamChunk(textDelta = "Sourced answer", completion = ChatCompletion(ChatCompletionStatus.COMPLETED)))
        }, agentConfig = { AnResult.Ok(AgentConfig(webSearch = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"))) },
            providers = providers)
        try {
            runCurrent()
            fixture.vm.send("Search this")
            runCurrent()
            assertFalse(fixture.vm.uiState.value.streaming)
            assertTrue(fixture.vm.uiState.value.messages.isEmpty())
            assertNotNull(fixture.vm.uiState.value.error)
            providers.providers.value = listOf(testSearchProvider())
            runCurrent()
            fixture.vm.send("Search this")
            runCurrent()
            assertEquals(listOf("Search this", "Sourced answer"), fixture.vm.uiState.value.messages.map { it.content })
            assertEquals(ChatCompletionStatus.COMPLETED, fixture.vm.uiState.value.messages.last().completion?.status)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `unsupported native mode does not use an enabled external provider`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = searchFixture(TestSearchPreferences(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")),
            flow { error("Unsupported native search must not start inference") },
            agentConfig = { AnResult.Ok(AgentConfig(webSearch = WebSearchPolicy(WebSearchMode.NATIVE))) })
        try {
            runCurrent()
            assertTrue(fixture.vm.uiState.value.webSearchReady)
            assertFalse(fixture.vm.uiState.value.webSearchAvailable)
            fixture.vm.send("Do not fall back")
            runCurrent()
            assertFalse(fixture.vm.uiState.value.streaming)
            assertTrue(fixture.vm.uiState.value.messages.isEmpty())
            assertNotNull(fixture.vm.uiState.value.error)
            assertEquals(WebSearchMode.NATIVE, fixture.vm.uiState.value.webSearchPolicy.mode)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `removing selected provider blocks future sends without changing the Agent choice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val selected = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng")
        val providers = TestSearchProviders()
        val fixture = searchFixture(TestSearchPreferences(), flow { error("Missing search provider must not start inference") },
            agentConfig = { AnResult.Ok(AgentConfig(webSearch = selected)) }, providers = providers)
        try {
            runCurrent()
            assertTrue(fixture.vm.uiState.value.webSearchAvailable)
            providers.providers.value = emptyList()
            runCurrent()
            assertFalse(fixture.vm.uiState.value.webSearchAvailable)
            assertEquals(selected, fixture.vm.uiState.value.webSearchPolicy)
            fixture.vm.send("Keep my explicit selection")
            runCurrent()
            assertTrue(fixture.vm.uiState.value.messages.isEmpty())
            assertNotNull(fixture.vm.uiState.value.error)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `Agent configuration errors do not fall back to global search and explicit refresh recovers`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = TestSearchPreferences(WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"))
        var config: AnResult<AgentConfig> = AnResult.Err(AnError("LOCAL_DATA", "Unavailable Agent configuration"))
        val fixture = searchFixture(preferences, flow { error("Unread Agent configuration must not start chat") },
            agentConfig = { config })
        try {
            runCurrent()
            assertFalse(fixture.vm.uiState.value.webSearchReady)
            assertFalse(fixture.vm.uiState.value.webSearchLoading)
            fixture.vm.send("Do not guess my settings")
            runCurrent()
            assertTrue(fixture.vm.uiState.value.messages.isEmpty())
            assertFalse(fixture.vm.uiState.value.streaming)
            config = AnResult.Ok(AgentConfig(webSearch = WebSearchPolicy()))
            fixture.vm.load()
            runCurrent()
            assertTrue(fixture.vm.uiState.value.webSearchReady)
            assertEquals(WebSearchMode.OFF, fixture.vm.uiState.value.webSearchPolicy.mode)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    @Test
    fun `global read errors block even an enabled Agent and manual reload can retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        val settings = MutableStateFlow(WebSearchSettings())
        val preferences = object : WebSearchPreferences {
            override val webSearchSettings: Flow<WebSearchSettings> = flow {
                if (fail) throw IOException("Storage unavailable")
                settings.collect { emit(it) }
            }
            override suspend fun setWebSearchPolicy(policy: WebSearchPolicy) = error("Chat cannot write global settings")
        }
        val fixture = searchFixture(preferences, flow { error("Unread global settings must not start chat") },
            agentConfig = { AnResult.Ok(AgentConfig(webSearch = WebSearchPolicy(WebSearchMode.PROVIDER, "searxng"))) })
        try {
            runCurrent()
            assertFalse(fixture.vm.uiState.value.webSearchReady)
            assertFalse(fixture.vm.uiState.value.webSearchLoading)
            fixture.vm.send("Wait for configuration")
            runCurrent()
            assertFalse(fixture.vm.uiState.value.streaming)
            assertTrue(fixture.vm.uiState.value.messages.isEmpty())
            fail = false
            fixture.vm.load()
            runCurrent()
            assertTrue(fixture.vm.uiState.value.webSearchReady)
            assertEquals(WebSearchMode.PROVIDER, fixture.vm.uiState.value.webSearchPolicy.mode)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    private data class SearchFixture(val vm: ChatViewModel, val store: ViewModelStore)

    private fun searchFixture(preferences: WebSearchPreferences, chunks: Flow<ChatStreamChunk>,
        agentConfig: (() -> AnResult<AgentConfig>)? = null,
        providers: TestSearchProviders = TestSearchProviders()): SearchFixture {
        val persisted = mutableListOf<ChatMessage>()
        val models = dependency(ModelRepository::class.java) { name ->
            when (name) {
                "listModels" -> AnResult.Ok(listOf(AIModel(id = "local-model", providerId = "custom-local", enabled = true)))
                else -> error("Unexpected model operation $name")
            }
        }
        val topics = dependency(TopicRepository::class.java) { name ->
            when (name) {
                "createTopic" -> AnResult.Ok("search-topic")
                "listTopics" -> AnResult.Ok(listOf(ChatTopic(id = "search-topic")))
                else -> error("Unexpected topic operation $name")
            }
        }
        val messages = object : MessageRepository {
            override suspend fun listMessages(topicId: String?, agentId: String?) = AnResult.Ok(persisted.toList())
            override suspend fun saveMessage(topicId: String, message: ChatMessage): AnResult<Unit> {
                persisted += message
                return AnResult.Ok(Unit)
            }
        }
        val completions = object : ChatCompletionRepository {
            override fun stream(providerId: String, modelId: String, systemPrompt: String, history: List<ChatMessage>,
                temperature: Double?, sessionId: String?, searchPolicy: WebSearchPolicy, purePrompt: Boolean): Flow<ChatStreamChunk> = chunks
        }
        val agents = dependency(AgentRepository::class.java) { name ->
            if (name == "getConfig" && agentConfig != null) agentConfig() else error("Unexpected agent operation $name")
        }
        val files = dependency(FileRepository::class.java) { error("Unexpected file operation $it") }
        val handle = if (agentConfig == null) SavedStateHandle() else SavedStateHandle(mapOf("agentId" to "search-agent"))
        val vm = ChatViewModel(handle, messages, topics, models, agents, files, completions, preferences, providers)
        return SearchFixture(vm, ViewModelStore().apply { put("search", vm) })
    }

    private class TestSearchPreferences(policy: WebSearchPolicy = WebSearchPolicy()) : WebSearchPreferences {
        override val webSearchSettings = MutableStateFlow(WebSearchSettings(policy))
        override suspend fun setWebSearchPolicy(policy: WebSearchPolicy) {
            webSearchSettings.value = WebSearchSettings(policy)
        }
    }

    private class TestSearchProviders(initial: List<WebSearchProvider> = listOf(testSearchProvider())) : WebSearchProviderRepository {
        override val providers = MutableStateFlow(initial)
        override suspend fun apiKey(id: String): String? = error("Chat UI must not read search credentials")
        override suspend fun save(provider: WebSearchProvider, apiKey: String?) = error("Chat cannot write search providers")
        override suspend fun delete(id: String) = error("Chat cannot delete search providers")
    }

    private companion object {
        fun testSearchProvider() = WebSearchProvider("searxng", "SearXNG", WebSearchProtocol.SEARXNG,
            "http://localhost:8888/search", enabled = true)
    }

    private fun <T : Any> dependency(type: Class<T>, operation: (String) -> Any?): T = requireNotNull(type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "ChatDependency(${type.simpleName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> operation(method.name)
            }
        },
    ))
}
