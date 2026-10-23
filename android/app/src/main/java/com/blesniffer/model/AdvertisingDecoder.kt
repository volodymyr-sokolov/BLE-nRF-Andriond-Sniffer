package com.blesniffer.model

/** One line of the expanded packet view. */
data class DecodedField(val label: String, val value: String)

data class DecodedPacket(
    val header: List<DecodedField>,
    val structures: List<DecodedField>,
    val problems: List<String>,
)

/**
 * Turns a raw advertising PDU (or, for phone captures, a bare advertising data
 * blob) into labelled fields.
 *
 * Nothing here throws on malformed input: a sniffer sees corrupt packets all the
 * time, and a packet that fails CRC still has to render. Anything that does not
 * parse is reported through [DecodedPacket.problems] and the raw bytes remain
 * available to the caller.
 */
object AdvertisingDecoder {

    // Advertising PDU types from the link layer header's lower nibble.
    private val PDU_TYPES = mapOf(
        0x0 to "ADV_IND",
        0x1 to "ADV_DIRECT_IND",
        0x2 to "ADV_NONCONN_IND",
        0x3 to "SCAN_REQ",
        0x4 to "SCAN_RSP",
        0x5 to "CONNECT_IND",
        0x6 to "ADV_SCAN_IND",
        0x7 to "ADV_EXT_IND",
        0x8 to "AUX_CONNECT_RSP",
    )

    private val AD_TYPES = mapOf(
        0x01 to "Flags",
        0x02 to "Incomplete 16-bit Service UUIDs",
        0x03 to "Complete 16-bit Service UUIDs",
        0x04 to "Incomplete 32-bit Service UUIDs",
        0x05 to "Complete 32-bit Service UUIDs",
        0x06 to "Incomplete 128-bit Service UUIDs",
        0x07 to "Complete 128-bit Service UUIDs",
        0x08 to "Shortened Local Name",
        0x09 to "Complete Local Name",
        0x0A to "TX Power Level",
        0x0D to "Class of Device",
        0x0E to "Simple Pairing Hash C-192",
        0x0F to "Simple Pairing Randomizer R-192",
        0x10 to "Security Manager TK Value",
        0x11 to "Security Manager OOB Flags",
        0x12 to "Peripheral Connection Interval Range",
        0x14 to "16-bit Service Solicitation UUIDs",
        0x15 to "128-bit Service Solicitation UUIDs",
        0x16 to "Service Data (16-bit UUID)",
        0x17 to "Public Target Address",
        0x18 to "Random Target Address",
        0x19 to "Appearance",
        0x1A to "Advertising Interval",
        0x1B to "LE Bluetooth Device Address",
        0x1C to "LE Role",
        0x1F to "32-bit Service Solicitation UUIDs",
        0x20 to "Service Data (32-bit UUID)",
        0x21 to "Service Data (128-bit UUID)",
        0x24 to "URI",
        0x25 to "Indoor Positioning",
        0x26 to "Transport Discovery Data",
        0x27 to "LE Supported Features",
        0x28 to "Channel Map Update Indication",
        0x2A to "Mesh Message",
        0x2B to "Mesh Beacon",
        0x2C to "BIGInfo",
        0x2D to "Broadcast Code",
        0x30 to "3D Information Data",
        0xFF to "Manufacturer Specific Data",
    )

    // A short list covering the companies that dominate the air in practice.
    private val COMPANIES = mapOf(
        0x0006 to "Microsoft",
        0x000F to "Broadcom",
        0x0075 to "Samsung",
        0x004C to "Apple",
        0x0059 to "Nordic Semiconductor",
        0x00E0 to "Google",
        0x0087 to "Garmin",
        0x00D2 to "Dialog Semiconductor",
        0x0157 to "Anhui Huami",
        0x0171 to "Amazon",
        0x0499 to "Ruuvi Innovations",
        0x038F to "Xiaomi",
        0x05A7 to "Sonos",
        0x0A12 to "Tile",
    )

    private val SERVICE_UUIDS = mapOf(
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x180A to "Device Information",
        0x180F to "Battery Service",
        0x1812 to "HID over GATT",
        0xFD6F to "Exposure Notification",
        0xFE9F to "Google",
        0xFEAA to "Eddystone",
    )

    fun decode(packet: BlePacket): DecodedPacket {
        val raw = packet.rawHex.hexToBytes()
        val problems = mutableListOf<String>()

        if (raw.isEmpty() && packet.rawHex.isNotEmpty()) {
            return DecodedPacket(emptyList(), emptyList(), listOf("Raw payload is not valid hex."))
        }
        if (!packet.crcOk) {
            problems += "CRC mismatch - the payload below may be corrupt."
        }

        val header = mutableListOf<DecodedField>()
        header += DecodedField("Source", packet.source.label)
        header += DecodedField("Channel", packet.channelLabel)
        header += DecodedField("RSSI", "${packet.rssi} dBm")
        header += DecodedField("CRC", if (packet.crcOk) "OK" else "FAILED")

        if (packet.source == SourceKind.USB && raw.size >= 2) {
            decodePduHeader(raw, header, problems)
        } else {
            header += DecodedField("PDU", "not exposed by the Android scan API")
        }

        if (packet.address.isNotEmpty()) {
            val type = packet.addressType.ifEmpty { "unknown" }
            header += DecodedField("Advertiser address", "${packet.address} ($type)")
        }

        val structures = if (packet.adOffset >= 0 && packet.adOffset <= raw.size) {
            decodeAdStructures(raw, packet.adOffset, problems)
        } else {
            emptyList()
        }

        return DecodedPacket(header, structures, problems)
    }

