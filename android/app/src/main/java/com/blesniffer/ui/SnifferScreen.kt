package com.blesniffer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blesniffer.GroupDetail
import com.blesniffer.UiState
import com.blesniffer.model.CaptureSource
import com.blesniffer.model.ChannelSelection
import com.blesniffer.model.TimelinePoint
import com.blesniffer.model.ExportFormat
import com.blesniffer.model.PacketGroup
import com.blesniffer.model.TimelineScale
import com.blesniffer.model.ViewMode
import com.blesniffer.ui.theme.SnifferColors

/** Actions the screen needs from the host activity or view model. */
data class SnifferActions(
    val onSource: (CaptureSource) -> Unit,
    val onChannel: (ChannelSelection) -> Unit,
    val onToggleMode: () -> Unit,
    val onAddressFilter: (String) -> Unit,
    val onPduTypeFilter: (String) -> Unit,
    val onRawCapture: (Boolean) -> Unit,
    val onToggleScan: () -> Unit,
    val onExport: (ExportFormat) -> Unit,
    val onOpenFile: () -> Unit,
    val onUpdateFirmware: () -> Unit,
    val onFlashBundledFirmware: (() -> Unit)?,
    val onAboutRecovery: () -> Unit,
    val onShowStats: () -> Unit,
    val onClear: () -> Unit,
    val onOpenGroup: (PacketGroup) -> Unit,
    val onCloseGroup: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onChartVisible: (Boolean) -> Unit,
    val onChartScale: (TimelineScale) -> Unit,
    val onChartAtLastData: (Boolean) -> Unit,
    val onSaveChart: (List<TimelinePoint>, String) -> Unit,
)

@Composable
fun SnifferScreen(
    state: UiState,
    groupDetail: GroupDetail?,
    chartPoints: List<TimelinePoint>,
    channelSelectable: Boolean,
    actions: SnifferActions,
    // Shown between the controls and the list when the selected source needs
    // something from the user - Bluetooth turned on, a permission, a USB device.
    banner: (@Composable () -> Unit)? = null,
) {
    val snackbarHost = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            actions.onDismissMessage()
        }
    }

    if (confirmClear) {
        ClearConfirmationDialog(
            packetCount = state.totalPackets,
            onConfirm = {
                confirmClear = false
                actions.onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // The controls stay put whether or not a group is open, so a capture
            // can be started or stopped without first backing out of whatever is
            // being inspected.
            ControlPanel(
                source = state.source,
                channel = state.channel,
                scanning = state.scanning,
                channelSelectable = channelSelectable,
                busy = state.busy,
                addressFilter = state.addressFilter,
                pduTypeFilter = state.pduTypeFilter,
                pduTypes = state.pduTypes,
                rawCapture = state.rawCapture,
                onSource = actions.onSource,
                onChannel = actions.onChannel,
                onAddressFilter = actions.onAddressFilter,
                onPduTypeFilter = actions.onPduTypeFilter,
                onRawCapture = actions.onRawCapture,
                onToggleScan = actions.onToggleScan,
                onExport = actions.onExport,
                onOpenFile = actions.onOpenFile,
                onUpdateFirmware = actions.onUpdateFirmware,
                onFlashBundledFirmware = actions.onFlashBundledFirmware,
                onAboutRecovery = actions.onAboutRecovery,
                onShowStats = actions.onShowStats,
            )

            StatusStrip(
                state = state,
                onToggleMode = actions.onToggleMode,
                onClear = { confirmClear = true },
            )
            banner?.invoke()
            HorizontalDivider()

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (groupDetail != null) {
                GroupHeader(
                    detail = groupDetail,
                    chartVisible = state.chartVisible,
                    onBack = actions.onCloseGroup,
                    onChartVisible = actions.onChartVisible,
                )

                if (state.chartVisible) {
                    TimelineChart(
                        points = chartPoints,
                        scale = state.chartScale,
                        atLastData = state.chartAtLastData,
                        onScale = actions.onChartScale,
                        onAtLastData = actions.onChartAtLastData,
                        onSavePng = { actions.onSaveChart(chartPoints, groupDetail.group.address) },
                    )
                }
                HorizontalDivider()
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    // Inside a group the same view toggle chooses between every
                    // packet and byte-identical payloads folded together. The
                    // type filter follows the user in here: having narrowed the
                    // list to one type, opening a device and being shown every
                    // type again is not what was asked for.
                    groupDetail != null && state.mode == ViewMode.ACCUMULATED ->
                        PayloadGroupList(
                            groups = groupDetail.payloadGroups
                                .filter { state.matchesType(it.pduType) },
                        )

                    groupDetail != null -> PacketList(
                        packets = if (state.pduTypeFilter.isEmpty()) {
                            groupDetail.packets
                        } else {
                            groupDetail.packets.filter { state.matchesType(it.pduType) }
                        },
                        totalPackets = groupDetail.group.count.toLong(),
                        emptyMessage = if (state.pduTypeFilter.isEmpty()) {
                            "No packets stored for this device."
                        } else {
                            "No ${state.pduTypeFilter} packets from this device."
                        },
                    )

                    state.mode == ViewMode.SEQUENTIAL -> PacketList(
                        packets = state.visiblePackets,
                        totalPackets = state.totalPackets,
                        emptyMessage = if (!state.filtered) {
                            "No packets yet. Press Start to begin capturing."
                        } else {
                            "No packets with ${state.filterDescription}."
                        },
                    )

                    else -> GroupList(
                        groups = state.visibleGroups,
                        onOpen = actions.onOpenGroup,
                        emptyMessage = if (!state.filtered) {
                            "No devices yet. Press Start to begin capturing."
                        } else {
                            "No devices with ${state.filterDescription}."
                        },
                    )
                }
            }
        }
    }
}

