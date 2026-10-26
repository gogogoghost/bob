package cc.jaxy.bob.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import cc.jaxy.bob.core.designsystem.theme.BobTheme

/** 下拉刷新容器：M3 PullToRefreshBox 的薄封装，状态内部 remember。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState(),
        modifier = modifier,
        content = { content() },
    )
}

@Preview(showBackground = true)
@Composable
private fun RefreshBoxLightPreview() {
    BobTheme(darkTheme = false) {
        RefreshBox(refreshing = false, onRefresh = {}) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("下拉刷新试试")
            }
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun RefreshBoxDarkPreview() {
    BobTheme(darkTheme = true) {
        RefreshBox(refreshing = true, onRefresh = {}) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("正在刷新…")
            }
        }
    }
}