    private fun decodePduHeader(
        raw: ByteArray,
        header: MutableList<DecodedField>,
        problems: MutableList<String>,
    ) {
        val b0 = raw[0].toInt() and 0xFF
        val type = b0 and 0x0F
        val length = raw[1].toInt() and 0xFF

        header += DecodedField(
            "PDU type",
            "${PDU_TYPES[type] ?: "Reserved"} (0x${"%X".format(type)})",
        )
        header += DecodedField("ChSel / TxAdd / RxAdd", buildString {
            append(if (b0 and 0x20 != 0) "1" else "0")
            append(" / ")
            append(if (b0 and 0x40 != 0) "1 (random)" else "0 (public)")
            append(" / ")
            append(if (b0 and 0x80 != 0) "1 (random)" else "0 (public)")
        })
        header += DecodedField("Payload length", "$length bytes")

        val available = raw.size - 2
        if (length != available) {
            problems += "Header declares $length payload bytes but $available were captured."
        }
    }

    private fun decodeAdStructures(
        raw: ByteArray,
        start: Int,
        problems: MutableList<String>,
    ): List<DecodedField> {
        val fields = mutableListOf<DecodedField>()
        var i = start

        while (i < raw.size) {
            val length = raw[i].toInt() and 0xFF
            if (length == 0) {
                // Zero length is the conventional end-of-data padding.
                break
            }
            if (i + length >= raw.size + 1) {
                problems += "Truncated AD structure at offset $i (declared $length bytes)."
                break
            }

            val type = raw[i + 1].toInt() and 0xFF
            val from = i + 2
            val to = minOf(i + 1 + length, raw.size)
            val data = if (from <= to) raw.copyOfRange(from, to) else ByteArray(0)

            val label = AD_TYPES[type] ?: "Unknown AD type 0x%02X".format(type)
            fields += DecodedField(label, describe(type, data))

            i += length + 1
        }

        return fields
    }

    private fun describe(type: Int, data: ByteArray): String = when (type) {
        0x01 -> describeFlags(data)
        0x02, 0x03 -> uuids16(data)
        0x14 -> uuids16(data)
        0x04, 0x05, 0x1F -> uuids32(data)
        0x06, 0x07, 0x15 -> uuids128(data)
        0x08, 0x09 -> asText(data)
        0x24 -> asText(data)
        0x0A -> if (data.isNotEmpty()) "${data[0].toInt()} dBm" else "(empty)"
        0x19 -> if (data.size >= 2) "0x%04X".format(le16(data, 0)) else "(malformed)"
        0x1A -> if (data.size >= 2) advInterval(le16(data, 0)) else "(malformed)"
        0x1B -> if (data.size >= 6) data.toBleAddress(0) else "(malformed)"
        0x12 -> describeIntervalRange(data)
        0x16 -> describeServiceData(data, 2)
        0x20 -> describeServiceData(data, 4)
        0x21 -> describeServiceData(data, 16)
        0xFF -> describeManufacturer(data)
        else -> if (data.isEmpty()) "(empty)" else data.toHex()
    }

    private fun describeFlags(data: ByteArray): String {
        if (data.isEmpty()) return "(empty)"
        val v = data[0].toInt() and 0xFF
        val set = buildList {
            if (v and 0x01 != 0) add("LE Limited Discoverable")
            if (v and 0x02 != 0) add("LE General Discoverable")
            if (v and 0x04 != 0) add("BR/EDR Not Supported")
            if (v and 0x08 != 0) add("Simultaneous LE + BR/EDR (Controller)")
            if (v and 0x10 != 0) add("Simultaneous LE + BR/EDR (Host)")
        }
        val names = if (set.isEmpty()) "none" else set.joinToString(", ")
        return "0x%02X - %s".format(v, names)
    }

    private fun uuids16(data: ByteArray): String {
        if (data.size < 2) return "(malformed)"
        return (0..data.size - 2 step 2).joinToString(", ") { off ->
            val uuid = le16(data, off)
            val known = SERVICE_UUIDS[uuid]
            if (known != null) "0x%04X ($known)".format(uuid) else "0x%04X".format(uuid)
        }
    }

    private fun uuids32(data: ByteArray): String {
        if (data.size < 4) return "(malformed)"
        return (0..data.size - 4 step 4).joinToString(", ") { off ->
            "0x%08X".format(le32(data, off))
        }
    }

    private fun uuids128(data: ByteArray): String {
        if (data.size < 16) return "(malformed)"
        return (0..data.size - 16 step 16).joinToString(", ") { off ->
            formatUuid128(data, off)
        }
    }

