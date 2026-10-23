package com.blesniffer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blesniffer.model.MethodDetail
import com.blesniffer.model.RecoveryMethod
import com.blesniffer.model.RecoveryStats
import com.blesniffer.model.SourceKind
import com.blesniffer.ui.theme.SnifferColors
import kotlin.math.roundToInt

/**
 * Session-wide view of what recovery actually managed.
 *
 * Deliberately shows refusals as prominently as repairs. A pipeline that repairs
 * 10% of corrupt packets and refuses the rest is working correctly if the rest
 * were unrepairable, and the only way to tell the difference is to see both
 * numbers side by side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryStatsScreen(stats: RecoveryStats, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery statistics") },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item { Headline(stats) }

                if (stats.crcErrors == 0) {
                    item {
                        Text(
                            text = "No corrupt packets in this session, so nothing needed " +
                                "repairing. Only the nRF52840 can see them at all - the " +
                                "phone's controller discards them before the app is told - " +
                                "and the algebra that repairs them needs the received " +
                                "checksum, which arrives only in the board's RAW mode.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    return@LazyColumn
                }

                breakdown(
                    "By repair method",
                    "Which layer of the pipeline committed to the answer, offered strongest " +
                        "first: CRC algebra, then a clean copy of the same frame, then the " +
                        "device's byte template, then the learned prior. A session weighted " +
                        "towards the top of this list is one to trust; weight at the bottom " +
                        "means the repairs rest on what bytes are usual rather than on a " +
                        "checksum or a second reception. \"Not recoverable\" is a result " +
                        "too - it is the pipeline refusing to guess.",
                    RecoveryMethod.entries.mapNotNull { method ->
                        val n = stats.byMethod[method.id] ?: return@mapNotNull null
                        method.label to n
                    },
                    total = stats.crcErrors,
                )

                if (stats.methodDetail.isNotEmpty()) {
                    item {
                        Section("What each method contributed")
                        Text(
                            text = "Share of all repairs, and what each one cost. A method " +
                                "that fires often but rewrites many bits is doing more " +
                                "guessing than one that changes a single bit.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        for (detail in stats.methodDetail.sortedByDescending { it.count }) {
                            MethodCard(detail, stats.repaired)
                        }
                    }
                }

                byChannel(stats)

                breakdown(
                    "By receiver",
                    "Corrupt packets only - how much each receiver captured altogether is " +
                        "already above, by channel. The phone's controller never reports a " +
                        "corrupt packet, so every error here comes from the board.",
                    SourceKind.entries.mapNotNull { source ->
                        if (!stats.bySource.containsKey(source.id)) return@mapNotNull null
                        source.shortLabel to (stats.errorsBySource[source.id] ?: 0)
                    },
                    total = stats.crcErrors,
                )

                if (stats.bitsCorrected.isNotEmpty()) {
                    breakdown(
                        "Bits corrected per repair",
                        "How much had to change. One or two bits is what syndrome decoding " +
                            "can prove; beyond that the answer came from somewhere other " +
                            "than the algebra and is worth correspondingly less.",
                        stats.bitsCorrected.entries
                            .sortedBy { it.key.toIntOrNull() ?: 0 }
                            .map { (bits, n) -> "$bits bits" to n },
                        total = stats.repaired.coerceAtLeast(1),
                    )
                }

                item {
                    Section("Confidence")
                    Text(
                        text = if (stats.repaired == 0) {
                            "No repairs to report."
                        } else {
                            "Average %.0f%%, ranging %.0f%% to %.0f%%. "
                                .format(
                                    stats.averageConfidence * 100,
                                    stats.minConfidence * 100,
                                    stats.maxConfidence * 100,
                                ) +
                                "This is the pipeline's own reading of how much evidence it " +
                                "had, not a measured error rate: it orders repairs sensibly " +
                                "but does not promise that one at 90% is wrong one time in " +
                                "ten. Nothing here reaches certainty - even a CRC-verified " +
                                "repair of two bits carries a small chance the checksum " +
                                "agreed by accident."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                item {
                    Section("Cross-checked")
                    Text(
                        text = "${groupDigits(stats.corroborated)} packets were seen by both receivers " +
                            "independently. Those are the ones where one radio can vouch " +
                            "for the other.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * One method's contribution: its share of repairs, then the detail that says how
 * much work it had to do to get there.
 */
