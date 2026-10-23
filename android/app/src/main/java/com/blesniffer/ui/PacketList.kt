package com.blesniffer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blesniffer.model.BlePacket
import kotlinx.coroutines.launch

/**
 * The sequential view: every packet in arrival order, newest at the top.
 *
 * The list follows new arrivals until the user interacts with it. Dragging it or
 * opening a packet pins it in place so whatever is being read stays put while
 * packets keep accumulating behind the scenes; a bar at the top then offers a
 * way back to the newest packet.
 */
@Composable
fun PacketList(
    packets: List<BlePacket>,
    totalPackets: Long,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No packets yet. Press Start to begin capturing.",
    // False for a capture loaded from a file: nothing can arrive, so there is
    // nothing to follow and no reason to offer a jump back to the newest.
    live: Boolean = true,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Saveable rather than merely remembered: these survive the list being
    // removed from the composition, which is what happens whenever a screen
    // covers the main one. Without it, glancing at the statistics threw away
    // the scroll position and closed whatever packet was open.
    var following by rememberSaveable { mutableStateOf(live) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // A boxed Long rather than mutableLongStateOf: the primitive state does not
    // round-trip through rememberSaveable's default saver, so this came back as
    // zero and the list announced every packet in the session as new.
    var pausedAtTotal by rememberSaveable { mutableStateOf(0L) }

    // The effect is keyed on listState alone so the collector is not torn down
    // and restarted on every packet, which means it closes over whatever
    // totalPackets was when it started - zero, before the stored capture had
    // been read back. Pausing the list then recorded a mark of zero and the bar
    // announced the entire session as new. Reading it through the latest value
    // keeps the long-lived collector and gets the current count.
    val latestTotal by rememberUpdatedState(totalPackets)

    // Only a genuine drag counts as the user taking over; a programmatic scroll
    // back to the top must not immediately re-pause the list.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && following) {
                following = false
                pausedAtTotal = latestTotal
            }
        }
    }

    val newestId = packets.firstOrNull()?.id
    LaunchedEffect(newestId, following) {
        if (following && packets.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    fun pause() {
        if (following) {
            following = false
            pausedAtTotal = totalPackets
        }
    }

    val newCount = (totalPackets - pausedAtTotal).coerceAtLeast(0)

    // Scrolling back to the very top is the same intent as tapping the bar, so
    // following resumes there - unless a packet is open, which means the user is
    // still reading. Without this the arrow kept pointing upwards while the
    // newest packet was already on screen.
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Scrolled down by a whole row or more means there are packets above the
    // viewport that have not been seen, whether or not any arrived since the
    // list was paused. That, and only that, is when the way back is offered.
    val scrolledDown by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }
    LaunchedEffect(atTop, expandedId) {
        if (atTop && expandedId == null && !following) {
            following = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (packets.isEmpty()) {
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

        // Wrapping the list makes every label in it selectable, so addresses,
        // signal levels and decoded field values can be long-pressed and copied
        // with the usual system controls instead of needing their own buttons.
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(packets, key = { it.id }) { packet ->
                    PacketRow(
                        packet = packet,
                        expanded = expandedId == packet.id,
                        onToggle = {
                            // Opening a packet is also a signal that the user is
                            // reading rather than watching the stream go by.
                            pause()
                            expandedId = if (expandedId == packet.id) null else packet.id
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }

        // Pausing is not the same thing as having something to go back to.
        // Reading a packet at the top of an idle list pauses the follow, but
        // nothing has scrolled out of view, and an arrow pointing at nothing is
        // just noise.
        AnimatedVisibility(
            visible = live && scrolledDown,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            JumpToTopBar(
                newCount = newCount,
                onClick = {
                    following = true
                    expandedId = null
                    scope.launch { listState.animateScrollToItem(0) }
                },
            )
        }
    }
}

/**
 * The paused indicator. Tapping it returns to the newest packet and resumes
 * following the stream.
 */
@Composable
private fun JumpToTopBar(newCount: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowDropUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = if (newCount > 0) {
                    "${groupDigits(newCount)} new packets"
                } else {
                    "Scrolling paused"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Tap to jump to the newest",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
