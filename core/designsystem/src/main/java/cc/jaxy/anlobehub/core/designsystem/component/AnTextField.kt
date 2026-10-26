package cc.jaxy.anlobehub.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.jaxy.anlobehub.core.designsystem.R
import cc.jaxy.anlobehub.core.designsystem.theme.AnlobehubTheme

// 通用输入框：filled 风格 —— 无左右边框，以 surfaceContainerHigh 背景分区，
// 形状取 MaterialTheme.shapes.large。error 态保留红色指示线 + supporting。
@Composable
fun AnTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    readOnly: Boolean = false,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.clip(MaterialTheme.shapes.large),
        label = { Text(label) },
        placeholder = placeholder,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        readOnly = readOnly,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

// 密码输入框：内置可见性切换，可见态用 rememberSaveable 保持（旋转/重建不丢失）
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AnTextField(
        value = value,
        onValueChange = onValueChange,
        label = label ?: stringResource(R.string.ds_password_label),
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.ds_password_hide else R.string.ds_password_show),
                )
            }
        },
        supportingText = supportingText,
        isError = isError,
        singleLine = true,
        keyboardActions = keyboardActions,
        enabled = enabled,
    )
}

@Preview(name = "输入框", showBackground = true)
@Composable
private fun AnTextFieldPreview() {
    AnlobehubTheme {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            AnTextField(value = "hello", onValueChange = {}, label = "昵称")
            AnTextField(
                value = "",
                onValueChange = {},
                label = "邮箱",
                supportingText = { Text("我们不会分享你的邮箱") },
            )
            PasswordField(value = "secret123", onValueChange = {})
        }
    }
}

@Preview(
    name = "输入框-深色",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AnTextFieldDarkPreview() {
    AnlobehubTheme(darkTheme = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            AnTextField(value = "", onValueChange = {}, label = "昵称", isError = true, supportingText = { Text("昵称不能为空") })
            PasswordField(value = "secret123", onValueChange = {}, isError = true, supportingText = { Text("密码至少 6 位") })
        }
    }
}
