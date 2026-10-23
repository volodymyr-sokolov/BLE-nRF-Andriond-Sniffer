package com.blesniffer.model

/** Where an individual packet came from. */
enum class SourceKind(val id: String, val label: String, val shortLabel: String) {
    PHONE("phone", "Phone radio", "phone"),
    USB("usb", "nRF52840 (USB)", "nRF52840");

    companion object {
        fun fromId(id: String): SourceKind = entries.firstOrNull { it.id == id } ?: PHONE
    }
}

/**
 * What the user has selected to capture with.
 *
 * Distinct from [SourceKind] because [BOTH] is a capture mode, never the origin
 * of a packet: every packet still comes from exactly one radio, and which one
 * matters for what can be trusted about it.
 */
enum class CaptureSource(val id: String, val label: String) {
    PHONE("phone", "Phone"),
    USB("usb", "nRF52840"),
    BOTH("both", "Both");

    val usesPhone: Boolean get() = this == PHONE || this == BOTH
    val usesUsb: Boolean get() = this == USB || this == BOTH

    companion object {
        fun fromId(id: String): CaptureSource = entries.firstOrNull { it.id == id } ?: PHONE
    }
}

/**
 * How a corrupt packet was repaired, per the recovery pipeline in section 6 of
 * the specification. Ordered by how much the result can be trusted.
 */
enum class RecoveryMethod(val id: String, val label: String) {
    CRC_SYNDROME("crc_syndrome", "CRC-24 syndrome decode"),
    CROSS_SOURCE("cross_source", "Clean copy from the other receiver"),
    CROSS_TIME("cross_time", "Majority vote over repeats"),
    TEMPLATE("template", "Device byte template"),
    ML_PRIOR("ml_prior", "Learned byte prior, CRC-verified"),
    UNRECOVERABLE("unrecoverable", "Not recoverable");

    companion object {
        fun fromId(id: String?): RecoveryMethod? =
            entries.firstOrNull { it.id == id }
    }
}

/**
 * Result of running a corrupt packet through the recovery pipeline.
 *
 * The specification is emphatic that a recovered packet must never overwrite
 * what was actually received (section 5), so this sits alongside the original
 * bytes rather than replacing them, and every field needed to judge the repair
 * travels with it.
 */
data class RecoveryInfo(
    val method: RecoveryMethod,
    val correctedHex: String,
    val bitsCorrected: Int,
    val confidence: Float,
    val candidatesEvaluated: Int,
    val originalCrcOk: Boolean,
) {
    val succeeded: Boolean get() = method != RecoveryMethod.UNRECOVERABLE
}

/**
 * Advertising channel to listen on. [ALL] is round-robin rather than genuinely
 * simultaneous - a single radio can only sit on one frequency at a time.
 */
enum class ChannelSelection(val id: String, val label: String) {
    CH37("37", "37"),
    CH38("38", "38"),
    CH39("39", "39"),
    ALL("all", "All");

    /** The wire form understood by the firmware's `ch` command. */
    val command: String get() = id

    companion object {
        fun fromId(id: String): ChannelSelection = entries.firstOrNull { it.id == id } ?: ALL
    }
}

enum class ViewMode { SEQUENTIAL, ACCUMULATED }

/** Time span covered by the timeline chart's horizontal axis. */
enum class TimelineScale(val label: String, val spanMs: Long) {
    SECOND("1 sec", 1_000L),
    TEN_SECONDS("10 sec", 10_000L),
    MINUTE("1 min", 60_000L),
    TEN_MINUTES("10 min", 600_000L);

    /** Width of one column, given the chart's fixed column count. */
    val binMs: Long get() = (spanMs / BIN_COUNT).coerceAtLeast(1L)

    companion object {
        /** Columns across the axis; a factor of every span above. */
        const val BIN_COUNT = 60

        fun fromId(id: String): TimelineScale = entries.firstOrNull { it.name == id } ?: TEN_SECONDS
    }
}

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    JSON("JSON", "json", "application/json"),
    CSV("CSV", "csv", "text/csv"),
}

/**
 * One received advertising packet.
 *
 * [rawHex] is stored rather than a ByteArray so the same value can go straight
 * into SQLite, the export files and the clipboard without re-encoding. What it
 * contains depends on the source: the nRF52840 delivers a complete PDU starting
 * at its 2 byte header, while Android's scan API only exposes the advertising
 * data. [adOffset] points at the first AD structure in either case, or -1 when
 * the PDU carries no AD structures at all (a CONNECT_IND, say).
 */