@Composable
private fun MethodCard(detail: MethodDetail, totalRepairs: Int) {
    val label = RecoveryMethod.fromId(detail.method)?.label ?: detail.method
    val share = if (totalRepairs <= 0) 0f else detail.count.toFloat() / totalRepairs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.0f%%".format(share * 100),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = buildString {
                append("${groupDigits(detail.count)} packets")
                append("  ·  ")
                append(
                    if (detail.minBits == detail.maxBits) {
                        "${groupDigits(detail.maxBits)} bits changed"
                    } else {
                        "%.1f bits average, ".format(detail.averageBits) +
                            "${groupDigits(detail.minBits)} to ${groupDigits(detail.maxBits)}"
                    },
                )
                append("  ·  ")
                append("%.0f%% confidence".format(detail.averageConfidence * 100))
                if (detail.averageCandidates >= 1f) {
                    append("  ·  ${groupDigits(detail.averageCandidates.roundToInt())} ")
                    append("candidates weighed")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Headline(stats: RecoveryStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) {
        Text(
            text = "${groupDigits(stats.crcErrors)} corrupt of " +
                "${groupDigits(stats.total)} captured",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "${groupDigits(stats.repaired)} repaired " +
                "(%.1f%%)".format(stats.repairRate * 100) +
                ", ${groupDigits(stats.unrecoverable)} left flagged",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Everything the session knows about a channel, on one row.
 *
 * Errors and repairs used to be two separate lists further apart than a screen
 * is tall, which meant reading a channel's story in two places and holding the
 * ordering in your head to line them up - and the question people actually ask
 * of these numbers ("this channel is noisy, did we get any of it back?") needed
 * both. Channels that carried traffic without a single error are listed too:
 * that a channel is clean is as much a result as that it is not.
 */
private fun LazyListScope.byChannel(stats: RecoveryStats) {
    val channels = (stats.totalByChannel.keys + stats.errorsByChannel.keys)
        .distinct()
        .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    if (channels.isEmpty()) return

    item {
        Section("By channel")
        Text(
            text = "A channel far above the others in errors is usually interference in " +
                "that part of the band rather than anything about the devices. Each bar is " +
                "that channel's share of all corrupt packets, filled in as far as the " +
                "repairs got.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for (channel in channels) {
            ChannelRow(
                label = if (channel == "0") "no channel (phone)" else "channel $channel",
                received = stats.totalByChannel[channel] ?: 0,
                corrupt = stats.errorsByChannel[channel] ?: 0,
                repaired = stats.repairedByChannel[channel] ?: 0,
                totalCorrupt = stats.crcErrors,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    label: String,
    received: Int,
    corrupt: Int,
    repaired: Int,
    totalCorrupt: Int,
) {
    val share = if (totalCorrupt <= 0) 0f else (corrupt.toFloat() / totalCorrupt).coerceIn(0f, 1f)
    val repairedShare = if (corrupt <= 0) 0f else (repaired.toFloat() / corrupt).coerceIn(0f, 1f)
    val rate = if (received == 0) 0.0 else 100.0 * corrupt / received

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = groupDigits(corrupt),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SnifferColors.neutralBadgeText,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Outer band: how much of the session's damage landed here. Inner
            // band: how much of that came back. Nesting them rather than
            // drawing two bars keeps "repaired" readable as a fraction of this
            // channel's own errors instead of of the whole capture.
            Box(
                modifier = Modifier
                    .fillMaxWidth(share)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.error),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(repairedShare)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Text(
            text = "${groupDigits(received)} received  ·  " +
                "%.1f%% corrupt".format(rate) +
                "  ·  ${groupDigits(repaired)} repaired",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.breakdown(
    title: String,
    explanation: String,
    rows: List<Pair<String, Int>>,
    total: Int,
) {
    if (rows.isEmpty()) return
    item {
        Section(title)
        Text(
            text = explanation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for ((label, count) in rows) {
            BarRow(label, count, total)
        }
    }
}

/** A labelled count with a proportional bar, so shares read at a glance. */
@Composable
private fun BarRow(label: String, count: Int, total: Int) {
    val fraction = if (total <= 0) 0f else (count.toFloat() / total).coerceIn(0f, 1f)

    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = groupDigits(count.toLong()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SnifferColors.neutralBadgeText,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 18.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}
