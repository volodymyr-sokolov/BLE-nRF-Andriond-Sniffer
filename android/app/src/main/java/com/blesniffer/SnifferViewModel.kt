package com.blesniffer

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blesniffer.data.PacketStore
import com.blesniffer.dfu.BundledFirmware
import com.blesniffer.dfu.DfuPackage
import com.blesniffer.dfu.DfuProgress
import com.blesniffer.dfu.DfuSession
import com.blesniffer.export.SessionFiles
import com.blesniffer.export.SessionInfo
import com.blesniffer.model.AdvertisingDecoder
import com.blesniffer.model.BlePacket
import com.blesniffer.model.ChannelSelection
import com.blesniffer.model.ExportFormat
import com.blesniffer.model.PacketGroup
import com.blesniffer.model.PayloadGroup
import com.blesniffer.model.RecoveryStats
import com.blesniffer.model.CaptureSource
import com.blesniffer.model.SourceKind
import com.blesniffer.model.SourceStatus
import com.blesniffer.model.TimelinePoint
import com.blesniffer.model.TimelineScale
import com.blesniffer.model.ViewMode
import kotlinx.coroutines.CancellationException
import com.blesniffer.recover.Deduplicator
import com.blesniffer.recover.RecoveryPipeline
import com.blesniffer.source.PacketSource
import com.blesniffer.source.PhoneBleSource
import com.blesniffer.source.UsbSnifferSource
import com.blesniffer.ui.TimelineRenderer
import com.blesniffer.ui.exportStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/** How many packets the sequential view keeps in memory. */
private const val LIVE_WINDOW = 3_000

/**
 * How many packets a group drill-down keeps.
 *
 * This used to be 2,000, which a single chatty advertiser reaches in minutes;
 * past that the oldest packets fell out of the page and the view stopped
 * growing. The chart no longer reads from this page at all - it queries storage
 * over its own window - but the list still benefits from the deeper history.
 */
private const val GROUP_PAGE = 20_000

/** Most packets moved from the capture thread to storage in one flush. */
private const val BATCH_LIMIT = 2_000

/**
 * Packets are moved from the capture thread to storage on this cadence.
 *
 * This is the only delay the app itself adds between a packet arriving and
 * appearing: timestamps are taken at capture, so batching never moves them, but
 * it does hold packets back from the screen. 100 ms keeps that below what reads
 * as lag while still amortising the database transaction over a batch.
 */
private const val FLUSH_INTERVAL_MS = 100L


data class UiState(
    val source: CaptureSource = CaptureSource.PHONE,
    val channel: ChannelSelection = ChannelSelection.ALL,
    val mode: ViewMode = ViewMode.SEQUENTIAL,
    val scanning: Boolean = false,
    val status: SourceStatus = SourceStatus(),
    val packets: List<BlePacket> = emptyList(),
    val groups: List<PacketGroup> = emptyList(),
    val totalPackets: Long = 0,
    val crcErrors: Long = 0,
    val message: String? = null,
    val busy: Boolean = false,
    /** Substring match on the advertiser address; empty means no filtering. */
    val addressFilter: String = "",
    /** Exact match on the PDU type; empty means every type. */
    val pduTypeFilter: String = "",
    /**
     * Types seen in this capture, sorted, for the filter menu.
     *
     * Offered from what has actually arrived rather than from the list of BLE
     * PDU types, because the two sources do not name them the same way: the
     * board reports `ADV_IND` from the link layer header, while Android only
     * exposes `legacy, connectable`. A fixed menu would match nothing at all in
     * a phone capture.
     */
    val pduTypes: List<String> = emptyList(),
    val chartVisible: Boolean = true,
    val chartScale: TimelineScale = TimelineScale.TEN_SECONDS,
    /**
     * Chart window ends at the newest packet rather than at the clock.
     *
     * Deliberately not remembered across runs, unlike the other two chart
     * settings: it is a way of going back to look at data that has stopped
     * arriving, and a capture that starts up pinned to a packet from the
     * previous session shows an empty chart for no reason the user can see.
     */
    val chartAtLastData: Boolean = false,
    /** Cross-source duplicates folded together this session. */
    val deduplicated: Long = 0,
    /** Corrupt packets the recovery pipeline repaired. */
    val repaired: Long = 0,
    /** Estimated host-minus-board clock offset, milliseconds. */
    val clockOffsetMs: Long = 0,
    /** Raw capture requested: the board sends CRC bytes and leaves whitening on. */
    val rawCapture: Boolean = false,
) {
    private fun matches(address: String) =
        addressFilter.isEmpty() || address.contains(addressFilter, ignoreCase = true)

    /** True when this packet type passes the filter, or there is no filter. */
    fun matchesType(pduType: String) = pduTypeFilter.isEmpty() || pduType == pduTypeFilter

    val filtered: Boolean get() = addressFilter.isNotEmpty() || pduTypeFilter.isNotEmpty()

    val visiblePackets: List<BlePacket>
        get() = if (!filtered) {
            packets
        } else {
            packets.filter { matches(it.address) && matchesType(it.pduType) }
        }

    val visibleGroups: List<PacketGroup>
        get() = collapseUnidentified(
            if (!filtered) {
                groups
            } else {
                // A device is kept when it has *ever* sent the selected type,
                // so filtering the list does not depend on what happened to
                // arrive most recently.
                groups.filter { group ->
                    matches(group.address) &&
                        (pduTypeFilter.isEmpty() || pduTypeFilter in group.pduTypes)
                }
            },
        )

    /** What the empty list should say, given which filters are in force. */
    val filterDescription: String
        get() = listOfNotNull(
            addressFilter.takeIf { it.isNotEmpty() }?.let { "an address matching \"$it\"" },
            pduTypeFilter.takeIf { it.isNotEmpty() }?.let { "type $it" },
        ).joinToString(" and ")

    /** Advertisers whose address was actually confirmed by an intact packet. */
    val confirmedDeviceCount: Int get() = groups.count { it.confirmed }
}

