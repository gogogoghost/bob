package cc.jaxy.bob.core.designsystem.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Localizable text for ViewModels, which cannot call `stringResource`.
 *
 * Screens resolve at the UI edge via [resolve] (composable) or
 * [resolve] with a [Context] (toasts, snackbars shown from non-composables).
 */
sealed interface UiText {
    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Quantity(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Raw(val text: String) : UiText

    companion object {
        fun rawOrNull(text: String?): UiText? = text?.takeIf { it.isNotBlank() }?.let(::Raw)
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) {
        stringResource(id)
    } else {
        stringResource(id, *args.toTypedArray())
    }
    is UiText.Quantity -> if (args.isEmpty()) {
        pluralStringResource(id, quantity, quantity)
    } else {
        pluralStringResource(id, quantity, *args.toTypedArray())
    }
    is UiText.Raw -> text
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) {
        context.getString(id)
    } else {
        context.getString(id, *args.toTypedArray())
    }
    is UiText.Quantity -> if (args.isEmpty()) {
        context.resources.getQuantityString(id, quantity, quantity)
    } else {
        context.resources.getQuantityString(id, quantity, *args.toTypedArray())
    }
    is UiText.Raw -> text
}
