package cc.jaxy.bob.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.jaxy.bob.core.designsystem.R
import cc.jaxy.bob.core.designsystem.theme.BobTheme

/** 加载中占位：居中菊花圈，label 为空时只显示圈。 */
@Composable
fun LoadingBox(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            if (label != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 空状态占位：图标 + 标题 + 可选描述与操作按钮。 */
@Composable
fun EmptyBox(
    icon: ImageVector,
    title: String,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** 旧签名：转调新版，默认 Inbox 图标。 */
@Composable
fun EmptyBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    EmptyBox(
        icon = Icons.Filled.Inbox,
        title = text,
        modifier = modifier,
    )
}

/** 出错占位：图标 + 文案 + FilledTonal 重试按钮。 */
@Composable
fun ErrorBox(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.ErrorOutline,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingBoxLightPreview() {
    BobTheme(darkTheme = false) {
        LoadingBox(label = "加载中…")
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LoadingBoxDarkPreview() {
    BobTheme(darkTheme = true) {
        LoadingBox()
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyBoxLightPreview() {
    BobTheme(darkTheme = false) {
        EmptyBox(
            icon = Icons.Filled.Inbox,
            title = "暂无收藏",
            description = "看到喜欢的帖子可以先收藏起来",
            actionLabel = "去逛逛",
            onAction = {},
        )
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EmptyBoxDarkPreview() {
    BobTheme(darkTheme = true) {
        EmptyBox(
            icon = Icons.Filled.Inbox,
            title = "暂无内容",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyBoxLegacyLightPreview() {
    BobTheme(darkTheme = false) {
        EmptyBox(text = "列表是空的")
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EmptyBoxLegacyDarkPreview() {
    BobTheme(darkTheme = true) {
        EmptyBox(text = "列表是空的")
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorBoxLightPreview() {
    BobTheme(darkTheme = false) {
        ErrorBox(text = "网络开小差了", onRetry = {})
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ErrorBoxDarkPreview() {
    BobTheme(darkTheme = true) {
        ErrorBox(text = "网络开小差了", onRetry = {})
    }
}
