package com.blesniffer.export

import com.blesniffer.model.BlePacket
import com.blesniffer.model.ExportFormat
import com.blesniffer.model.RecoveryInfo
import com.blesniffer.model.RecoveryMethod
import com.blesniffer.model.SourceKind
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Metadata written alongside the packets so an exported file stands alone. */
data class SessionInfo(
    val source: String,
    val channel: String,
    val startedAtMs: Long,
    val exportedAtMs: Long,
    val packetCount: Long,
)

/**
 * Writes and reads capture files.
 *
 * Both formats round-trip: anything exported can be reopened in the viewer. JSON
 * keeps the session metadata in an object header; CSV puts it in `#` comment
 * lines above the header row, which spreadsheet tools ignore and the importer
 * understands.
 *
 * Exports stream row by row straight from SQLite so a session of any size can be
 * written without holding it in memory.
 */
object SessionFiles {

    private const val CSV_HEADER =
        "index,timestamp_iso,timestamp_ms,device_time_us,channel,rssi_dbm,crc_ok," +
            "address,address_type,pdu_type,raw_hex,ad_offset,source,sources," +
            "recovery_method,recovery_hex,recovery_bits,recovery_confidence," +
            "recovery_candidates"

    private fun isoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    // --- export ---------------------------------------------------------------

