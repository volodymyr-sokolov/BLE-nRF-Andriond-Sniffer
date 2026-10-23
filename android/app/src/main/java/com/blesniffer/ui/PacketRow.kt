package com.blesniffer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blesniffer.model.AdvertisingDecoder
import com.blesniffer.model.BlePacket
import com.blesniffer.model.hexToBytes
import com.blesniffer.model.toHexDump
import com.blesniffer.ui.theme.SnifferColors

/**
 * One packet in the sequential list.
 *
 * Collapsed it shows arrival time, channel, address and signal strength.
 * Expanded it adds the decoded fields, the raw payload and a copy action.
 * Packets that failed CRC stay in the list - they are often the interesting
 * ones - but are marked so they are not mistaken for clean captures.
 */
@Composable
fun PacketRow(
    packet: BlePacket,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatClock(packet.timestampMs),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.width(10.dp))
            ChannelBadge(label = packet.channelLabel, crcOk = packet.crcOk)

            Spacer(Modifier.width(10.dp))
            Text(
                text = packet.addressLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "${packet.rssi}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = SnifferColors.forRssi(packet.rssi),
            )
            Text(
                text = " dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (packet.wasRepaired || packet.corroborated) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (packet.wasRepaired) {
                    Text(
                        text = "repaired · ${packet.recovery?.bitsCorrected ?: 0} bits",
                        style = MaterialTheme.typography.labelSmall,
                        color = SnifferColors.channelOkText,
                    )
                }
                if (packet.corroborated) {
                    Text(
                        text = "both receivers",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (expanded) {
            PacketDetail(packet)
        }
    }
}

/**
 * The channel number doubles as the CRC indicator.
 *
 * A clean packet gets a green badge, a corrupt one keeps the neutral badge the
 * list has always used. That replaces the old "CRC error" caption: the state is
 * legible at a glance down the column without spending a second line on the
 * common case.
 */
@Composable
private fun ChannelBadge(label: String, crcOk: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = if (crcOk) SnifferColors.channelOkText else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (crcOk) SnifferColors.channelOkBackground else MaterialTheme.colorScheme.secondaryContainer,
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * The expanded body of a packet: decoded fields, advertising data and the raw
 * bytes. Shared by the sequential list and the collapsed-payload list, so both
 * show a packet the same way.
 */
@Composable
fun PacketDetail(packet: BlePacket) {
    val clipboard = LocalClipboardManager.current
    val decoded = AdvertisingDecoder.decode(packet)
    val rawDump = packet.rawHex.hexToBytes().toHexDump()

    Column(modifier = Modifier.padding(top = 10.dp)) {
        HorizontalDivider()

        SectionTitle("Reception")
        FieldRow(
            "Received by",
            packet.sources.joinToString(", ") { it.shortLabel } +
                if (packet.corroborated) " (both, deduplicated)" else "",
        )

        packet.recovery?.let { recovery ->
            FieldRow("Repair", recovery.method.label)
            if (recovery.succeeded) {
                FieldRow("Bits corrected", recovery.bitsCorrected.toString())
                FieldRow("Confidence", "%.0f%%".format(recovery.confidence * 100))
            }
            FieldRow("Candidates checked", recovery.candidatesEvaluated.toString())
        }

        for (problem in decoded.problems) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = SnifferColors.crcError,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionTitle("Decoded")
        for (field in decoded.header) {
            FieldRow(field.label, field.value)
        }

        if (decoded.structures.isNotEmpty()) {
            SectionTitle("Advertising data")
            for (field in decoded.structures) {
                FieldRow(field.label, field.value)
            }
        }

        // A repair is shown next to what actually arrived, never instead of it:
        // the specification requires the original to survive untouched so any
        // analysis can be re-run on received bytes only.
        packet.recovery?.takeIf { it.succeeded }?.let { recovery ->
            SectionTitle("Repaired payload")
            Text(
                text = recovery.correctedHex.hexToBytes().toHexDump(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SnifferColors.repairedBackground)
                    .padding(8.dp),
            )
            Text(
                text = "Differences from what was received are highlighted below as the " +
                    "original; both are kept.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionTitle(if (packet.wasRepaired) "Original payload (as received)" else "Raw payload")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The hex takes a weighted share rather than wrapping under the
            // button, so the icon sits beside the payload instead of over it.
            Text(
                text = rawDump.ifEmpty { "(empty)" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = { clipboard.setText(AnnotatedString(clipboardText(packet, decoded))) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy packet to clipboard",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/** A plain text rendering of everything on screen, for pasting elsewhere. */
private fun clipboardText(
    packet: BlePacket,
    decoded: com.blesniffer.model.DecodedPacket,
): String = buildString {
    appendLine("BLE advertising packet")
    appendLine("Time: ${formatClock(packet.timestampMs)} (${packet.timestampMs} ms)")
    if (packet.deviceTimeUs > 0) appendLine("Device time: ${packet.deviceTimeUs} us")
    for (field in decoded.header) appendLine("${field.label}: ${field.value}")
    if (decoded.structures.isNotEmpty()) {
        appendLine()
        appendLine("Advertising data:")
        for (field in decoded.structures) appendLine("  ${field.label}: ${field.value}")
    }
    appendLine()
    appendLine("Raw: ${packet.rawHex}")
    for (problem in decoded.problems) appendLine("! $problem")
}
