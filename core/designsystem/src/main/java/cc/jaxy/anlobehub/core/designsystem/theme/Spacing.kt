package cc.jaxy.anlobehub.core.designsystem.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 全局间距 token，从 2dp 到 32dp 共 8 档
@Immutable
data class AnSpacing(
    val xs2: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
)

// 间距 CompositionLocal，主题内 Provide 默认值
val LocalSpacing = staticCompositionLocalOf { AnSpacing() }

// 经 MaterialTheme 取用：MaterialTheme.spacing.m
val MaterialTheme.spacing: AnSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current

/**
 * 输入行/选择器行的容器色：surfaceContainerHighest。浅色柔灰紫、深色灰紫，
 * 与 surface 页面底拉开一档。App-theme-aware（NOT system）。
 */
val MaterialTheme.inputRowContainer: androidx.compose.ui.graphics.Color
    @Composable
    @ReadOnlyComposable
    get() = colorScheme.surfaceContainerHighest

// 间距预览：浅色
@Preview(name = "Spacing-Light")
@Composable
private fun SpacingLightPreview() {
    AnlobehubTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
        ) {
            Text("间距 s=8 l=16")
            Surface {
                Box(modifier = Modifier.size(MaterialTheme.spacing.xxl))
            }
        }
    }
}

// 间距预览：深色
@Preview(
    name = "Spacing-Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SpacingDarkPreview() {
    AnlobehubTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
        ) {
            Text("间距 s=8 l=16")
            Surface {
                Box(modifier = Modifier.size(MaterialTheme.spacing.xxl))
            }
        }
    }
}
