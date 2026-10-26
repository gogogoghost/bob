package cc.jaxy.bob.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cc.jaxy.bob.core.common.preferences.AppLanguage
import cc.jaxy.bob.core.data.preferences.UiPreferencesStore
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapWithStoredLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BobAppTheme {
                BobNavHost()
            }
        }
    }

    companion object {
        /**
         * Applies the stored language to a context. Used by [attachBaseContext]
         * (pre-Hilt) and readable anywhere a wrapped context is needed.
         */
        fun wrapWithStoredLocale(base: Context): Context {
            val tag = UiPreferencesStore.readLanguageTagBlocking(base)
                ?: return base
            val locale = AppLanguage.fromTag(tag).toLocale() ?: return base
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.setLocale(locale)
            }
            return base.createConfigurationContext(config)
        }

        private fun AppLanguage.toLocale(): Locale? = when (this) {
            AppLanguage.SYSTEM -> null
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.ENGLISH -> Locale.ENGLISH
        }
    }
}