/**
 * Folds every unidentifiable advertiser into a single row.
 *
 * An address read out of a packet that failed CRC and could not be repaired is
 * not evidence of a device: a few flipped bits in the address field mint a brand
 * new one, so a noisy capture invents hundreds of advertisers that were never
 * there. Listing them buries the real devices - a nine minute capture produced
 * around 600 such phantoms against a few dozen genuine ones.
 *
 * They are collected rather than hidden: the packets are real and the count
 * matters, it is only the identity that is fiction.
 */
fun collapseUnidentified(groups: List<PacketGroup>): List<PacketGroup> {
    val (confirmed, unidentified) = groups.partition { it.confirmed }
    if (unidentified.isEmpty()) return confirmed.sortedByDescending { it.count }

    val bucket = PacketGroup(
        address = PacketGroup.DAMAGED_BUCKET,
        addressType = "",
        count = unidentified.sumOf { it.count },
        lastRssi = unidentified.maxByOrNull { it.lastTimestampMs }?.lastRssi ?: 0,
        lastTimestampMs = unidentified.maxOf { it.lastTimestampMs },
        lastPduType = "",
        crcErrors = unidentified.sumOf { it.crcErrors },
        name = null,
        confirmedPackets = 0,
        mergedDevices = unidentified.size,
        pduTypes = unidentified.flatMapTo(mutableSetOf()) { it.pduTypes },
    )
    return (confirmed + bucket).sortedByDescending { it.count }
}

/** A group the user has drilled into, plus the packets it contains. */
data class GroupDetail(
    val group: PacketGroup,
    val packets: List<BlePacket>,
) {
    /**
     * The same packets collapsed by payload. Derived rather than stored so it
     * cannot drift from [packets], and computed once per instance rather than
     * on every recomposition - a group can hold a couple of thousand packets.
     */
    val payloadGroups: List<PayloadGroup> by lazy { groupByPayload(packets) }
}

/**
 * Collapses byte-identical packets, ignoring signal strength.
 *
 * Two frames from the same advertiser differ in RSSI on every repetition and
 * usually in which channel caught them, so identity is the payload itself; the
 * channels that saw it are collected alongside as a set.
 *
 * Corrupt packets are the exception. Damage lands in a different place every
 * time, so no two of them are byte-identical and each one became a row of its
 * own - a device heard through interference showed the two frames it really
 * sends followed by several hundred singletons. They all go into one row
 * instead, which is the only thing anyone reads off them anyway: how many there
 * were, and how many came back.
 */
fun groupByPayload(packets: List<BlePacket>): List<PayloadGroup> {
    val map = LinkedHashMap<String, PayloadGroup>()
    // Byte-distinct corrupt frames, so the merged row can say how many it stands
    // for without holding on to all of them.
    val distinctErrors = mutableSetOf<String>()

    for (packet in packets) {
        val error = !packet.crcOk
        val key = if (error) PayloadGroup.MERGED_ERRORS_KEY else "${packet.rawHex}|true"
        if (error) distinctErrors += packet.rawHex

        val existing = map[key]
        map[key] = PayloadGroup(
            rawHex = existing?.rawHex ?: packet.rawHex,
            crcOk = packet.crcOk,
            adOffset = existing?.adOffset ?: packet.adOffset,
            pduType = existing?.pduType ?: packet.pduType,
            address = packet.address,
            addressType = packet.addressType,
            count = (existing?.count ?: 0) + 1,
            // packets arrive newest first, so the first one seen is the latest.
            lastRssi = existing?.lastRssi ?: packet.rssi,
            lastTimestampMs = existing?.lastTimestampMs ?: packet.timestampMs,
            channels = (existing?.channels ?: emptySet()) +
                if (packet.channel in 37..39) setOf(packet.channel) else emptySet(),
            representative = existing?.representative ?: packet,
            merged = error,
            repaired = (existing?.repaired ?: 0) + if (packet.wasRepaired) 1 else 0,
        )
    }

    map[PayloadGroup.MERGED_ERRORS_KEY]?.let {
        map[PayloadGroup.MERGED_ERRORS_KEY] = it.copy(distinctPayloads = distinctErrors.size)
    }
    return map.values.sortedByDescending { it.count }
}

