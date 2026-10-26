package cc.jaxy.bob.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cc.jaxy.bob.core.common.preferences.AppLanguage
import cc.jaxy.bob.core.data.preferences.UiPreferencesStore
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import dagger.hilt.android.HiltAndroidApp
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class BobApp : Application() {

    @Inject
    lateinit var uiPreferences: UiPreferencesStore

    private val imageHttpClient by lazy(LazyThreadSafetyMode.NONE) { OkHttpClient() }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundActivity = WeakReference<Activity>(null)
    private var appliedTag: String? = null

    override fun onCreate() {
        super.onCreate()
        removeLegacyAuthData()
        // Coil3 needs an explicit network fetcher for http(s) images.
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(this)
                .components { add(OkHttpNetworkFetcherFactory(imageHttpClient)) }
                // Avatars/logos are tiny and URL-addressed (new URL = new
                // image), so cache aggressively: 25% RAM + 100MB disk.
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(this, 0.25)
                        .build()
                }
                .logger(coil3.util.DebugLogger())
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("coil").toOkioPath())
                        .maxSizeBytes(100L * 1024 * 1024)
                        .build()
                }
                .build()
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Recreate the foreground activity when the language changes at runtime.
        // Startup locale is handled by MainActivity.attachBaseContext.
        appScope.launch {
            uiPreferences.language
                .distinctUntilChanged()
                .drop(1)
                .collect { language ->
                    applyLanguage(language)
                }
        }
    }

    private fun removeLegacyAuthData() {
        val migration = getSharedPreferences("local_data_migrations", MODE_PRIVATE)
        if (migration.getBoolean("legacy_auth_data_removed", false)) return
        val obsoletePrefixes = listOf(
            "server_prefs.preferences_pb",
            "network_cookies.preferences_pb",
            "user.preferences_pb",
        )
        val matchingFiles = filesDir.resolve("datastore").listFiles().orEmpty()
            .filter { file -> obsoletePrefixes.any { prefix -> file.name.startsWith(prefix) } }
        if (matchingFiles.all { !it.exists() || it.delete() }) {
            migration.edit().putBoolean("legacy_auth_data_removed", true).apply()
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        // drop(1) already skipped the startup emission; every emission here
        // is a genuine runtime change → recreate to apply the new locale.
        if (appliedTag == language.tag) return
        appliedTag = language.tag
        foregroundActivity.get()?.recreate()
    }
}