    /**
     * [forEachPacket] is a push-style iteration - typically [PacketStore.forEachPacket]
     * - so rows go from the database cursor to the file without an intermediate
     * list, and so this function can be exercised without a database.
     */
    fun export(
        output: OutputStream,
        format: ExportFormat,
        info: SessionInfo,
        forEachPacket: ((BlePacket) -> Unit) -> Unit,
    ) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            val iso = isoFormatter()
            when (format) {
                ExportFormat.JSON -> {
                    writer.write("{\n")
                    writer.write("  \"format\": \"ble-sniffer-session\",\n")
                    writer.write("  \"version\": 1,\n")
                    writer.write("  \"session\": {\n")
                    writer.write("    \"source\": ${quote(info.source)},\n")
                    writer.write("    \"channel\": ${quote(info.channel)},\n")
                    writer.write("    \"startedAt\": ${quote(iso.format(Date(info.startedAtMs)))},\n")
                    writer.write("    \"exportedAt\": ${quote(iso.format(Date(info.exportedAtMs)))},\n")
                    writer.write("    \"packetCount\": ${info.packetCount}\n")
                    writer.write("  },\n")
                    writer.write("  \"packets\": [\n")

                    var index = 0
                    forEachPacket { packet ->
                        if (index > 0) writer.write(",\n")
                        writer.write("    ")
                        writer.write(packetToJson(packet, index, iso))
                        index++
                    }

                    writer.write("\n  ]\n}\n")
                }

                ExportFormat.CSV -> {
                    writer.write("# ble-sniffer-session v1\n")
                    writer.write("# source=${info.source}\n")
                    writer.write("# channel=${info.channel}\n")
                    writer.write("# startedAt=${iso.format(Date(info.startedAtMs))}\n")
                    writer.write("# exportedAt=${iso.format(Date(info.exportedAtMs))}\n")
                    writer.write(CSV_HEADER)
                    writer.write("\n")

                    var index = 0
                    forEachPacket { packet ->
                        writer.write(packetToCsv(packet, index, iso))
                        writer.write("\n")
                        index++
                    }
                }
            }
        }
    }

    private fun packetToJson(packet: BlePacket, index: Int, iso: SimpleDateFormat): String =
        buildString {
            append("{")
            append("\"index\":").append(index)
            append(",\"timestamp\":").append(quote(iso.format(Date(packet.timestampMs))))
            append(",\"timestampMs\":").append(packet.timestampMs)
            append(",\"deviceTimeUs\":").append(packet.deviceTimeUs)
            append(",\"channel\":").append(packet.channel)
            append(",\"rssi\":").append(packet.rssi)
            append(",\"crcOk\":").append(packet.crcOk)
            append(",\"address\":").append(quote(packet.address))
            append(",\"addressType\":").append(quote(packet.addressType))
            append(",\"pduType\":").append(quote(packet.pduType))
            append(",\"raw\":").append(quote(packet.rawHex))
            append(",\"adOffset\":").append(packet.adOffset)
            append(",\"source\":").append(quote(packet.source.id))
            append(",\"sources\":[")
            append(packet.sources.joinToString(",") { quote(it.id) })
            append("]")
            packet.recovery?.let { r ->
                append(",\"recovery\":{")
                append("\"method\":").append(quote(r.method.id))
                append(",\"corrected\":").append(quote(r.correctedHex))
                append(",\"bitsCorrected\":").append(r.bitsCorrected)
                append(",\"confidence\":").append(r.confidence)
                append(",\"candidatesEvaluated\":").append(r.candidatesEvaluated)
                append(",\"originalCrcOk\":").append(r.originalCrcOk)
                append("}")
            }
            append("}")
        }

    private fun packetToCsv(packet: BlePacket, index: Int, iso: SimpleDateFormat): String =
        listOf(
            index.toString(),
            iso.format(Date(packet.timestampMs)),
            packet.timestampMs.toString(),
            packet.deviceTimeUs.toString(),
            packet.channel.toString(),
            packet.rssi.toString(),
            if (packet.crcOk) "1" else "0",
            packet.address,
            packet.addressType,
            packet.pduType,
            packet.rawHex,
            packet.adOffset.toString(),
            packet.source.id,
            packet.sources.joinToString("+") { it.id },
            packet.recovery?.method?.id.orEmpty(),
            packet.recovery?.correctedHex.orEmpty(),
            packet.recovery?.bitsCorrected?.toString().orEmpty(),
            packet.recovery?.confidence?.toString().orEmpty(),
            packet.recovery?.candidatesEvaluated?.toString().orEmpty(),
        ).joinToString(",") { csvEscape(it) }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun quote(value: String): String = JSONObject.quote(value)

    // --- import ---------------------------------------------------------------

    data class LoadedSession(val info: SessionInfo, val packets: List<BlePacket>)

    /**
     * Reads a previously exported file. The format is detected from the content
     * rather than the file name, because documents picked through the storage
     * picker often arrive with an opaque name.
     */
    fun load(input: InputStream): LoadedSession {
        val text = input.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{")) loadJson(trimmed) else loadCsv(text)
    }

    private fun loadJson(text: String): LoadedSession {
        val root = JSONObject(text)
        val session = root.optJSONObject("session")
        val array = root.optJSONArray("packets")
            ?: throw IllegalArgumentException("No \"packets\" array in this JSON file.")

        val packets = ArrayList<BlePacket>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            packets += BlePacket(
                id = i.toLong() + 1,
                timestampMs = o.optLong("timestampMs"),
                deviceTimeUs = o.optLong("deviceTimeUs"),
                channel = o.optInt("channel"),
                rssi = o.optInt("rssi"),
                crcOk = o.optBoolean("crcOk", true),
                address = o.optString("address"),
                addressType = o.optString("addressType"),
                pduType = o.optString("pduType"),
                rawHex = o.optString("raw"),
                adOffset = o.optInt("adOffset", -1),
                source = SourceKind.fromId(o.optString("source")),
                sources = o.optJSONArray("sources")?.let { arr ->
                    (0 until arr.length()).map { SourceKind.fromId(arr.optString(it)) }.toSet()
                } ?: setOf(SourceKind.fromId(o.optString("source"))),
                recovery = o.optJSONObject("recovery")?.let { r ->
                    RecoveryMethod.fromId(r.optString("method"))?.let { method ->
                        RecoveryInfo(
                            method = method,
                            correctedHex = r.optString("corrected"),
                            bitsCorrected = r.optInt("bitsCorrected"),
                            confidence = r.optDouble("confidence").toFloat(),
                            candidatesEvaluated = r.optInt("candidatesEvaluated"),
                            originalCrcOk = r.optBoolean("originalCrcOk"),
                        )
                    }
                },
            )
        }

        val info = SessionInfo(
            source = session?.optString("source").orEmpty().ifEmpty { "unknown" },
            channel = session?.optString("channel").orEmpty().ifEmpty { "—" },
            startedAtMs = packets.firstOrNull()?.timestampMs ?: 0L,
            exportedAtMs = 0L,
            packetCount = packets.size.toLong(),
        )
        return LoadedSession(info, packets)
    }

    private fun loadCsv(text: String): LoadedSession {
        var source = "unknown"
        var channel = "—"
        val packets = mutableListOf<BlePacket>()
        var headerSeen = false
        var index = 0L

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue

            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                when {
                    body.startsWith("source=") -> source = body.removePrefix("source=")
                    body.startsWith("channel=") -> channel = body.removePrefix("channel=")
                }
                continue
            }

            if (!headerSeen) {
                headerSeen = true
                // Tolerate files saved without the comment block.
                if (line.startsWith("index,") || line.contains("raw_hex")) continue
            }

            val cells = splitCsv(line)
            if (cells.size < 13) continue

            index++
            packets += BlePacket(
                id = index,
                timestampMs = cells[2].toLongOrNull() ?: 0L,
                deviceTimeUs = cells[3].toLongOrNull() ?: 0L,
                channel = cells[4].toIntOrNull() ?: 0,
                rssi = cells[5].toIntOrNull() ?: 0,
                crcOk = cells[6] != "0",
                address = cells[7],
                addressType = cells[8],
                pduType = cells[9],
                rawHex = cells[10],
                adOffset = cells[11].toIntOrNull() ?: -1,
                source = SourceKind.fromId(cells[12]),
                sources = cells.getOrNull(13).orEmpty()
                    .split('+').filter { it.isNotBlank() }.map(SourceKind::fromId).toSet()
                    .ifEmpty { setOf(SourceKind.fromId(cells[12])) },
                recovery = RecoveryMethod.fromId(cells.getOrNull(14))?.let { method ->
                    RecoveryInfo(
                        method = method,
                        correctedHex = cells.getOrNull(15).orEmpty(),
                        bitsCorrected = cells.getOrNull(16)?.toIntOrNull() ?: 0,
                        confidence = cells.getOrNull(17)?.toFloatOrNull() ?: 0f,
                        candidatesEvaluated = cells.getOrNull(18)?.toIntOrNull() ?: 0,
                        originalCrcOk = false,
                    )
                },
            )
        }

        if (packets.isEmpty()) {
            throw IllegalArgumentException("No packet rows found in this CSV file.")
        }

        return LoadedSession(
            SessionInfo(source, channel, packets.first().timestampMs, 0L, packets.size.toLong()),
            packets,
        )
    }

    private fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    fun suggestFileName(format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "ble-capture-$stamp.${format.extension}"
    }
}
