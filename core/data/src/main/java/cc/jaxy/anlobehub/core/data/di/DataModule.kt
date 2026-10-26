package cc.jaxy.anlobehub.core.data.di

import cc.jaxy.anlobehub.core.data.agent.AgentRepository
import cc.jaxy.anlobehub.core.data.agent.AgentRepositoryImpl
import cc.jaxy.anlobehub.core.data.chat.ChatCompletionRepository
import cc.jaxy.anlobehub.core.data.chat.DirectChatCompletionRepository
import cc.jaxy.anlobehub.core.data.chat.MessageRepository
import cc.jaxy.anlobehub.core.data.chat.MessageRepositoryImpl
import cc.jaxy.anlobehub.core.data.chat.ModelRepository
import cc.jaxy.anlobehub.core.data.chat.ModelRepositoryImpl
import cc.jaxy.anlobehub.core.data.chat.TopicRepository
import cc.jaxy.anlobehub.core.data.chat.TopicRepositoryImpl
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.files.FileRepositoryImpl
import cc.jaxy.anlobehub.core.data.preferences.UiPreferencesStore
import cc.jaxy.anlobehub.core.data.preferences.WebSearchPreferences
import cc.jaxy.anlobehub.core.data.provider.AndroidProviderSecretStore
import cc.jaxy.anlobehub.core.data.provider.ProviderRepository
import cc.jaxy.anlobehub.core.data.provider.ProviderRepositoryImpl
import cc.jaxy.anlobehub.core.data.provider.ProviderSecretStore
import cc.jaxy.anlobehub.core.data.search.WebSearchProviderRepository
import cc.jaxy.anlobehub.core.data.search.WebSearchProviderRepositoryImpl
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
    @Binds abstract fun bindWebSearchPreferences(impl: UiPreferencesStore): WebSearchPreferences
    @Binds abstract fun bindWebSearchProviderRepository(impl: WebSearchProviderRepositoryImpl): WebSearchProviderRepository

    companion object {
        @Provides @Singleton fun provideWebSearchClient(): cc.jaxy.anlobehub.core.data.search.WebSearchClient =
            cc.jaxy.anlobehub.core.data.search.WebSearchClient(okhttp3.OkHttpClient())
    }
}
