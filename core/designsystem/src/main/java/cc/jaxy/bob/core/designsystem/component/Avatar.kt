package cc.jaxy.bob.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.SizeResolver
import coil3.toBitmap
import kotlinx.coroutines.launch
import cc.jaxy.bob.core.designsystem.theme.BobTheme

/** Avatar decode target: xxxhdpi 56dp ≈ 224px, rounded up for crispness. */
private const val OUTPUT_PX = 256
@Composable
private fun avatarColors(name: String?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val presets =
        listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
            scheme.errorContainer to scheme.onErrorContainer,
            scheme.surfaceVariant to scheme.onSurfaceVariant,
        )
    val index = ((name?.hashCode() ?: 0) and Int.MAX_VALUE) % presets.size
    return presets[index]
}

// 通用头像：emoji/单字直显；http(s) URL 走 Coil（加载中/失败透出底层回退）；无则首字 + hash 配色
@Composable
fun InitialAvatar(
    name: String?,
    avatarUrl: String? = null,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    /**
     * True while the profile is still loading: shows a neutral blank disc
     * instead of the initial fallback (which would flash before the real
     * avatar). Only show the initial once loading settles without an image.
     */
    loading: Boolean = false,
) {
    val (container, onContainer) = avatarColors(name)
    val trimmed = avatarUrl?.trim().orEmpty()
    // Agent avatar 常为 emoji（如 ✍️）：非 URL 短文本直接渲染，不走图片加载。
    val glyph = trimmed.takeIf { it.isNotEmpty() && !it.isImageUrl() }
        // Keep the circle readable: a stray long string (not an emoji/URL)
        // degrades to the name initial instead of overflowing.
        ?.takeIf { it.codePointCount(0, it.length) <= 4 }
    // 首字：去空格取第一个字符，空名字用 ? 占位
    val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading && glyph == null) {
            // Blank disc with a subtle spinner slot; content arrives shortly.
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(size * 0.45f),
                strokeWidth = 2.dp,
                color = onContainer.copy(alpha = 0.6f),
            )
        } else {
            Text(
                text = glyph ?: initial,
                style = if (glyph != null) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = onContainer,
            )
        }
        if (glyph == null && trimmed.isImageUrl()) {
            val context = LocalContext.current
            // Sync local snapshot first: shows instantly on cold start,
            // then Coil swaps in the full-res image without flashing.
            val snapshot = remember(trimmed) { AvatarCache.get(context, trimmed) }
            if (snapshot != null) {
                androidx.compose.foundation.Image(
                    bitmap = snapshot,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
            // 圆形裁剪 + 裁剪填充；占位/失败时 AsyncImage 无内容，透出底层回退
            // URL 切换时保留旧图直到新图就绪（placeholderMemoryCacheKey 命中
            // 内存中的上一张），避免闪回首字。
            var lastUrl by remember { mutableStateOf<String?>(null) }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(trimmed)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCacheKey(trimmed)
                    .placeholderMemoryCacheKey(lastUrl?.let { coil3.memory.MemoryCache.Key(it) })
                    .listener(
                        onSuccess = { _, result ->
                            lastUrl = trimmed
                            // Persist a thumbnail for instant cold-start paint.
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                runCatching {
                                    AvatarCache.put(context, trimmed, result.image.toBitmap())
                                }
                            }
                        },
                    )
                    .size(SizeResolver(Size(Dimension(OUTPUT_PX), Dimension(OUTPUT_PX))))
                    .crossfade(snapshot == null)
                    .build(),
                contentDescription = null,
                modifier = Modifier.matchParentSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun String.isImageUrl(): Boolean {
    val lower = lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("file://") || lower.startsWith("content://") ||
        lower.startsWith("android.resource://")
}

// 小胶囊：模型名等短标签展示
@Composable
fun ModelBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "Avatar-首字", showBackground = true)
@Composable
private fun InitialAvatarPreview() {
    BobTheme {
        Row(Modifier.padding(16.dp)) {
            InitialAvatar(name = "安罗")
            InitialAvatar(name = null, modifier = Modifier.padding(start = 8.dp))
            // 有 url 时预览环境加载不出图片，透出底层首字即占位/失败回退态
            InitialAvatar(name = "Anlo", avatarUrl = "https://example.com/avatar.png", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Preview(
    name = "Avatar-首字-深色",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun InitialAvatarDarkPreview() {
    BobTheme(darkTheme = true) {
        Row(Modifier.padding(16.dp)) {
            InitialAvatar(name = "Anlo")
            InitialAvatar(name = "", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Preview(name = "Badge", showBackground = true)
@Composable
private fun ModelBadgePreview() {
    BobTheme {
        ModelBadge(text = "gpt-4o", modifier = Modifier.padding(16.dp))
    }
}

@Preview(
    name = "Badge-深色",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ModelBadgeDarkPreview() {
    BobTheme(darkTheme = true) {
        ModelBadge(text = "claude-sonnet", modifier = Modifier.padding(16.dp))
    }
}