/** A capture loaded from a file rather than the radio. */
data class ViewerState(
    val name: String,
    val info: SessionInfo,
    val packets: List<BlePacket>,
    val groups: List<PacketGroup>,
    val mode: ViewMode = ViewMode.SEQUENTIAL,
)

private const val PREFS_NAME = "sniffer-ui"
private const val KEY_CHART_VISIBLE = "chart-visible"
private const val KEY_CHART_SCALE = "chart-scale"
private const val KEY_RAW_CAPTURE = "raw-capture"

/**
 * Which radio to capture from, remembered across restarts.
 *
 * This is not a convenience. The app can be restarted by the system at any
 * time, and without this the source silently reverted to the phone's own radio
 * - which reports no channel and never a CRC failure. A measurement run then
 * carried on recording packets that could not contain the thing being measured,
 * and the result was indistinguishable from a channel that had gone quiet.
 * Losing a bench session to that is how this constant came to exist.
 */
private const val KEY_SOURCE = "capture-source"

class SnifferViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PacketStore(app)

    private val prefs = app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        UiState(
            source = CaptureSource.fromId(
                prefs.getString(KEY_SOURCE, CaptureSource.PHONE.id)!!,
            ),
            chartVisible = prefs.getBoolean(KEY_CHART_VISIBLE, true),
            chartScale = TimelineScale.fromId(
                prefs.getString(KEY_CHART_SCALE, TimelineScale.TEN_SECONDS.name)!!,
            ),
            rawCapture = prefs.getBoolean(KEY_RAW_CAPTURE, false),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _groupDetail = MutableStateFlow<GroupDetail?>(null)
    val groupDetail: StateFlow<GroupDetail?> = _groupDetail.asStateFlow()

    /**
     * Points behind the timeline chart for the open group.
     *
     * Read from storage over the chart's own time window rather than taken from
     * the packet page. The page holds the most recent N packets, so at the ten
     * minute scale its oldest entries were being evicted while still on the
     * chart - columns on the left would shrink and vanish as newer packets
     * pushed them out, showing history that decayed instead of history that
     * happened.
     */
    private val _chartPoints = MutableStateFlow<List<TimelinePoint>>(emptyList())
    val chartPoints: StateFlow<List<TimelinePoint>> = _chartPoints.asStateFlow()

    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    /**
     * Packets land here from whichever thread the source runs on and are drained
     * on a timer. Doing the SQLite write and the UI update in batches is what
     * lets a busy channel keep up.
     */
    private val inbox = ConcurrentLinkedQueue<BlePacket>()

    /** Newest first, capped at [LIVE_WINDOW]; older packets live only in SQLite. */
    private val liveWindow = ArrayDeque<BlePacket>()

    /** Running accumulation, keyed by advertiser address. */
    private val accumulator = LinkedHashMap<String, PacketGroup>()

    /**
     * Every PDU type this capture has produced, for the filter menu.
     *
     * Sorted, and kept here rather than derived from [accumulator] on each
     * flush: the menu changes a handful of times in a whole session, and
     * rebuilding the list ten times a second would recompose the control panel
     * ten times a second for a list that had not moved.
     */
    private val observedPduTypes = sortedSetOf<String>()

    private val listener = PacketSource.Listener { packet -> inbox.add(packet) }

    private val phoneSource = PhoneBleSource(app, listener)
    private val usbSource = UsbSnifferSource(app, listener)

    /** Every source the current selection has running. */
    private val activeSources: List<PacketSource>
        get() = buildList {
            if (_uiState.value.source.usesPhone) add(phoneSource)
            if (_uiState.value.source.usesUsb) add(usbSource)
        }

    /**
     * Last state reported by each source.
     *
     * Declared here, above the init block, deliberately. viewModelScope runs on
     * Dispatchers.Main.immediate, so the status collector launched from init
     * starts executing during construction and receives the current value at
     * once - before any property declared further down has been initialised.
     */
    private var phoneStatus = SourceStatus()
    private var usbStatus = SourceStatus()

    private val deduplicator = Deduplicator()
    private val recovery = RecoveryPipeline()

    init {
        viewModelScope.launch { flushLoop() }
        viewModelScope.launch { observeStatus() }
        viewModelScope.launch { restoreExistingCapture() }
        viewModelScope.launch { chartRefreshLoop() }
    }

    /**
     * Keeps the chart's window current while a group is open. Slower than the
     * packet flush because the chart only needs to be right to the second, and
     * the query reaches back as far as ten minutes.
     */
    private suspend fun chartRefreshLoop() {
        while (true) {
            // One reload per column, so the ten minute chart is not re-reading
            // ten minutes of rows every second to redraw columns ten seconds
            // wide. The floor matters as much as the ceiling: at the one second
            // scale a slower reload leaves the right-hand end of the window
            // showing nothing, because the data is older than the window.
            delay(_uiState.value.chartScale.binMs.coerceIn(250L, 10_000L))
            if (_groupDetail.value != null && _uiState.value.chartVisible) {
                runCatching { reloadChartPoints() }
                    .onFailure { Log.e("BleSniffer", "chart refresh failed", it) }
            }
        }
    }

    /**
     * Reloads the chart's window for the open group.
     *
     * The window normally ends now, which is what makes a live chart slide. When
     * it is pinned to the last data the query has to move with it: reading the
     * last ten seconds of *wall clock* for a device that stopped advertising
     * five minutes ago returns nothing at all, so the chart would have had
     * nothing to pin itself to.
     */
    private suspend fun reloadChartPoints() {
        val address = _groupDetail.value?.group?.address ?: return
        val state = _uiState.value
        val points = withContext(Dispatchers.IO) {
            val end = if (state.chartAtLastData) {
                store.lastTimestampMs(address) ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
            store.timelinePoints(address, end - state.chartScale.spanMs)
        }
        _chartPoints.value = points
    }

    // --- capture lifecycle ----------------------------------------------------

    /**
     * Moves packets from the capture threads into storage and the UI.
     *
     * The body is guarded because this loop is the only thing feeding the whole
     * app: an exception escaping it - a storage error, a bad packet - would
     * cancel the coroutine and silently freeze every counter on screen with no
     * indication that anything had gone wrong. Failures are reported and the
     * loop carries on.
     */
    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_INTERVAL_MS)
            try {
                drainInbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BleSniffer", "flush failed", e)
                _uiState.value = _uiState.value.copy(
                    message = "Storing packets failed: ${e.message}",
                )
            }
        }
    }

    private suspend fun drainInbox() {
        val batch = ArrayList<BlePacket>(BATCH_LIMIT)
        while (batch.size < BATCH_LIMIT) {
            batch += inbox.poll() ?: break
        }

        val ready = settle(batch)
        if (ready.isEmpty()) return

        val stored = withContext(Dispatchers.IO) { store.insertBatch(ready) }

        for (packet in stored) {
            liveWindow.addFirst(packet)
            accumulate(packet)
        }
        while (liveWindow.size > LIVE_WINDOW) {
            liveWindow.removeLast()
        }

        val newTypes = stored.any { it.pduType.isNotEmpty() && observedPduTypes.add(it.pduType) }

        val crcErrors = accumulator.values.sumOf { it.crcErrors.toLong() }
        _uiState.value = _uiState.value.copy(
            packets = liveWindow.toList(),
            groups = accumulator.values.sortedByDescending { it.count },
            totalPackets = _uiState.value.totalPackets + stored.size,
            crcErrors = crcErrors,
            pduTypes = if (newTypes) observedPduTypes.toList() else _uiState.value.pduTypes,
            deduplicated = deduplicator.mergedCount(),
            repaired = _uiState.value.repaired + stored.count { it.wasRepaired },
            clockOffsetMs = deduplicator.clockOffsetMs(),
        )

        refreshOpenGroup(stored)
    }

    /**
     * Turns freshly captured packets into the ones worth storing.
     *
     * With one receiver there is nothing to reconcile and packets go straight
     * through. With both running, a packet has to wait out the matching window
     * before it can be known whether the other receiver also saw it, so this
     * releases only what has settled - which is why deduplication costs a couple
     * of hundred milliseconds of display latency and single-source capture does
     * not pay it.
     *
     * Recovery runs after deduplication on purpose: a corrupt packet whose twin
     * arrived intact from the other receiver is repaired from that twin, which
     * is the strongest evidence available and the cheapest layer in the pipeline.
     */
    private fun settle(batch: List<BlePacket>): List<BlePacket> {
        if (_uiState.value.source != CaptureSource.BOTH) {
            return batch.map(recovery::process)
        }

        batch.forEach(deduplicator::offer)
        val released = deduplicator.drain(System.currentTimeMillis())
        return released.map(recovery::process)
    }

    private fun accumulate(packet: BlePacket) {
        // Keyed on the address exactly as stored. Substituting a placeholder
        // here made restored groups (which read the raw column) disagree with
        // live ones, so the group rendered blank and drilling into it queried
        // for a string no row contains. The placeholder belongs in the UI.
        val key = packet.address
        val existing = accumulator[key]
        // A corrupt packet's "Complete Local Name" is not a name, it is damaged
        // bytes that happened to parse. Labelling a device from one produces
        // convincing nonsense, so error packets never contribute a name.
        val name = if (packet.crcOk) {
            AdvertisingDecoder.extractName(packet.rawHex, packet.adOffset)
        } else {
            null
        }

        accumulator[key] = PacketGroup(
            address = key,
            addressType = packet.addressType.ifEmpty { existing?.addressType.orEmpty() },
            count = (existing?.count ?: 0) + 1,
            lastRssi = packet.rssi,
            lastTimestampMs = packet.timestampMs,
            lastPduType = packet.pduType,
            crcErrors = (existing?.crcErrors ?: 0) + if (packet.crcOk) 0 else 1,
            name = name ?: existing?.name,
            confirmedPackets = (existing?.confirmedPackets ?: 0) +
                if (packet.crcOk || packet.wasRepaired) 1 else 0,
            repaired = (existing?.repaired ?: 0) + if (packet.wasRepaired) 1 else 0,
            pduTypes = if (packet.pduType.isEmpty()) {
                existing?.pduTypes.orEmpty()
            } else {
                existing?.pduTypes.orEmpty() + packet.pduType
            },
        )
    }

    /**
     * Keeps a group that is currently open on screen in step with new arrivals.
     *
     * New packets are prepended to what was already loaded rather than the list
     * being recomputed from the live window: the window holds only the most
     * recent packets across all devices, so rebuilding from it would silently
     * discard the older history that was read out of storage when the group was
     * opened.
     */
    private fun refreshOpenGroup(stored: List<BlePacket>) {
        val open = _groupDetail.value ?: return
        val updated = accumulator[open.group.address] ?: open.group

        val arrivals = stored
            .filter { it.address == updated.address }
            .asReversed()

        val packets = if (arrivals.isEmpty()) {
            open.packets
        } else {
            (arrivals + open.packets).take(GROUP_PAGE)
        }

        _groupDetail.value = GroupDetail(updated, packets)
    }

    private suspend fun observeStatus() {
        // Both sources are watched at all times so a USB unplug or a Bluetooth
        // toggle registers whichever of them the capture is currently using.
        viewModelScope.launch {
            phoneSource.status.collect {
                phoneStatus = it
                publishStatus()
            }
        }
        usbSource.status.collect {
            usbStatus = it
            publishStatus()
        }
    }

    /**
     * Combines the sources' states into the one line the status strip shows.
     *
     * With both running, "scanning" means either radio is delivering and an
     * error from either is worth surfacing - a capture half working is not the
     * same as a capture working.
     */
    private fun publishStatus() {
        val selection = _uiState.value.source
        val parts = buildList {
            if (selection.usesPhone) add(phoneStatus)
            if (selection.usesUsb) add(usbStatus)
        }

        val combined = SourceStatus(
            connected = parts.any { it.connected },
            scanning = parts.any { it.scanning },
            detail = when (selection) {
                CaptureSource.BOTH -> "phone + ${usbStatus.detail}"
                CaptureSource.USB -> usbStatus.detail
                CaptureSource.PHONE -> phoneStatus.detail
            },
            error = parts.firstNotNullOfOrNull { it.error },
            // Only the board has firmware, so this survives whichever source is
            // selected rather than being combined with anything.
            firmware = usbStatus.firmware,
        )

        _uiState.value = _uiState.value.copy(status = combined, scanning = combined.scanning)
    }

    private suspend fun restoreExistingCapture() {
        val existing = withContext(Dispatchers.IO) {
            val count = store.count()
            if (count == 0L) null else Triple(count, store.recent(LIVE_WINDOW), store.loadGroups())
        } ?: return

        val (count, recent, groups) = existing
        liveWindow.clear()
        liveWindow.addAll(recent)
        accumulator.clear()
        groups.forEach { accumulator[it.address] = it }
        observedPduTypes.clear()
        groups.forEach { observedPduTypes.addAll(it.pduTypes) }

        _uiState.value = _uiState.value.copy(
            packets = recent,
            groups = groups.sortedByDescending { it.count },
            totalPackets = count,
            crcErrors = groups.sumOf { it.crcErrors.toLong() },
            pduTypes = observedPduTypes.toList(),
            message = "Recovered $count packets from the previous session.",
        )
    }

    // --- user actions ---------------------------------------------------------

    fun setSource(source: CaptureSource) {
        if (source == _uiState.value.source) return
        activeSources.forEach { it.stop() }
        deduplicator.reset()
        prefs.edit().putString(KEY_SOURCE, source.id).apply()
        _uiState.value = _uiState.value.copy(source = source, scanning = false)
        publishStatus()
    }

    /**
     * Switches the board between raw and parsed capture.
     *
     * Raw capture is what makes CRC syndrome decoding possible: the board stops
     * verifying and stripping the CRC and sends the bytes as they arrived. It
     * only affects the nRF52840 - the phone's own radio has no such mode.
     */
    fun setRawCapture(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RAW_CAPTURE, enabled).apply()
        _uiState.value = _uiState.value.copy(rawCapture = enabled)
        usbSource.setRawMode(enabled)
    }

    fun setChannel(channel: ChannelSelection) {
        _uiState.value = _uiState.value.copy(channel = channel)
        activeSources.forEach { it.setChannel(channel) }
    }

    fun setMode(mode: ViewMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun toggleMode() {
        setMode(
            if (_uiState.value.mode == ViewMode.SEQUENTIAL) {
                ViewMode.ACCUMULATED
            } else {
                ViewMode.SEQUENTIAL
            },
        )
    }

    fun setAddressFilter(filter: String) {
        _uiState.value = _uiState.value.copy(addressFilter = filter)
    }

    /** Narrows both views to one PDU type; empty restores every type. */
    fun setPduTypeFilter(pduType: String) {
        _uiState.value = _uiState.value.copy(pduTypeFilter = pduType)
    }

    /**
     * Chart visibility and scale are remembered across groups and across runs:
     * hiding the chart once should not have to be repeated for every device the
     * user opens.
     */
    fun setChartVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_CHART_VISIBLE, visible).apply()
        _uiState.value = _uiState.value.copy(chartVisible = visible)
    }

    fun setChartScale(scale: TimelineScale) {
        prefs.edit().putString(KEY_CHART_SCALE, scale.name).apply()
        _uiState.value = _uiState.value.copy(chartScale = scale)
        // A wider scale needs history the previous window never loaded.
        viewModelScope.launch { runCatching { reloadChartPoints() } }
    }

    /**
     * Pins the chart to the newest packet, or releases it back to the clock.
     * Either way the window has moved, so the points behind it are re-read.
     */
    fun setChartAtLastData(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(chartAtLastData = enabled)
        viewModelScope.launch { runCatching { reloadChartPoints() } }
    }

    fun toggleScanning() {
        val channel = _uiState.value.channel
        if (_uiState.value.scanning) {
            activeSources.forEach { it.stop() }
            CaptureService.stop(getApplication())
        } else {
            deduplicator.reset()
            activeSources.forEach { it.start(channel) }
            // Started before the first packet rather than after, so a capture
            // cannot be suspended during the window where it is running but has
            // nothing to show for it yet.
            CaptureService.start(getApplication())
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            inbox.clear()
            liveWindow.clear()
            accumulator.clear()
            observedPduTypes.clear()
            // Its merge count and clock offset are republished from here on
            // every batch, so clearing the UI figures alone would not hold.
            deduplicator.reset()
            _groupDetail.value = null
            withContext(Dispatchers.IO) { store.clear() }
            _uiState.value = _uiState.value.copy(
                packets = emptyList(),
                groups = emptyList(),
                totalPackets = 0,
                crcErrors = 0,
                // These count what the pipeline did to the packets that were
                // just deleted, so leaving them standing put a repaired total
                // beside an empty capture.
                repaired = 0,
                deduplicated = 0,
                clockOffsetMs = 0,
                // A filter on a type nothing in the capture has any more is a
                // filter that hides everything for no visible reason.
                pduTypes = emptyList(),
                pduTypeFilter = "",
                message = "History cleared.",
            )
        }
    }

    fun openGroup(group: PacketGroup) {
        viewModelScope.launch {
            val packets = withContext(Dispatchers.IO) {
                if (group.isDamagedBucket) {
                    // The row stands for many addresses at once, so it is opened
                    // by the set it collapsed rather than by a single address.
                    store.packetsForAddresses(
                        accumulator.values.filterNot { it.confirmed }.map { it.address },
                        GROUP_PAGE,
                    )
                } else {
                    store.forAddress(group.address, GROUP_PAGE)
                }
            }
            _groupDetail.value = GroupDetail(group, packets)
            reloadChartPoints()
        }
    }

    fun closeGroup() {
        _groupDetail.value = null
        _chartPoints.value = emptyList()
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // --- USB plumbing ---------------------------------------------------------

    fun usbDevice(): UsbDevice? = usbSource.findDevice()

    fun usbHasPermission(device: UsbDevice): Boolean = usbSource.hasPermission(device)

    fun connectUsb(device: UsbDevice) {
        usbSource.setRawMode(_uiState.value.rawCapture)
        usbSource.connect(device)
        usbSource.setChannel(_uiState.value.channel)
    }

    fun disconnectUsb() = usbSource.disconnect()

    fun phone(): PhoneBleSource = phoneSource

    // --- files ----------------------------------------------------------------

    fun export(uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val count = store.count()
                    val info = SessionInfo(
                        source = _uiState.value.source.label,
                        channel = _uiState.value.channel.label,
                        startedAtMs = store.firstTimestampMs() ?: System.currentTimeMillis(),
                        exportedAtMs = System.currentTimeMillis(),
                        packetCount = count,
                    )
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        SessionFiles.export(output, format, info, store::forEachPacket)
                    } ?: error("Could not open the destination file for writing.")
                    count
                }
            }

            _uiState.value = _uiState.value.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "Exported $it packets as ${format.label}." },
                    onFailure = { "Export failed: ${it.message}" },
                ),
            )
        }
    }

    fun openSessionFile(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { SessionFiles.load(it) }
                        ?: error("Could not open the file.")
                }
            }

            result.fold(
                onSuccess = { loaded ->
                    _viewer.value = ViewerState(
                        name = displayName,
                        info = loaded.info,
                        packets = loaded.packets.asReversed(),
                        groups = groupsOf(loaded.packets),
                    )
                    _uiState.value = _uiState.value.copy(busy = false, message = null)
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        message = "Could not open that file: ${it.message}",
                    )
                },
            )
        }
    }

    /**
     * Writes the open group's chart to a PNG at the currently selected scale.
     *
     * The image is rendered from the same points and the same renderer the
     * screen uses, so what lands in the file is what was on screen - just at a
     * resolution worth keeping.
     */
    fun exportChart(uri: Uri, points: List<TimelinePoint>, title: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val scale = _uiState.value.chartScale

            // The same right-hand edge the screen is using. Saving a chart
            // pinned to the last data and getting back an empty image ending at
            // the current time would be a different picture from the one the
            // user was looking at, which is the one thing this export promises.
            val endMs = if (_uiState.value.chartAtLastData) {
                points.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = TimelineRenderer.toBitmap(
                        points = points,
                        scale = scale,
                        endMs = endMs,
                        style = exportStyle(),
                        title = title,
                    )
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")
                        ?.use { out ->
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                                error("Could not encode the chart as PNG.")
                            }
                        }
                        ?: error("Could not open the destination file for writing.")
                    bitmap.recycle()
                }
            }

            _uiState.value = _uiState.value.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "Chart saved at ${scale.label} scale." },
                    onFailure = { "Could not save the chart: ${it.message}" },
                ),
            )
        }
    }

    // --- firmware update -------------------------------------------------------

    private val _stats = MutableStateFlow<RecoveryStats?>(null)
    val stats: StateFlow<RecoveryStats?> = _stats.asStateFlow()

    /** Recomputes the recovery statistics from storage and opens the screen. */
    fun openStats() {
        viewModelScope.launch {
            val computed = withContext(Dispatchers.IO) {
                runCatching { store.recoveryStats() }.getOrDefault(RecoveryStats.EMPTY)
            }
            _stats.value = computed
        }
    }

    fun closeStats() {
        _stats.value = null
    }

    private val _dfu = MutableStateFlow<DfuProgress?>(null)
    val dfu: StateFlow<DfuProgress?> = _dfu.asStateFlow()

    private var pendingPackage: DfuPackage? = null

    /**
     * Reads a DFU package, resets the board into its bootloader and installs it.
     *
     * The board disappears from USB and comes back under a different product id
     * partway through, so the second half waits for it to re-enumerate and asks
     * for permission again if the system requires it.
     */
    fun updateFirmware(uri: Uri, requestPermission: (UsbDevice) -> Unit) {
        install(requestPermission) {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { DfuPackage.read(it) }
                ?: error("Could not open the package.")
        }
    }

    /** Firmware version built into this app, or null when none was bundled. */
    fun bundledFirmwareVersion(): String? =
        BundledFirmware.version(getApplication())

    /**
     * Installs the image shipped inside the app, so updating the board needs no
     * file and no network.
     */
    fun updateFirmwareFromBundle(requestPermission: (UsbDevice) -> Unit) {
        install(requestPermission) { BundledFirmware.read(getApplication()) }
    }

    private fun install(
        requestPermission: (UsbDevice) -> Unit,
        read: () -> DfuPackage,
    ) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) { runCatching { read() } }.getOrElse {
                _dfu.value = DfuProgress.Failed(
                    it.message ?: "Could not read the package.",
                    boardInBootloader = false,
                )
                return@launch
            }

            pendingPackage = parsed
            _dfu.value = DfuProgress.Step("Resetting the board into its bootloader…")

            activeSources.forEach { it.stop() }

            // Open the board ourselves rather than insisting it was already
            // connected. Requiring the user to first pick the USB source and
            // press Connect is a step they have no way of knowing about, and
            // the failure it produces reads like a hardware fault.
            if (!connectForUpdate(requestPermission)) {
                pendingPackage = null
                return@launch
            }

            val reset = usbSource.rebootIntoBootloader()
            if (!reset) {
                _dfu.value = DfuProgress.Failed(
                    "Could not reset the board into its bootloader.",
                    boardInBootloader = false,
                )
                pendingPackage = null
                return@launch
            }

            // Give the board time to drop off the bus and come back in DFU mode.
            delay(1_500)
            awaitBootloader(requestPermission)
        }
    }

    /**
     * Gets a working connection to the board before the update starts, asking
     * for USB permission if the system has not granted it yet.
     *
     * Returns false, having already reported why, when there is nothing to talk
     * to. The distinction that matters here is between "no board is plugged in"
     * and "the board is plugged in but we were not allowed to open it" - they
     * need completely different things from the user.
     */
    private suspend fun connectForUpdate(requestPermission: (UsbDevice) -> Unit): Boolean {
        if (usbSource.status.value.connected) return true

        val device = usbSource.findDevice()
        if (device == null) {
            _dfu.value = DfuProgress.Failed(
                "No nRF52840 found. Connect it to the phone with an OTG cable and try again.",
                boardInBootloader = false,
            )
            return false
        }

        if (!usbSource.hasPermission(device)) {
            _dfu.value = DfuProgress.Step("Allow access to the board to continue…")
            requestPermission(device)

            var granted = false
            repeat(60) {                       // up to ~30 seconds
                if (usbSource.hasPermission(device)) {
                    granted = true
                    return@repeat
                }
                delay(500)
            }
            if (!granted) {
                _dfu.value = DfuProgress.Failed(
                    "Permission to use the board was not granted.",
                    boardInBootloader = false,
                )
                return false
            }
        }

        _dfu.value = DfuProgress.Step("Opening the board…")
        usbSource.connect(device)

        if (!usbSource.status.value.connected) {
            _dfu.value = DfuProgress.Failed(
                usbSource.status.value.error ?: "Could not open the board.",
                boardInBootloader = false,
            )
            return false
        }
        return true
    }

    private suspend fun awaitBootloader(requestPermission: (UsbDevice) -> Unit) {
        _dfu.value = DfuProgress.Step("Waiting for the board to reappear…")

        var asked = false
        repeat(40) {                       // up to ~20 seconds
            val device = usbSource.findDevice()
            if (device != null) {
                if (usbSource.hasPermission(device)) {
                    runUpdate(device)
                    return
                }
                if (!asked) {
                    asked = true
                    _dfu.value = DfuProgress.Step("Allow access to the board to continue.")
                    requestPermission(device)
                }
            }
            delay(500)
        }

        _dfu.value = DfuProgress.Failed(
            "The board did not reappear in bootloader mode.",
            boardInBootloader = true,
        )
        pendingPackage = null
    }

    /** Called once permission for the bootloader device has been granted. */
    fun onUsbPermissionGranted(device: UsbDevice) {
        if (pendingPackage == null) return
        viewModelScope.launch { runUpdate(device) }
    }

    private suspend fun runUpdate(device: UsbDevice) {
        val pkg = pendingPackage ?: return
        pendingPackage = null

        withContext(Dispatchers.IO) {
            val port = runCatching { usbSource.openForUpdate(device) }.getOrElse {
                _dfu.value = DfuProgress.Failed(
                    "Could not open the board: ${it.message}",
                    boardInBootloader = true,
                )
                return@withContext
            }
            try {
                DfuSession(port).install(pkg) { progress -> _dfu.value = progress }
            } finally {
                runCatching { port.close() }
            }
        }
    }

    fun dismissDfu() {
        _dfu.value = null
        pendingPackage = null
    }

    fun setViewerMode(mode: ViewMode) {
        _viewer.value = _viewer.value?.copy(mode = mode)
    }

    fun closeViewer() {
        _viewer.value = null
    }

    private fun groupsOf(packets: List<BlePacket>): List<PacketGroup> {
        val map = LinkedHashMap<String, PacketGroup>()
        for (packet in packets) {
            val key = packet.address
            val existing = map[key]
            map[key] = PacketGroup(
                address = key,
                addressType = packet.addressType.ifEmpty { existing?.addressType.orEmpty() },
                count = (existing?.count ?: 0) + 1,
                lastRssi = packet.rssi,
                lastTimestampMs = packet.timestampMs,
                lastPduType = packet.pduType,
                crcErrors = (existing?.crcErrors ?: 0) + if (packet.crcOk) 0 else 1,
                confirmedPackets = (existing?.confirmedPackets ?: 0) +
                    if (packet.crcOk || packet.wasRepaired) 1 else 0,
                repaired = (existing?.repaired ?: 0) + if (packet.wasRepaired) 1 else 0,
                pduTypes = if (packet.pduType.isEmpty()) {
                    existing?.pduTypes.orEmpty()
                } else {
                    existing?.pduTypes.orEmpty() + packet.pduType
                },
                name = (
                    if (packet.crcOk) {
                        AdvertisingDecoder.extractName(packet.rawHex, packet.adOffset)
                    } else {
                        null
                    }
                    ) ?: existing?.name,
            )
        }
        return map.values.sortedByDescending { it.count }
    }

    override fun onCleared() {
        super.onCleared()
        phoneSource.close()
        usbSource.close()
    }
}