/** Identifies the device being inspected and gets back to the list. */
@Composable
private fun GroupHeader(
    detail: GroupDetail,
    chartVisible: Boolean,
    onBack: () -> Unit,
    onChartVisible: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to the device list",
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = detail.group.address.ifEmpty { "(no address)" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${groupDigits(detail.group.count)} packets" +
                    (if (detail.group.crcErrors > 0) {
                        " · ${groupDigits(detail.group.crcErrors)} CRC" +
                            ", ${groupDigits(detail.group.repaired)} repaired"
                    } else {
                        ""
                    }) +
                    (detail.group.name?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Show and hide share one position, so the control does not move around
        // as it is used.
        IconButton(onClick = { onChartVisible(!chartVisible) }) {
            if (chartVisible) {
                ChartOffIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = "Hide the timeline chart",
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = "Show the timeline chart",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ClearConfirmationDialog(
    packetCount: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear the capture?") },
        text = {
            Text(
                if (packetCount > 0) {
                    "This deletes all ${groupDigits(packetCount)} packets in the current session. " +
                        "Export first if you want to keep them."
                } else {
                    "There is nothing captured yet."
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A summary of the capture, sharing its row with the two view actions.
 *
 * The counters wrap. How many there are depends on what the capture has actually
 * produced - repaired and merged totals only appear once they are non-zero - and
 * on a narrow screen the later ones were being pushed off the edge entirely.
 *
 * The icons stay pinned to the right edge while the counters flow around them,
 * so a capture that grows a fourth or fifth counter pushes the numbers onto a
 * second line rather than moving the controls out from under the thumb that has
 * learned where they are. Nothing here explains itself; the prose that used to
 * sit under these numbers is in "How repair works".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusStrip(
    state: UiState,
    onToggleMode: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TotalBadge(state.totalPackets)

                Counter("${groupDigits(state.confirmedDeviceCount)} devices")
                if (state.crcErrors > 0) {
                    Counter("${groupDigits(state.crcErrors)} CRC", MaterialTheme.colorScheme.error)
                }
                if (state.repaired > 0) {
                    Counter("${groupDigits(state.repaired)} repaired", SnifferColors.channelOkText)
                }
                if (state.deduplicated > 0) {
                    Counter("${groupDigits(state.deduplicated)} merged")
                }
                if (state.clockOffsetMs != 0L) {
                    Counter("${state.clockOffsetMs} ms offset")
                }
                Counter(state.status.detail)
            }

            // One icon that flips between the flat list and the per-device
            // grouping, showing the view it will switch to.
            IconButton(onClick = onToggleMode, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (state.mode == ViewMode.SEQUENTIAL) {
                        Icons.Filled.Dataset
                    } else {
                        Icons.AutoMirrored.Filled.FormatListBulleted
                    },
                    contentDescription = if (state.mode == ViewMode.SEQUENTIAL) {
                        "Group by device"
                    } else {
                        "Show every packet"
                    },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "Clear history",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.status.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Counter(text: String, color: androidx.compose.ui.graphics.Color? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}


/**
 * Session packet count. Deliberately grey: it is a running total, not a state
 * the user has to react to, and the colour in this bar belongs to the CRC
 * warning next to it.
 */
@Composable
private fun TotalBadge(total: Long) {
    Text(
        // Digits are grouped in threes; the spaces are also where the number
        // wraps if the row runs out of width.
        text = "${groupDigits(total)} packets",
        style = MaterialTheme.typography.labelMedium,
        // Proportional on purpose: monospace faces frequently have no thin space
        // glyph, which would undo the digit grouping.
        color = SnifferColors.neutralBadgeText,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SnifferColors.neutralBadgeBackground)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Prompt shown when the selected source needs something before it can run. */
@Composable
fun ActionRequiredBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