data class BlePacket(
    val id: Long,
    val timestampMs: Long,
    val deviceTimeUs: Long,
    val channel: Int,
    val rssi: Int,
    val crcOk: Boolean,
    val address: String,
    val addressType: String,
    val pduType: String,
    val rawHex: String,
    val adOffset: Int,
    val source: SourceKind,
    /**
     * Every receiver that saw this packet. Holds more than one entry after
     * deduplication has folded the host's copy into the nRF52840's.
     */
    val sources: Set<SourceKind> = setOf(source),
    /** Null when the packet was received cleanly, or was never repairable. */
    val recovery: RecoveryInfo? = null,
    /**
     * True when [rawHex] ends with the three received CRC bytes.
     *
     * Only the firmware's raw capture mode delivers them; in its normal mode the
     * radio verifies and strips the CRC. Everything algebraic about recovery -
     * syndrome decoding, and the verification the learned prior depends on -
     * needs this to be true.
     */
    val hasCrcField: Boolean = false,
) {
    /** Channel 0 means the source could not tell us - Android never reports one. */
    val channelLabel: String get() = if (channel in 37..39) channel.toString() else "—"

    val addressLabel: String get() = address.ifEmpty { "(no address)" }

    /** True when this packet was seen by both receivers independently. */
    val corroborated: Boolean get() = sources.size > 1

    /** Bytes to decode: the repaired ones when a repair was accepted. */
    val effectiveHex: String
        get() = recovery?.takeIf { it.succeeded }?.correctedHex ?: rawHex

    val wasRepaired: Boolean get() = recovery?.succeeded == true

    companion object {
        const val CHANNEL_UNKNOWN = 0
    }
}

/** A group of packets sharing an advertiser address, for the accumulation view. */
data class PacketGroup(
    val address: String,
    val addressType: String,
    val count: Int,
    val lastRssi: Int,
    val lastTimestampMs: Long,
    val lastPduType: String,
    val crcErrors: Int,
    val name: String?,
    /**
     * Packets from this address that were either received intact or repaired.
     *
     * Zero means the address itself is unverified: it was read out of packets
     * that failed CRC and could not be put back together, so the bytes it was
     * parsed from may well be corrupt. Such an "address" is usually not a device
     * at all - a few flipped bits in the address field of one damaged packet
     * mint a brand new one - which is why they are collapsed rather than listed.
     */
    val confirmedPackets: Int = 0,
    /**
     * Packets from this address that failed CRC and were then put back together.
     *
     * Shown beside the error count rather than folded into it: "12 CRC" next to
     * "9 repaired" says the device is being heard through interference and most
     * of it survived, which is a different situation from twelve errors and
     * nothing recovered, and the two are indistinguishable from one number.
     */
    val repaired: Int = 0,
    /** Devices folded into this row; more than one only for the damaged bucket. */
    val mergedDevices: Int = 1,
    /**
     * Every PDU type this advertiser has been seen sending.
     *
     * A set rather than [lastPduType] because the packet type filter asks
     * whether a device ever sends a type, and a beacon that answers a SCAN_REQ
     * once an hour would otherwise disappear from the filtered list for all the
     * time in between.
     */
    val pduTypes: Set<String> = emptySet(),
) {
    val confirmed: Boolean get() = confirmedPackets > 0

    /** True for the synthetic row collecting every unidentifiable advertiser. */
    val isDamagedBucket: Boolean get() = address == DAMAGED_BUCKET

    companion object {
        /** Sentinel address for the collapsed row of unidentifiable packets. */
        const val DAMAGED_BUCKET = "__damaged__"
    }
}

/**
 * A packet reduced to what the timeline chart needs.
 *
 * The chart covers a far longer span than the packet list keeps in memory, so
 * these are read straight from storage as a narrow projection rather than being
 * derived from the list.
 */
data class TimelinePoint(val timestampMs: Long, val channel: Int, val crcOk: Boolean)

fun BlePacket.toTimelinePoint() = TimelinePoint(timestampMs, channel, crcOk)

/**
 * Packets within one advertiser that carry byte-identical payloads.
 *
 * Signal strength is deliberately not part of the identity: an advertiser
 * repeating the same frame produces a different RSSI every time, and treating
 * those as distinct would defeat the point of collapsing them.
 */
data class PayloadGroup(
    val rawHex: String,
    val crcOk: Boolean,
    val adOffset: Int,
    val pduType: String,
    val address: String,
    val addressType: String,
    val count: Int,
    val lastRssi: Int,
    val lastTimestampMs: Long,
    val channels: Set<Int>,
    /** The most recent packet in the group, for the expanded detail view. */
    val representative: BlePacket,
    /**
     * True for the single row that collects every corrupt frame of this device.
     *
     * Damage is different every time, so corrupt packets never collapse into
     * each other the way repeated clean frames do: a device heard through
     * interference produced one row per corrupt packet and buried the two or
     * three frames it actually sends under hundreds of one-off rows. They are
     * gathered into one row instead, counted and left uncollapsed underneath.
     */
    val merged: Boolean = false,
    /** How many byte-distinct frames this row stands for; 1 unless [merged]. */
    val distinctPayloads: Int = 1,
    /** Repaired packets inside this row. Only ever non-zero when [merged]. */
    val repaired: Int = 0,
) {
    /** Stable identity for list keys and expansion state. */
    val key: String get() = if (merged) MERGED_ERRORS_KEY else "$rawHex|$crcOk"

    companion object {
        const val MERGED_ERRORS_KEY = "__crc_errors__"
    }
}

/** Connection/scan state of whichever source is currently selected. */
data class SourceStatus(
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val detail: String = "",
    val error: String? = null,
    /**
     * Firmware version the board reported, or null when nothing is connected.
     *
     * Kept out of [detail] because it does not belong on the capture screen: it
     * changes only when the board is reflashed, so as a running status line it
     * was a constant taking up room beside numbers that move. It is shown in
     * "How repair works" instead, beside what the two capture modes do.
     */
    val firmware: String? = null,
)
