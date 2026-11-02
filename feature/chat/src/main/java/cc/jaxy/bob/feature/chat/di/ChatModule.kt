package cc.jaxy.bob.feature.chat.di

import cc.jaxy.bob.feature.chat.ChatGenerationKeepAlive
import cc.jaxy.bob.feature.chat.ServiceChatGenerationKeepAlive
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {
    @Binds
    @Singleton
    abstract fun bindChatGenerationKeepAlive(impl: ServiceChatGenerationKeepAlive): ChatGenerationKeepAlive
}