    private fun formatUuid128(data: ByteArray, offset: Int): String {
        // 128 bit UUIDs also go out least significant byte first.
        val be = ByteArray(16) { data[offset + 15 - it] }
        val hex = be.toHex().lowercase()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun describeServiceData(data: ByteArray, uuidBytes: Int): String {
        if (data.size < uuidBytes) return "(malformed)"
        val uuid = when (uuidBytes) {
            2 -> {
                val v = le16(data, 0)
                val known = SERVICE_UUIDS[v]
                if (known != null) "0x%04X ($known)".format(v) else "0x%04X".format(v)
            }
            4 -> "0x%08X".format(le32(data, 0))
            else -> formatUuid128(data, 0)
        }
        val payload = data.copyOfRange(uuidBytes, data.size)
        return if (payload.isEmpty()) uuid else "$uuid, data ${payload.toHex()}"
    }

    private fun describeManufacturer(data: ByteArray): String {
        if (data.size < 2) return if (data.isEmpty()) "(empty)" else data.toHex()
        val company = le16(data, 0)
        val name = COMPANIES[company] ?: "unknown"
        val payload = data.copyOfRange(2, data.size)
        val body = if (payload.isEmpty()) "(no data)" else payload.toHex()
        return "0x%04X ($name), $body".format(company)
    }

    private fun describeIntervalRange(data: ByteArray): String {
        if (data.size < 4) return "(malformed)"
        val min = le16(data, 0)
        val max = le16(data, 2)
        // Connection interval is counted in 1.25 ms units.
        return "%.2f ms .. %.2f ms".format(min * 1.25, max * 1.25)
    }

    private fun advInterval(units: Int): String = "%.1f ms".format(units * 0.625)

    private fun asText(data: ByteArray): String {
        if (data.isEmpty()) return "(empty)"
        val text = String(data, Charsets.UTF_8)
        // Advertised names occasionally contain junk; show hex when they do.
        return if (text.any { it.code < 0x20 && it != '\n' }) data.toHex() else text
    }

    private fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * Pulls the advertised name out of a packet, for labelling groups. Returns
     * null when the packet carries no name.
     */
    fun extractName(rawHex: String, adOffset: Int): String? {
        if (adOffset < 0) return null
        val raw = rawHex.hexToBytes()
        var i = adOffset
        while (i < raw.size) {
            val length = raw[i].toInt() and 0xFF
            if (length == 0) break
            val type = if (i + 1 < raw.size) raw[i + 1].toInt() and 0xFF else break
            if (type == 0x08 || type == 0x09) {
                val from = i + 2
                val to = minOf(i + 1 + length, raw.size)
                if (from < to) {
                    val text = String(raw.copyOfRange(from, to), Charsets.UTF_8)
                    if (text.isNotBlank() && text.none { it.code < 0x20 }) return text
                }
            }
            i += length + 1
        }
        return null
    }

    /**
     * Works out where the AD structures begin inside a full PDU, and which
     * address the packet should be grouped under.
     *
     * Returns null when the PDU is too short to interpret.
     */
    fun locate(raw: ByteArray): PduLayout? {
        if (raw.size < 2) return null
        val b0 = raw[0].toInt() and 0xFF
        val type = b0 and 0x0F
        val txRandom = b0 and 0x40 != 0
        val payload = 2

        // Types carrying AdvA followed by advertising data.
        return when (type) {
            0x0, 0x2, 0x4, 0x6 -> PduLayout(
                pduType = PDU_TYPES[type] ?: "Reserved",
                address = raw.toBleAddress(payload),
                addressType = if (txRandom) "random" else "public",
                adOffset = if (raw.size >= payload + 6) payload + 6 else -1,
            )
            // AdvA then TargetA, no advertising data.
            0x1 -> PduLayout(
                pduType = "ADV_DIRECT_IND",
                address = raw.toBleAddress(payload),
                addressType = if (txRandom) "random" else "public",
                adOffset = -1,
            )
            // ScanA then AdvA: group under the advertiser being scanned.
            0x3 -> PduLayout(
                pduType = "SCAN_REQ",
                address = raw.toBleAddress(payload + 6),
                addressType = if (b0 and 0x80 != 0) "random" else "public",
                adOffset = -1,
            )
            // InitA then AdvA then link layer data.
            0x5 -> PduLayout(
                pduType = "CONNECT_IND",
                address = raw.toBleAddress(payload + 6),
                addressType = if (b0 and 0x80 != 0) "random" else "public",
                adOffset = -1,
            )
            // Extended advertising carries a variable header we do not unpack;
            // there is no plain AdvA at a fixed offset.
            0x7 -> PduLayout("ADV_EXT_IND", "", "", -1)
            0x8 -> PduLayout("AUX_CONNECT_RSP", "", "", -1)
            else -> PduLayout("Reserved 0x%X".format(type), "", "", -1)
        }
    }

    data class PduLayout(
        val pduType: String,
        val address: String,
        val addressType: String,
        val adOffset: Int,
    )
}
