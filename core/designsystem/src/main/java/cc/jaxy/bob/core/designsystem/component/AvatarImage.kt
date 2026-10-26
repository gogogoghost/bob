package cc.jaxy.bob.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Avatar image prep: center-crop square, downscale to 512px, WebP ≤ 1MB
 * (mirrors web `MAX_ARTWORK_SIZE`).
 */
object AvatarImage {

    const val SIZE_PX = 512
    const val MAX_BYTES = 1024 * 1024

    data class Prepared(val bytes: ByteArray, val mime: String = "image/webp")

    fun prepare(context: Context, uri: Uri): Prepared? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        return try {
            val cropped = centerCropSquare(bitmap)
            val scaled = if (max(cropped.width, cropped.height) > SIZE_PX) {
                Bitmap.createScaledBitmap(cropped, SIZE_PX, SIZE_PX, true).also {
                    if (it != cropped) cropped.recycle()
                }
            } else {
                cropped
            }
            encodeUnderLimit(scaled).also {
                if (scaled != cropped) scaled.recycle()
                if (cropped != bitmap) cropped.recycle()
                bitmap.recycle()
            }
        } catch (_: Exception) {
            bitmap.recycle()
            null
        }
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val edge = minOf(src.width, src.height)
        if (src.width == edge && src.height == edge) return src
        val x = (src.width - edge) / 2
        val y = (src.height - edge) / 2
        return Bitmap.createBitmap(src, x, y, edge, edge)
    }

    private fun encodeUnderLimit(bitmap: Bitmap): Prepared? {
        var quality = 90
        while (quality >= 30) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) return null
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_BYTES) return Prepared(bytes)
            quality -= 15
        }
        return null
    }
}
