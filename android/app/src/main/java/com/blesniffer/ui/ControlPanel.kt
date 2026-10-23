package com.blesniffer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blesniffer.model.CaptureSource
import com.blesniffer.model.ChannelSelection
import com.blesniffer.model.ExportFormat
import com.blesniffer.ui.theme.SnifferColors

/**
 * Everything that drives a capture, gathered at the top of the screen: where
 * packets come from, which channel to listen on, how to display them, and the
 * start, export and clear actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPanel(
    source: CaptureSource,
    channel: ChannelSelection,
    scanning: Boolean,
    channelSelectable: Boolean,
    busy: Boolean,
    addressFilter: String,
    pduTypeFilter: String,
    pduTypes: List<String>,
    rawCapture: Boolean,
    onSource: (CaptureSource) -> Unit,
    onChannel: (ChannelSelection) -> Unit,
    onAddressFilter: (String) -> Unit,
    onPduTypeFilter: (String) -> Unit,
    onRawCapture: (Boolean) -> Unit,
    onToggleScan: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onOpenFile: () -> Unit,
    onUpdateFirmware: () -> Unit,
    onFlashBundledFirmware: (() -> Unit)?,
    onAboutRecovery: () -> Unit,
    onShowStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Everything that decides what is being captured, in the order the
        // decisions are made: which radio, how it listens, where, then go.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MenuButton(
                label = source.label,
                options = CaptureSource.entries.map { it.label to it },
                onSelect = onSource,
            )

            // Only the board has capture modes; the phone's radio hands over a
            // parsed result and nothing can change that. Sits next to the source
            // because it is a property of that source, not of the view.
            if (channelSelectable) {
                CaptureModeToggle(rawCapture = rawCapture, onChange = onRawCapture)
            }

            MenuButton(
                label = "Ch ${channel.label}",
                enabled = channelSelectable,
                options = ChannelSelection.entries.map { it.label to it },
                onSelect = onChannel,
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onToggleScan,
                colors = if (scanning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = SnifferColors.startContainer,
                        contentColor = SnifferColors.onStartContainer,
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    imageVector = if (scanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (scanning) "Stop" else "Start")
            }
        }

        // Everything that narrows what is shown, plus the tool menu. The address
        // field takes the rest of the width: it has to hold a whole MAC address
        // without scrolling, which is the only thing anyone types into it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AddressFilterField(
                value = addressFilter,
                onValue = onAddressFilter,
                modifier = Modifier.weight(1f),
            )

            // Types are offered from what the capture has actually produced, so
            // before anything arrives there is nothing to choose between. Capped
            // tightly: the longest label is a whole line on its own, and the
            // address field beside it needs 17 monospace characters.
            MenuButton(
                label = pduTypeFilter.ifEmpty { "All types" },
                enabled = pduTypes.isNotEmpty(),
                modifier = Modifier.widthIn(max = 112.dp),
                options = listOf("All types" to "") + pduTypes.map { it to it },
                onSelect = onPduTypeFilter,
            )

            ToolsButton(
                busy = busy,
                onExport = onExport,
                onOpenFile = onOpenFile,
                onUpdateFirmware = onUpdateFirmware,
                onFlashBundledFirmware = onFlashBundledFirmware,
                onAboutRecovery = onAboutRecovery,
                onShowStats = onShowStats,
            )
        }
    }
}

/**
 * Switches the board between its two capture modes.
 *
 * RAW is what makes CRC repair possible: the board stops checking and stripping
 * the CRC and sends the bytes exactly as they arrived, leaving the host to
 * dewhiten and verify. STD is the parsed mode the board boots in, where the
 * radio does that work and the CRC never reaches the app.
 *
 * Deliberately a labelled control rather than a menu entry: which mode is
 * running changes what the app can do with a corrupt packet, so it should be
 * readable without opening anything.
 */
