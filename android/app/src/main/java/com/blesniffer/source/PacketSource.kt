package com.blesniffer.source

import com.blesniffer.model.BlePacket
import com.blesniffer.model.ChannelSelection
import com.blesniffer.model.SourceStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * A thing that produces advertising packets.
 *
 * Implementations push packets through [onPacket] on a background thread; the
 * view model batches them before they reach the UI or the database.
 */
interface PacketSource {

    val status: StateFlow<SourceStatus>

    /** Begins delivering packets on [channel]. */
    fun start(channel: ChannelSelection)

    /** Stops delivery. Sources that can, tell the hardware to stop transmitting. */
    fun stop()

    /** Switches channel, taking effect immediately whether or not scanning. */
    fun setChannel(channel: ChannelSelection)

    /** Releases hardware. The source cannot be used afterwards. */
    fun close()

    /** True when this source can honour [setChannel]. */
    val supportsChannelSelection: Boolean

    fun interface Listener {
        fun onPacket(packet: BlePacket)
    }
}
