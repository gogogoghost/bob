package cc.jaxy.bob.core.data.di

import cc.jaxy.bob.core.data.agent.AgentRepository
import cc.jaxy.bob.core.data.agent.AgentRepositoryImpl
import cc.jaxy.bob.core.data.chat.ChatCompletionRepository
import cc.jaxy.bob.core.data.chat.DirectChatCompletionRepository
import cc.jaxy.bob.core.data.chat.MessageRepository
import cc.jaxy.bob.core.data.chat.MessageRepositoryImpl
import cc.jaxy.bob.core.data.chat.ModelRepository
import cc.jaxy.bob.core.data.chat.ModelRepositoryImpl
import cc.jaxy.bob.core.data.chat.TopicRepository
import cc.jaxy.bob.core.data.chat.TopicRepositoryImpl
import cc.jaxy.bob.core.data.files.FileRepository
import cc.jaxy.bob.core.data.files.FileRepositoryImpl
import cc.jaxy.bob.core.data.preferences.UiPreferencesStore
import cc.jaxy.bob.core.data.provider.AndroidProviderSecretStore
import cc.jaxy.bob.core.data.provider.ProviderRepository
import cc.jaxy.bob.core.data.provider.ProviderRepositoryImpl
import cc.jaxy.bob.core.data.provider.ProviderSecretStore
import cc.jaxy.bob.core.data.search.WebSearchProviderRepository
import cc.jaxy.bob.core.data.search.WebSearchProviderRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun bindAgentRepository(impl: AgentRepositoryImpl): AgentRepository
    @Binds abstract fun bindTopicRepository(impl: TopicRepositoryImpl): TopicRepository
    @Binds abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository
    @Binds abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository
    @Binds abstract fun bindProviderRepository(impl: ProviderRepositoryImpl): ProviderRepository
    @Binds abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository
    @Binds abstract fun bindProviderSecretStore(impl: AndroidProviderSecretStore): ProviderSecretStore
    @Binds abstract fun bindChatCompletionRepository(impl: DirectChatCompletionRepository): ChatCompletionRepository
    @Binds abstract fun bindWebSearchProviderRepository(impl: WebSearchProviderRepositoryImpl): WebSearchProviderRepository

    companion object {
        @Provides @Singleton fun provideWebSearchClient(): cc.jaxy.bob.core.data.search.WebSearchClient =
            cc.jaxy.bob.core.data.search.WebSearchClient(okhttp3.OkHttpClient())
    }
}
