package cc.jaxy.bob.core.designsystem.theme

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// 点击反馈：按钮/卡片等明确点击行为后调用
fun hapticClick(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

// 轻 tick：开关、选择、滑动到刻度等细微反馈
fun hapticTick(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

// 重反馈：删除确认、长按触发等需要强调的场景
fun hapticHeavy(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

// 触觉预览：浅色
@Preview(name = "Haptics-Light")
@Composable
private fun HapticsLightPreview() {
    BobTheme(darkTheme = false) {
        val view = LocalView.current
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("触觉反馈示例")
            Button(onClick = { hapticClick(view) }) {
                Text("点击反馈")
            }
        }
    }
}

// 触觉预览：深色
@Preview(
    name = "Haptics-Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HapticsDarkPreview() {
    BobTheme(darkTheme = true) {
        val view = LocalView.current
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("触觉反馈示例")
            Button(onClick = { hapticClick(view) }) {
                Text("点击反馈")
            }
        }
    }
}
