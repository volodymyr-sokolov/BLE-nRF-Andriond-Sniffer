package com.blesniffer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

fun formatClock(timestampMs: Long): String = synchronized(TIME_FORMAT) {
    TIME_FORMAT.format(Date(timestampMs))
}

/** Renders an age as the coarsest unit that still reads precisely. */
fun formatAge(ageMs: Long): String = when {
    ageMs < 0 -> "now"
    ageMs < 1_000 -> "${ageMs} ms ago"
    ageMs < 60_000 -> "%.1f s ago".format(ageMs / 1000.0)
    ageMs < 3_600_000 -> "${ageMs / 60_000} min ago"
    else -> "${ageMs / 3_600_000} h ago"
}

/**
 * A clock that ticks on a fixed period, so ages stay honest without every packet
 * arrival forcing a recomposition of the whole list.
 *
 * The device list uses a slow period on purpose. A device advertising every
 * hundred milliseconds re-zeroes its age constantly, and a number that changes
 * faster than it can be read is not information. Holding each value for a few
 * seconds makes the column legible.
 */
@Composable
fun rememberTicker(periodMs: Long): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

@Composable
fun rememberSecondTicker(): State<Long> = rememberTicker(1_000)

/** Minimum time an age reading stays on screen before it is replaced. */
const val AGE_HOLD_MS = 5_000L

/**
 * Thin space (U+2009), the typographic separator for digit groups. A full space
 * opens too wide a gap and makes one number look like two.
 */
const val DIGIT_SEPARATOR = ' '

/**
 * Splits a count into groups of three digits separated by thin spaces.
 *
 * The separators do double duty: they make a six figure count readable, and
 * they give the text layout somewhere to break, so a number too wide for its
 * badge wraps between groups instead of being clipped or squeezing everything
 * else out of the row.
 */
fun groupDigits(value: Long): String =
    value.toString()
        .reversed()
        .chunked(3)
        .joinToString(DIGIT_SEPARATOR.toString())
        .reversed()

/**
 * Most counts in the app are Int. Without this overload every call site spells
 * out `.toLong()`, and the ones that found it easier to write `"$count"` are
 * exactly the places where the grouping went missing.
 */
fun groupDigits(value: Int): String = groupDigits(value.toLong())
