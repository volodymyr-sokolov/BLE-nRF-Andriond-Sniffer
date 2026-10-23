package com.blesniffer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blesniffer.ViewerState
import com.blesniffer.model.PacketGroup
import com.blesniffer.model.ViewMode

/**
 * Read-only view of a capture loaded from a JSON or CSV file.
 *
 * It reuses the same list components as a live capture, so a saved session reads
 * exactly like the one it was exported from - including the grouped view and
 * drilling into a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewer: ViewerState,
    onMode: (ViewMode) -> Unit,
    onClose: () -> Unit,
) {
    var openGroup by remember { mutableStateOf<PacketGroup?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = openGroup?.address ?: viewer.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = openGroup?.let { "${it.count} packets" }
                                ?: "${viewer.packets.size} packets · ${viewer.info.source} · " +
                                "channel ${viewer.info.channel}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (openGroup != null) openGroup = null else onClose() },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = if (openGroup != null) {
                                "Back to the device list"
                            } else {
                                "Close this file"
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (openGroup == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (viewer.mode == ViewMode.SEQUENTIAL) {
                            "Every packet"
                        } else {
                            "Grouped by device"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Same toggle as the live screen, for consistency.
                    IconButton(
                        onClick = {
                            onMode(
                                if (viewer.mode == ViewMode.SEQUENTIAL) {
                                    ViewMode.ACCUMULATED
                                } else {
                                    ViewMode.SEQUENTIAL
                                },
                            )
                        },
                    ) {
                        Icon(
                            imageVector = if (viewer.mode == ViewMode.SEQUENTIAL) {
                                Icons.Filled.Workspaces
                            } else {
                                Icons.AutoMirrored.Filled.List
                            },
                            contentDescription = if (viewer.mode == ViewMode.SEQUENTIAL) {
                                "Group by device"
                            } else {
                                "Show every packet"
                            },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }

            Box(modifier = Modifier.weight(1f)) {
                val group = openGroup
                when {
                    group != null -> PacketList(
                        packets = viewer.packets.filter {
                            it.address.ifEmpty { "(no address)" } == group.address
                        },
                        totalPackets = group.count.toLong(),
                        emptyMessage = "No packets for this device in the file.",
                        live = false,
                    )

                    viewer.mode == ViewMode.SEQUENTIAL -> PacketList(
                        packets = viewer.packets,
                        totalPackets = viewer.packets.size.toLong(),
                        emptyMessage = "This file contains no packets.",
                        live = false,
                    )

                    else -> GroupList(
                        groups = viewer.groups,
                        onOpen = { openGroup = it },
                    )
                }
            }
        }
    }
}
