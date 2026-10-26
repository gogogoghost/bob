package cc.jaxy.bob.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.jaxy.bob.core.designsystem.component.AnTextField
import cc.jaxy.bob.core.designsystem.theme.spacing

/**
 * Avatar picker: emoji grid first (covers 99%), image URL tucked behind
 * an "advanced" toggle. Mirrors web's EmojiPicker-first artwork flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarPickerSheet(
    current: String,
    uploading: Boolean,
    onPick: (String) -> Unit,
    onUploadClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showUrl by remember { mutableStateOf(false) }
    var url by remember(current) { mutableStateOf(if (current.isImageUrl()) current else "") }
    val spacing = MaterialTheme.spacing
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(
                stringResource(R.string.agents_avatar_pick_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedButton(
                onClick = onUploadClick,
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                    )
                }
                Text(
                    text = if (uploading) {
                        stringResource(R.string.agents_avatar_uploading)
                    } else {
                        stringResource(R.string.agents_avatar_upload)
                    },
                    modifier = Modifier.padding(start = spacing.s),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 48.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(AVATAR_EMOJIS) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (!showUrl) {
                TextButton(onClick = { showUrl = true }) {
                    Text(stringResource(R.string.agents_avatar_custom_url))
                }
            } else {
                AnTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.agents_edit_avatar_label),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.agents_edit_avatar_hint)) },
                    singleLine = true,
                )
                TextButton(
                    onClick = { if (url.isNotBlank()) onPick(url.trim()) },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.agents_avatar_use_url))
                }
            }
        }
    }
}

private fun String.isImageUrl(): Boolean {
    val lower = lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

private val AVATAR_EMOJIS = listOf(
    "🤖", "✨", "🔥", "💡", "🚀", "🎯", "💎", "🌟",
    "🧠", "👾", "🎨", "📚", "💬", "🔍", "⚡", "🌈",
    "🐱", "🐶", "🦊", "🐼", "🦁", "🐸", "🐙", "🦄",
    "👩‍💻", "👨‍💻", "🧑‍🎨", "👩‍🏫", "🧑‍🔬", "👨‍🚀", "🧙", "🦸",
    "📝", "✍️", "📊", "🎵", "🎬", "📷", "🎮", "🏆",
    "❤️", "💪", "👍", "🙏", "👋", "💯", "🎉", "☕",
    "🌸", "🍀", "🌙", "☀️", "🌊", "🍎", "⚽", "🎧",
)
