package cc.jaxy.anlobehub.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Provider brand icon (mirrors web `ProviderIcon` avatar style).
 *
 * Static 144px WebP artwork (light/dark picked by app theme). Falls
 * back to [InitialAvatar] when upstream has no artwork for [providerId] or
 * a custom [logoUrl] should win.
 */
@Composable
fun ProviderIcon(
    providerId: String,
    name: String?,
    logoUrl: String? = null,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    // App-theme-aware (NOT system): -night qualifiers mismatch overrides.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val drawable = providerBrandIcon(providerId, dark)
    if (!logoUrl.isNullOrBlank() || drawable == null) {
        InitialAvatar(
            name = name,
            avatarUrl = logoUrl?.takeIf { it.isNotBlank() },
            size = size,
            modifier = modifier,
        )
        return
    }
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}

/**
 * Built-in web search service brand mark.
 *
 * Self-contained on purpose: core:data must stay UI-free, so the 10
 * catalog [SearchBrand] ids map to hand-drawn vectors here instead of a
 * generated `providerBrandIcon`-style drawable table. Only built-in ids get
 * artwork; custom services keep [InitialAvatar] so they never collide with
 * a built-in brand. [ModelIcon]-style legibility at 40dp over pixel logos.
 */
enum class SearchBrand { TAVILY, BRAVE, EXA, SERPER, SERPAPI, BOCHA, FIRECRAWL, LINKUP, YOU, SEARXNG }

/** Catalog id (builtin or custom) -> brand glyph; unknown/custom ids return null. */
fun searchBrand(providerId: String?, builtin: Boolean = true): SearchBrand? {
    if (!builtin) return null
    return when (providerId?.lowercase()) {
        "tavily" -> SearchBrand.TAVILY
        "brave" -> SearchBrand.BRAVE
        "exa" -> SearchBrand.EXA
        "serper" -> SearchBrand.SERPER
        "serpapi" -> SearchBrand.SERPAPI
        "bocha" -> SearchBrand.BOCHA
        "firecrawl" -> SearchBrand.FIRECRAWL
        "linkup" -> SearchBrand.LINKUP
        "you" -> SearchBrand.YOU
        "searxng" -> SearchBrand.SEARXNG
        else -> null
    }
}

@Composable
fun SearchProviderIcon(
    providerId: String?,
    name: String?,
    builtin: Boolean = true,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val glyph = remember(providerId, builtin) { searchBrand(providerId, builtin) }
    if (glyph == null) {
        InitialAvatar(name = name, size = size, modifier = modifier)
        return
    }
    SearchBrandBadge(glyph = glyph, size = size, modifier = modifier)
}

