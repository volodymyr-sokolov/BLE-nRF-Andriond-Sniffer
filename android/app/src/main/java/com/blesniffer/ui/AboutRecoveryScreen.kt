package com.blesniffer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blesniffer.ui.theme.SnifferColors

/**
 * In-app explanation of how the board, deduplication and repair work.
 *
 * Deliberately states what each layer's answer rests on, and where it is weak,
 * as prominently as what it can do. A user looking at a packet marked "repaired"
 * needs to know what that claim is worth, otherwise the marking is worse than
 * useless. Everything here is written to be true of the code as it stands rather
 * than of the plan it was built from; nothing points at a file the reader of a
 * phone screen has no way to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutRecoveryScreen(
    bundledFirmwareVersion: String?,
    connectedFirmwareVersion: String?,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How repair works") },
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
                item {
                    Callout(
                        "There is no neural network here. Packets are repaired by CRC algebra " +
                            "and by comparison with clean receptions of the same frame. " +
                            "A learned prior only ever breaks ties the CRC has already " +
                            "narrowed to a single answer.",
                    )
                }

                section(
                    "The board's firmware",
                    "The nRF52840 runs the radio directly, with no Bluetooth stack in the " +
                        "way, and has two capture modes. The one it boots in is STD: the " +
                        "radio reads the advertisement's own length field, dewhitens it, " +
                        "checks the CRC and hands over the result with the CRC stripped off.\n\n" +
                        "RAW is what makes algebraic repair possible. The length field is " +
                        "ignored and every capture is a fixed 42 bytes, so a corrupted length " +
                        "cannot move the end of the packet; whitening and the CRC check are " +
                        "both switched off, and the bytes arrive exactly as they were " +
                        "transmitted. The app dewhitens them and computes the CRC itself, " +
                        "which is the only way the received checksum ever reaches the code " +
                        "that needs it.\n\n" +
                        "Expect fewer packets per second in RAW. A fixed-length capture keeps " +
                        "the radio on air for the full 42 bytes even when the advertisement " +
                        "was a third of that, and each packet costs about twice as many bytes " +
                        "over USB, so more of them are dropped before they are ever sent." +
                        firmwareVersions(bundledFirmwareVersion, connectedFirmwareVersion),
                )

                section(
                    "Two receivers, one transmission",
                    "The phone's radio and the nRF52840 hear the same advertisement " +
                        "independently, milliseconds apart. Matching them on advertiser " +
                        "address plus payload folds the pair into one packet.\n\n" +
                        "The board's copy is the one kept: it carries the channel, a " +
                        "microsecond hardware timestamp and the real CRC status, none of " +
                        "which survive the phone's parsed scan result. The packet still " +
                        "records that both receivers saw it.\n\n" +
                        "The clocks are related by a median offset estimated from matched " +
                        "pairs, and not trusted until five pairs agree - for a beacon " +
                        "repeating one payload, a single pair cannot tell this transmission " +
                        "from the next one.\n\n" +
                        "The phone never reports a corrupt packet at all: its controller " +
                        "discards those before the app is told. Every error you see, and so " +
                        "every repair, comes from the board.",
                )

                section(
                    "Four layers, strongest first",
                    "A packet that fails CRC is offered to each layer in turn and the first " +
                        "one that will commit to an answer wins. The layer that answered " +
                        "travels with the packet and is what the statistics screen counts, " +
                        "so a repair can always be read back to the evidence behind it.\n\n" +
                        "1.  CRC-24 syndrome decoding - algebra.\n" +
                        "2.  A clean copy of the same frame, from the other receiver or from " +
                        "an earlier repetition.\n" +
                        "3.  The device's own byte template.\n" +
                        "4.  A learned byte prior, and only where the CRC has already " +
                        "settled the answer.\n\n" +
                        "Every layer may refuse, and refusing is the default. A packet no " +
                        "layer will commit to is kept exactly as it arrived and marked " +
                        "unrecoverable - which is a result, not a failure.",
                )

                section(
                    "1. CRC-24 syndrome decoding",
                    "BLE's CRC is linear, so the difference between the checksum a packet " +
                        "carries and the one its bytes produce depends only on the errors, " +
                        "not on the data. Every single-bit error therefore has its own fixed " +
                        "signature, and finding which bit flipped is a lookup rather than a " +
                        "search. Two-bit errors are found by pairing signatures.\n\n" +
                        "A packet mended this way is not a guess: the result agrees with a " +
                        "24-bit checksum.\n\n" +
                        "This needs the received CRC bytes, so it only runs in RAW mode. In " +
                        "STD the radio checks the CRC and throws it away, and this layer and " +
                        "the prior below both sit idle.",
                )

                section(
                    "2. A clean copy of the same frame",
                    "With both receivers running, the phone's copy of a frame the board " +
                        "received badly is a complete answer rather than a reconstruction. " +
                        "The two receivers frame a packet differently - the board delivers " +
                        "the PDU from its header, the phone only the advertising data - so " +
                        "the copies are aligned on their advertising-data chains before " +
                        "anything is substituted.\n\n" +
                        "Failing that, repetitions of the same frame from the last 30 " +
                        "seconds are voted on byte by byte. A candidate is accepted only " +
                        "when it is the single closest match and that frame was received at " +
                        "least twice, so it is the device's steady state rather than a " +
                        "one-off.",
                )

                section(
                    "3. What the device template learns",
                    "For each advertiser the app records which values every byte position " +
                        "has ever held. After eight packets, a position that has only ever " +
                        "carried one value counts as stable and may be restored; anything " +
                        "that varies is left alone, so counters and rotating addresses are " +
                        "never rewritten.\n\n" +
                        "A candidate must also leave the payload structurally whole: its " +
                        "advertising-data chain has to tile the payload exactly, each length " +
                        "byte landing on the next. A frame whose header and tail disagree " +
                        "about where the packet even began is treated as lost sync and " +
                        "refused outright, however well the rest of it scores.\n\n" +
                        "This is learned from the traffic you captured, per device, and only " +
                        "from packets that arrived intact. Repaired packets never train " +
                        "anything - a model fed its own guesses would agree with them more " +
                        "and more.",
                )

                section(
                    "4. The learned prior",
                    "Counts of which byte tends to follow which, and what tends to sit at " +
                        "each offset, built from the clean traffic you have captured. It is " +
                        "used only to choose between repairs that already satisfy the CRC, " +
                        "only when one candidate beats the rest by a wide margin, and only " +
                        "when the packet has not lost sync.\n\n" +
                        "It is the weakest layer and the one to distrust. In practice the " +
                        "layers above it settle nearly everything that can be settled, so " +
                        "what reaches this one is what nothing else could explain - and a " +
                        "repair that rests on which bytes are common rather than on a " +
                        "checksum or a second reception is a guess with a good prior, not " +
                        "evidence. It is kept because a CRC-verified candidate is still " +
                        "worth offering, and it is fenced in for the same reason.\n\n" +
                        "The specification calls for a small transformer here. This is a " +
                        "counting model instead: it trains in one pass on the phone with no " +
                        "corpus to ship, and can be replaced without disturbing anything " +
                        "around it.",
                )

                section(
                    "How often it is wrong",
                    "Sometimes. How often depends on the capture, and no figure measured on " +
                        "one radio in one room transfers to yours.\n\n" +
                        "What holds regardless is the ordering. A syndrome decode of one or " +
                        "two bits agrees with a 24-bit checksum and is close to safe. A " +
                        "clean copy from the other receiver is a real reception rather than " +
                        "a reconstruction. Below those the evidence thins out, and the " +
                        "learned prior is where wrong answers concentrate.\n\n" +
                        "Two-bit decoding is where even the algebra can mislead: when a " +
                        "packet is carrying more than two flipped bits, a two-bit fix that " +
                        "happens to satisfy the checksum cannot be told from a real one. " +
                        "Heavy damage is exactly when that is most likely.\n\n" +
                        "So read the statistics screen rather than trusting a number quoted " +
                        "here. It shows which layer answered, how many bits each repair had " +
                        "to change and how confident it was - and a session where most " +
                        "repairs came from the first two layers, changing a bit or two each, " +
                        "is a different thing from one that leaned on the last.\n\n" +
                        "Treat a repaired packet as a strong hypothesis, not as data. The " +
                        "bytes that actually arrived are always kept beside it, and export " +
                        "carries both.",
                )

                section(
                    "What is still weak",
                    "Repair depends on hearing a device more than once. A frame seen a " +
                        "single time, from an advertiser nothing is known about, has nothing " +
                        "to be compared against and only the algebra can help it.\n\n" +
                        "An address read out of a packet that failed CRC and could not be " +
                        "repaired is not evidence of a device: a few flipped bits in the " +
                        "address field mint a new one. Those are collected into a single " +
                        "row rather than listed as advertisers, but the packets behind them " +
                        "are still unattributed.\n\n" +
                        "Confidence is a calibration of the pipeline's own reasoning, not a " +
                        "measured error rate. It orders repairs sensibly; it does not " +
                        "promise that a repair at 90% is wrong one time in ten.",
                )
            }
        }
    }
}

/**
 * The two firmware versions that matter, and whether they agree.
 *
 * A board flashed from an older build of this app is the one failure that looks
 * like a bug in the app rather than a mismatch - raw capture simply does nothing
 * - so when the two differ it is said outright.
 */
private fun firmwareVersions(bundled: String?, connected: String?): String = buildString {
    append("\n\n")
    when {
        connected == null && bundled == null ->
            append("No board is connected, and this build ships without a firmware image.")

        connected == null ->
            append(
                "No board is connected. This app carries firmware version $bundled; " +
                    "\"Flash bundled firmware\" in the tools menu installs it over USB.",
            )

        bundled == null ->
            append("The connected board is running firmware version $connected.")

        connected == bundled ->
            append(
                "The connected board is running firmware version $connected, which is the " +
                    "version bundled with this app.",
            )

        else ->
            append(
                "The connected board is running firmware version $connected, while this app " +
                    "carries $bundled. \"Flash bundled firmware\" in the tools menu will " +
                    "bring the board up to date.",
            )
    }
}

private fun LazyListScope.section(title: String, body: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Callout(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Default,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SnifferColors.repairedBackground)
            .padding(12.dp),
    )
}
