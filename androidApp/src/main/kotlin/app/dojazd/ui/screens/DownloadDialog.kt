package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.ui.theme.Spacing
import java.util.Locale

private sealed interface DownloadSize {
    data object Checking : DownloadSize

    data object Unknown : DownloadSize

    data class Known(
        val bytes: Long,
    ) : DownloadSize
}

/**
 * Asks before downloading something, telling the user how much data it is.
 * [downloadSize] is looked up when the dialog opens, which is itself a user action.
 */
@Composable
fun DownloadConfirmDialog(
    downloadSize: suspend () -> Long?,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentDownloadSize by rememberUpdatedState(downloadSize)
    val size by produceState<DownloadSize>(initialValue = DownloadSize.Checking) {
        value = currentDownloadSize()?.let(DownloadSize::Known) ?: DownloadSize.Unknown
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Text(text)
                Text(
                    when (val current = size) {
                        DownloadSize.Checking -> stringResource(R.string.download_size_checking)
                        DownloadSize.Unknown -> stringResource(R.string.download_size_unknown)
                        is DownloadSize.Known -> stringResource(R.string.download_size, megabytes(current.bytes))
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000.0)