@Composable
private fun SearchBrandBadge(
    glyph: SearchBrand,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // Stable per-brand container; onBrand ink follows app light/dark theme.
    val (container, ink) = when (glyph) {
        SearchBrand.TAVILY -> Color(0xFF0EA5E9) to Color.White
        SearchBrand.BRAVE -> Color(0xFFFB542B) to Color.White
        SearchBrand.EXA -> Color(0xFF7C3AED) to Color.White
        SearchBrand.SERPER -> Color(0xFF16A34A) to Color.White
        SearchBrand.SERPAPI -> Color(0xFFF59E0B) to Color(0xFF1C1917)
        SearchBrand.BOCHA -> Color(0xFF2563EB) to Color.White
        SearchBrand.FIRECRAWL -> Color(0xFFEA580C) to Color.White
        SearchBrand.LINKUP -> Color(0xFF0D9488) to Color.White
        SearchBrand.YOU -> Color(0xFF1D4ED8) to Color.White
        SearchBrand.SEARXNG -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Canvas(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        onDraw = {
            drawCircle(container)
            val cx = size.toPx() / 2f
            val unit = size.toPx() / 40f
            when (glyph) {
                SearchBrand.TAVILY -> {
                    // Compass needle: exploration/search.
                    val needle = Path().apply {
                        moveTo(cx + 7f * unit, cx - 9f * unit)
                        lineTo(cx - 3f * unit, cx + 3f * unit)
                        lineTo(cx - 7f * unit, cx + 9f * unit)
                        lineTo(cx + 3f * unit, cx - 3f * unit)
                        close()
                    }
                    drawPath(needle, ink)
                    drawCircle(ink, radius = 8.5f * unit, style = Stroke(width = 2.2f * unit))
                }
                SearchBrand.BRAVE -> {
                    // Lion-head abstraction: bold triangle mane + visor gap.
                    val mane = Path().apply {
                        moveTo(cx, cx - 9f * unit)
                        lineTo(cx + 8f * unit, cx - 4f * unit)
                        lineTo(cx + 6f * unit, cx + 7f * unit)
                        lineTo(cx, cx + 9.5f * unit)
                        lineTo(cx - 6f * unit, cx + 7f * unit)
                        lineTo(cx - 8f * unit, cx - 4f * unit)
                        close()
                    }
                    drawPath(mane, ink)
                    drawLine(
                        container,
                        Offset(cx - 4.5f * unit, cx + 0.5f * unit),
                        Offset(cx + 4.5f * unit, cx + 0.5f * unit),
                        strokeWidth = 2.4f * unit,
                        cap = StrokeCap.Round,
                    )
                }
                SearchBrand.EXA -> {
                    // Hexagonal molecule node.
                    val hex = Path().apply {
                        val r = 8f * unit
                        for (i in 0..5) {
                            val a = Math.PI / 3 * i - Math.PI / 6
                            val x = cx + (r * kotlin.math.cos(a)).toFloat()
                            val y = cx + (r * kotlin.math.sin(a)).toFloat()
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawPath(hex, ink, style = Stroke(width = 2.4f * unit))
                    drawCircle(ink, radius = 2.2f * unit)
                }
                SearchBrand.SERPER -> {
                    // Magnifier over bar (serp result).
                    drawCircle(
                        ink,
                        radius = 6f * unit,
                        center = Offset(cx - 2f * unit, cx - 2f * unit),
                        style = Stroke(width = 2.6f * unit),
                    )
                    drawLine(
                        ink,
                        Offset(cx + 2.5f * unit, cx + 2.5f * unit),
                        Offset(cx + 8f * unit, cx + 8f * unit),
                        strokeWidth = 2.8f * unit,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        ink,
                        Offset(cx - 8f * unit, cx + 6f * unit),
                        Offset(cx + 1f * unit, cx + 6f * unit),
                        strokeWidth = 2.2f * unit,
                        cap = StrokeCap.Round,
                    )
                }
                SearchBrand.SERPAPI, SearchBrand.FIRECRAWL -> {
                    // Braces/flame-ish chevrons: API + crawl.
                    val left = Path().apply {
                        moveTo(cx + 1f * unit, cx - 8f * unit)
                        lineTo(cx - 6f * unit, cx)
                        lineTo(cx + 1f * unit, cx + 8f * unit)
                    }
                    val right = Path().apply {
                        moveTo(cx - 1f * unit, cx - 5.5f * unit)
                        lineTo(cx + 4f * unit, cx)
                        lineTo(cx - 1f * unit, cx + 5.5f * unit)
                    }
                    drawPath(left, ink, style = Stroke(width = 2.6f * unit, cap = StrokeCap.Round))
                    drawPath(right, ink, style = Stroke(width = 2.6f * unit, cap = StrokeCap.Round))
                }
                SearchBrand.BOCHA, SearchBrand.LINKUP, SearchBrand.YOU -> {
                    // Chat/result bubble with link nodes.
                    val bubble = Path().apply {
                        moveTo(cx - 8f * unit, cx - 5f * unit)
                        lineTo(cx + 8f * unit, cx - 5f * unit)
                        lineTo(cx + 8f * unit, cx + 4f * unit)
                        lineTo(cx + 1f * unit, cx + 4f * unit)
                        lineTo(cx - 2f * unit, cx + 8f * unit)
                        lineTo(cx - 2f * unit, cx + 4f * unit)
                        lineTo(cx - 8f * unit, cx + 4f * unit)
                        close()
                    }
                    drawPath(bubble, ink, style = Stroke(width = 2.4f * unit))
                    drawCircle(ink, radius = 1.6f * unit, center = Offset(cx - 3.5f * unit, cx - 0.5f * unit))
                    drawCircle(ink, radius = 1.6f * unit, center = Offset(cx + 0.5f * unit, cx - 0.5f * unit))
                    drawCircle(ink, radius = 1.6f * unit, center = Offset(cx + 4.5f * unit, cx - 0.5f * unit))
                }
                SearchBrand.SEARXNG -> {
                    // Meta-search fan: three outgoing rays from a node.
                    drawCircle(ink, radius = 2.6f * unit)
                    val rays = listOf(
                        Offset(cx - 8f * unit, cx - 6f * unit) to Offset(cx - 3f * unit, cx - 2f * unit),
                        Offset(cx + 8f * unit, cx - 6f * unit) to Offset(cx + 3f * unit, cx - 2f * unit),
                        Offset(cx, cx + 9f * unit) to Offset(cx, cx + 3f * unit),
                    )
                    rays.forEach { (from, to) ->
                        drawLine(ink, from, to, strokeWidth = 2.4f * unit, cap = StrokeCap.Round)
                    }
                }
            }
        },
    )
}
