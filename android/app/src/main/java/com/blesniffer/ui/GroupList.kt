package com.blesniffer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blesniffer.model.PacketGroup
import com.blesniffer.model.PayloadGroup
import com.blesniffer.ui.theme.SnifferColors

/**
 * The accumulation view: one row per advertiser address, ordered by how many
 * packets it has sent. Tapping a row drills into that advertiser's packets.
 */
@Composable
fun GroupList(
    groups: List<PacketGroup>,
    onOpen: (PacketGroup) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No devices yet. Press Start to begin capturing.",
) {
    // Ages here update every AGE_HOLD_MS so each reading is legible; see
    // rememberTicker.
    val now by rememberTicker(AGE_HOLD_MS)

    Box(modifier = modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            return@Box
        }

        // Addresses and signal levels here are the values most worth copying,
        // so the device list is selectable too.
        SelectionContainer {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it.address }) { group ->
                    GroupRow(group = group, now = now, onClick = { onOpen(group) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: PacketGroup, now: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountBadge(group.count)

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    group.isDamagedBucket -> "Unidentified damaged packets"
                    group.address.isEmpty() -> "(no address)"
                    else -> group.address
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (group.isDamagedBucket) {
                    FontFamily.Default
                } else {
                    FontFamily.Monospace
                },
                fontWeight = FontWeight.Medium,
                color = if (group.confirmed) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (group.isDamagedBucket) {
                    Text(
                        text = "${groupDigits(group.mergedDevices)} addresses never confirmed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                group.name?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (group.crcErrors > 0) {
                    Text(
                        text = "${groupDigits(group.crcErrors)} CRC",
                        style = MaterialTheme.typography.labelSmall,
                        color = SnifferColors.crcError,
                    )
                }
                // Beside the error count rather than subtracted from it: what is
                // worth knowing about a device heard through interference is how
                // much of the damage came back, and that needs both numbers.
                if (group.repaired > 0) {
                    Text(
                        text = "${groupDigits(group.repaired)} repaired",
                        style = MaterialTheme.typography.labelSmall,
                        color = SnifferColors.channelOkText,
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${group.lastRssi} dBm",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = SnifferColors.forRssi(group.lastRssi),
                textAlign = TextAlign.End,
            )
            Text(
                text = formatAge(now - group.lastTimestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Show packets",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Byte-identical payloads within one advertiser, collapsed.
 *
 * A beacon that repeats the same frame produces hundreds of rows that differ
 * only in RSSI; folding them makes the handful of genuinely distinct frames
 * visible. Tapping one expands the representative packet exactly as in the
 * sequential list.
 */
@Composable
fun PayloadGroupList(
    groups: List<PayloadGroup>,
    modifier: Modifier = Modifier,
) {
    // Ages here update every AGE_HOLD_MS so each reading is legible; see
    // rememberTicker.
    val now by rememberTicker(AGE_HOLD_MS)
    var expanded by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            Text(
                text = "No packets stored for this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            return@Box
        }

        SelectionContainer {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it.key }) { group ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (expanded == group.key) null else group.key
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CountBadge(group.count)
                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    // The merged row stands for frames that are
                                    // all different, so showing one of them as
                                    // its title would misrepresent the rest.
                                    text = if (group.merged) {
                                        "Corrupt packets"
                                    } else {
                                        group.rawHex.chunked(2).joinToString(" ").take(48)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = if (group.merged) {
                                        FontFamily.Default
                                    } else {
                                        FontFamily.Monospace
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        if (group.merged) {
                                            append("${groupDigits(group.distinctPayloads)} ")
                                            append("distinct frames")
                                            if (group.repaired > 0) {
                                                append(" · ${groupDigits(group.repaired)} repaired")
                                            }
                                        } else {
                                            append(group.pduType.ifEmpty { "payload" })
                                        }
                                        if (group.channels.isNotEmpty()) {
                                            append(" · ch ")
                                            append(group.channels.sorted().joinToString("/"))
                                        }
                                        if (!group.crcOk && !group.merged) append(" · CRC error")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (group.crcOk) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        SnifferColors.crcError
                                    },
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${group.lastRssi} dBm",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = SnifferColors.forRssi(group.lastRssi),
                                    textAlign = TextAlign.End,
                                )
                                Text(
                                    text = formatAge(now - group.lastTimestampMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }

                        if (expanded == group.key) {
                            if (group.merged) {
                                Text(
                                    text = "Damage lands somewhere different in every packet, " +
                                        "so no two of these are alike. The most recent one:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            PacketDetail(group.representative)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/**
 * Packet count for a group. Grey rather than accented: it is a quantity, not a
 * warning, and the accent colour in these rows belongs to the CRC marker.
 */
@Composable
private fun CountBadge(count: Int) {
    Text(
        text = groupDigits(count),
        style = MaterialTheme.typography.labelLarge,
        // Proportional: see the note on the session total badge.
        color = SnifferColors.neutralBadgeText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SnifferColors.neutralBadgeBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .widthIn(min = 40.dp, max = 64.dp),
    )
}
