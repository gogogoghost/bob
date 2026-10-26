package cc.jaxy.anlobehub.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cc.jaxy.anlobehub.core.designsystem.R as DsR
import cc.jaxy.anlobehub.core.designsystem.theme.spacing

/**
 * Shared list-filter search box (pill shape). All list screens MUST use
 * this instead of a hand-rolled OutlinedTextField.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    AnTextField(
        value = query,
        onValueChange = onQueryChange,
        label = hint,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.l, vertical = spacing.s),
        leadingIcon = {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(DsR.string.common_clear),
                    )
                }
            }
        },
        singleLine = true,
    )
}
