package cc.jaxy.anlobehub.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 浅色：极光紫主色（取自启动图标背景），粉/青点缀
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF6D4EFB),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE6DEFF),
        onPrimaryContainer = Color(0xFF220F80),
        secondary = Color(0xFF9B3DF5),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF3DFFF),
        onSecondaryContainer = Color(0xFF4A0B7A),
        tertiary = Color(0xFF0B87B8),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD2F2FF),
        onTertiaryContainer = Color(0xFF06394F),
        error = Color(0xFFDC2626),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
        background = Color(0xFFF6F2FF),
        onBackground = Color(0xFF1B1530),
        surface = Color(0xFFFDFCFF),
        onSurface = Color(0xFF1B1530),
        surfaceVariant = Color(0xFFE4DCF5),
        onSurfaceVariant = Color(0xFF4F4763),
        surfaceTint = Color(0xFF6D4EFB),
        inverseSurface = Color(0xFF1B1530),
        inverseOnSurface = Color(0xFFF3F0FF),
        inversePrimary = Color(0xFFC4B0FF),
        surfaceDim = Color(0xFFE4DCF5),
        surfaceBright = Color(0xFFFDFCFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F2FF),
        surfaceContainer = Color(0xFFEFEAFA),
        surfaceContainerHigh = Color(0xFFE4DCF5),
        surfaceContainerHighest = Color(0xFFE9E4FA),
        outline = Color(0xFF8E85A3),
        outlineVariant = Color(0xFFE8E3F5),
        scrim = Color(0xFF120B24),
    )

// 深色：紫调暗底（呼应壁纸暗部）+ 亮紫主色
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFB9A3FF),
        onPrimary = Color(0xFF2A0F7A),
        primaryContainer = Color(0xFF4E2BC4),
        onPrimaryContainer = Color(0xFFEAE2FF),
        secondary = Color(0xFFE08DFF),
        onSecondary = Color(0xFF4A0B5E),
        secondaryContainer = Color(0xFF7A1E9B),
        onSecondaryContainer = Color(0xFFF9E2FF),
        tertiary = Color(0xFF5FD4FF),
        onTertiary = Color(0xFF06334A),
        tertiaryContainer = Color(0xFF0A4A63),
        onTertiaryContainer = Color(0xFFD2F2FF),
        error = Color(0xFFF87171),
        onError = Color(0xFF7F1D1D),
        errorContainer = Color(0xFF7F1D1D),
        onErrorContainer = Color(0xFFFEE2E2),
        background = Color(0xFF14101F),
        onBackground = Color(0xFFF1EDFF),
        surface = Color(0xFF14101F),
        onSurface = Color(0xFFF1EDFF),
        surfaceVariant = Color(0xFF2A2338),
        onSurfaceVariant = Color(0xFFB8AED1),
        surfaceTint = Color(0xFFB9A3FF),
        inverseSurface = Color(0xFFF1EDFF),
        inverseOnSurface = Color(0xFF14101F),
        inversePrimary = Color(0xFF6D4EFB),
        surfaceDim = Color(0xFF0B0812),
        surfaceBright = Color(0xFF2A2338),
        surfaceContainerLowest = Color(0xFF0B0812),
        surfaceContainerLow = Color(0xFF14101F),
        surfaceContainer = Color(0xFF1E1829),
        surfaceContainerHigh = Color(0xFF2A2338),
        surfaceContainerHighest = Color(0xFF2E2740),
        outline = Color(0xFF5C5370),
        outlineVariant = Color(0xFF2A2338),
        scrim = Color(0xFF0B0812),
    )

// 自定义排版：title/display 沿用 M3 默认缩放，只收紧/放宽 body 与 label 行高
private val DefaultTypography = Typography()
private val AnTypography =
    Typography(
        displayLarge = DefaultTypography.displayLarge,
        displayMedium = DefaultTypography.displayMedium,
        displaySmall = DefaultTypography.displaySmall,
        headlineLarge = DefaultTypography.headlineLarge,
        headlineMedium = DefaultTypography.headlineMedium,
        headlineSmall = DefaultTypography.headlineSmall,
        titleLarge = DefaultTypography.titleLarge,
        titleMedium = DefaultTypography.titleMedium,
        titleSmall = DefaultTypography.titleSmall,
        // 正文行高放大，保证中文长文本可读性
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.5.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.25.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
        ),
        // 标签行高收紧，适配按钮/徽标等小字场景
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
    )

/**
 * 应用级主题入口。
 * @param darkTheme 是否深色，默认跟随系统
 * @param dynamicColor 是否在 Android 12+ 启用动态取色，默认关闭
 * （品牌极光紫优先于系统壁纸取色）。
 */
@Composable
fun AnlobehubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Android 12+ 动态取色优先，低版本/关闭时回落到 Lobe 自定义配色
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            if (darkTheme) DarkColors else LightColors
        }
    // 间距 token 随主题下发，各页面经 MaterialTheme.spacing 取用
    CompositionLocalProvider(LocalSpacing provides AnSpacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AnTypography,
            content = content,
        )
    }
}

// 主题预览：浅色
@Preview(name = "Theme-Light")
@Composable
private fun AnlobehubThemeLightPreview() {
    AnlobehubTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("浅色主题", style = MaterialTheme.typography.titleLarge)
            Text("正文行高预览：安罗贝胡，阅读无压力。", style = MaterialTheme.typography.bodyLarge)
            Text("标签预览", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// 主题预览：深色
@Preview(
    name = "Theme-Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AnlobehubThemeDarkPreview() {
    AnlobehubTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("深色主题", style = MaterialTheme.typography.titleLarge)
            Text("正文行高预览：安罗贝胡，阅读无压力。", style = MaterialTheme.typography.bodyLarge)
            Text("标签预览", style = MaterialTheme.typography.labelLarge)
        }
    }
}
