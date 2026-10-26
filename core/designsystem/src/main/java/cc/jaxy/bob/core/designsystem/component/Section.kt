package cc.jaxy.bob.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.jaxy.bob.core.designsystem.theme.BobTheme

// 分组标题：labelLarge + onSurfaceVariant + 统一内边距
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// 设置行：ListItem 封装，leading 图标 + headline/supporting + trailing 默认右箭头，整行可点击
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = modifier.clickable(onClick = onClick),
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null)
        },
        trailingContent = trailing ?: {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
    )
}

@Preview(name = "分组", showBackground = true)
@Composable
private fun SectionPreview() {
    BobTheme {
        Column {
            SectionTitle(text = "外观")
            SettingRow(icon = Icons.Filled.Palette, title = "主题", subtitle = "跟随系统", onClick = {})
            SettingRow(icon = Icons.Filled.Notifications, title = "通知", onClick = {})
        }
    }
}

@Preview(
    name = "分组-深色",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SectionDarkPreview() {
    BobTheme(darkTheme = true) {
        Column {
            SectionTitle(text = "外观")
            SettingRow(icon = Icons.Filled.Palette, title = "主题", subtitle = "深色", onClick = {})
        }
    }
}
