package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import app.dojazd.model.Line
import app.dojazd.ui.LineBadge
import app.dojazd.ui.WrappingTopBar
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import app.dojazd.ui.vehicleIcon

/**
 * The bar over a line's screens: back, the kind of vehicle and the line's number with [text] beside it,
 * which wraps onto more lines rather than being cut short, and any actions in [content] at the end.
 */
@Composable
internal fun LineHeader(
    line: Line?,
    text: String?,
    onBack: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    WrappingTopBar(onBack, actions = { content() }) {
        if (line != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Icon(
                        painterResource(vehicleIcon(line.type)),
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.medium),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LineBadge(line)
                }
                text?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
internal fun LineTitle(
    line: Line,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        LineBadge(line)
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