@Composable
private fun CaptureModeToggle(rawCapture: Boolean, onChange: (Boolean) -> Unit) {
    val border = ButtonDefaults.outlinedButtonBorder(enabled = true)

    Text(
        text = if (rawCapture) "RAW" else "STD",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = if (rawCapture) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (rawCapture) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier.border(border, RoundedCornerShape(20.dp))
                },
            )
            .clickable { onChange(!rawCapture) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

/**
 * Narrows both views to advertisers whose address contains what is typed.
 *
 * Built from a BasicTextField rather than an OutlinedTextField: the Material
 * field has a 56dp minimum height that towers over the buttons beside it and
 * cannot be reduced without replacing its decoration anyway. This matches the
 * outlined buttons in the row - same grey outline, same corner radius, same
 * height.
 */
@Composable
private fun AddressFilterField(
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Taken from the outlined buttons rather than picked to look similar, so the
    // field cannot drift away from them as the theme changes.
    val border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .height(38.dp)
            .border(border, RoundedCornerShape(20.dp))
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The magnifier says what an empty field is for. Once there is an
        // address in it the field says that itself, and those 22dp are the
        // difference between a whole MAC fitting and the first octets scrolling
        // out of sight on a narrow screen.
        if (value.isEmpty()) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = "Filter address",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (value.isNotEmpty()) {
            IconButton(onClick = { onValue("") }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear the filter",
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * A button that opens a menu of choices, used for source, channel and type.
 *
 * The menu sits in a Box beside the button rather than inside the button's own
 * content. A DropdownMenu is a Popup - a real second window - and it anchors
 * itself to wherever it was declared: nested in the button's row it anchored to
 * a point *after the label and the arrow*, so the menu opened shifted across
 * and hanging down over the controls below instead of under the button it
 * belongs to.
 */
@Composable
private fun <T> MenuButton(
    label: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // The Box wraps its content, so a caller that stretched the old button with
    // a weight has to say it wants the button stretched too - otherwise the
    // button keeps its natural width inside a box that is now wider than it is.
    fill: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((text, value) in options) {
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/**
 * The app's tool menu: export, statistics, help, reopening a capture, firmware.
 *
 * Carries a gear rather than the download arrow it used to. Only two of its
 * seven entries save anything; the rest are what the app can do rather than
 * where it can write, and an arrow pointing down at all of them misdescribed
 * the menu badly enough that the statistics screen was hard to find.
 */
@Composable
private fun ToolsButton(
    busy: Boolean,
    onExport: (ExportFormat) -> Unit,
    onOpenFile: () -> Unit,
    onUpdateFirmware: () -> Unit,
    onFlashBundledFirmware: (() -> Unit)?,
    onAboutRecovery: () -> Unit,
    onShowStats: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    // Menu outside the button's content, for the reason given on MenuButton.
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = !busy,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            // No drop-down arrow beside the gear. It shares a row with the
            // address field, which has to hold seventeen monospace characters,
            // and those 24dp buy more than a second hint that a gear opens
            // something does.
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Tools and export",
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (format in ExportFormat.entries) {
                DropdownMenuItem(
                    text = { Text("Save as ${format.label}") },
                    onClick = {
                        open = false
                        onExport(format)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Recovery statistics…") },
                onClick = {
                    open = false
                    onShowStats()
                },
            )
            DropdownMenuItem(
                text = { Text("How repair works…") },
                onClick = {
                    open = false
                    onAboutRecovery()
                },
            )
            DropdownMenuItem(
                text = { Text("Open a saved capture…") },
                onClick = {
                    open = false
                    onOpenFile()
                },
            )
            HorizontalDivider()
            if (onFlashBundledFirmware != null) {
                DropdownMenuItem(
                    // Which version is bundled is stated in "How repair works",
                    // beside what the two capture modes do with it. In a menu
                    // label it was a number with nothing to compare it against.
                    text = { Text("Flash bundled firmware") },
                    onClick = {
                        open = false
                        onFlashBundledFirmware()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Update from a file…") },
                onClick = {
                    open = false
                    onUpdateFirmware()
                },
            )
        }
    }
}
