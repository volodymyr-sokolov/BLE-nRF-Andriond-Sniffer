package com.blesniffer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blesniffer.dfu.DfuProgress

/**
 * Progress of a firmware update.
 *
 * There is no cancel button while bytes are moving: interrupting a DFU leaves
 * the board sitting in its bootloader with a half-written image, and the only
 * honest thing to offer is to wait. The dialog can only be dismissed once the
 * update has finished or failed.
 */
@Composable
fun FirmwareUpdateDialog(progress: DfuProgress, onDismiss: () -> Unit) {
    val finished = progress is DfuProgress.Done || progress is DfuProgress.Failed

    AlertDialog(
        onDismissRequest = { if (finished) onDismiss() },
        title = {
            Text(
                when (progress) {
                    is DfuProgress.Done -> "Firmware installed"
                    is DfuProgress.Failed -> "Update failed"
                    else -> "Updating the board"
                },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (progress) {
                    is DfuProgress.Step -> {
                        Text(progress.message)
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }

                    is DfuProgress.Sending -> {
                        val percent = if (progress.totalBytes == 0) {
                            0
                        } else {
                            100 * progress.sentBytes / progress.totalBytes
                        }
                        Text("Writing image — $percent%")
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                        Text(
                            text = "${groupDigits(progress.sentBytes.toLong())} of " +
                                "${groupDigits(progress.totalBytes.toLong())} bytes",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    is DfuProgress.Done -> Text(
                        "The board has restarted with the new firmware. Reconnect it to " +
                            "start capturing again.",
                    )

                    is DfuProgress.Failed -> Text(
                        // The replug advice only applies once the board has
                        // actually been reset. Offering it for a failure that
                        // happened before anything was touched sends people
                        // hunting for a problem that does not exist.
                        progress.message + if (progress.boardInBootloader) {
                            "\n\nThe board is most likely still in its bootloader. Unplug and " +
                                "replug it, then try again - or flash it from a PC."
                        } else {
                            ""
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (finished) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
