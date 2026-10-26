package cc.jaxy.bob.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest

/**
 * Synchronous local avatar store. Coil (memory+disk) is async by design,
 * so cold start always flashes the initial for one frame. This keeps a
 * 112px WebP per URL under filesDir that loads in <5ms on first
 * composition — the avatar shows instantly like a local glyph.
 */
object AvatarCache {

    private const val DIR = "avatars"
    private const val EDGE = 112

    private fun fileFor(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(File(context.filesDir, DIR).apply { mkdirs() }, "$hash.webp")
    }

    /** Synchronous read; null on miss. Call inside remember. */
    fun get(context: Context, url: String): ImageBitmap? {
        return try {
            val file = fileFor(context, url)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downscales + persists (call off the UI thread). Never recycles the
     * input: it may be Coil's shared reference (recycling crashes draw).
     */
    fun put(context: Context, url: String, bitmap: Bitmap) {
        try {
            if (bitmap.isRecycled) return
            val edge = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - edge) / 2
            val y = (bitmap.height - edge) / 2
            val scaled = Bitmap.createScaledBitmap(
                Bitmap.createBitmap(bitmap, x, y, edge, edge),
                EDGE, EDGE, true,
            )
            fileFor(context, url).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            }
        } catch (_: Exception) {
            // best-effort
        }
    }
}
