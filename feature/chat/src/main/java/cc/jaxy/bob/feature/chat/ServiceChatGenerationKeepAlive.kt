package cc.jaxy.bob.feature.chat

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [ChatGenerationService] for one answer. Failures are swallowed: a blocked foreground
 * start (background start restriction) must never break generation.
 */
@Singleton
class ServiceChatGenerationKeepAlive @Inject constructor(@ApplicationContext private val context: Context) :
    ChatGenerationKeepAlive {
    override fun start() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ChatGenerationService::class.java))
        }
    }

    override fun stop() {
        runCatching { context.stopService(Intent(context, ChatGenerationService::class.java)) }
    }
}
