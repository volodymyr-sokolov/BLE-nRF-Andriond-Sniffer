package com.blesniffer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.blesniffer.model.BlePacket
import com.blesniffer.model.PacketGroup
import com.blesniffer.model.MethodDetail
import com.blesniffer.model.RecoveryInfo
import com.blesniffer.model.RecoveryStats
import com.blesniffer.model.RecoveryMethod
import com.blesniffer.model.SourceKind
import com.blesniffer.model.TimelinePoint

/**
 * SQLite backing store for the capture in progress.
 *
 * Everything received goes here, which is what keeps memory bounded: the UI only
 * ever holds a window of recent packets, and anything older is read back on
 * demand (drilling into a group) or streamed straight to a file (exporting).
 *
 * Only the current session lives in the database. Older captures are kept as
 * exported JSON/CSV files and opened through the viewer, so there is no need to
 * carry historical sessions around.
 */
class PacketStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    /**
     * Rows are evicted oldest-first beyond this point so a capture left running
     * overnight cannot fill the device. At a busy 50 packets/second this is a
     * couple of hours of history.
     */
    private val maxRows = 500_000

    private class Helper(context: Context) : SQLiteOpenHelper(context, "capture.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE packets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_ms INTEGER NOT NULL,
                    dev_us INTEGER NOT NULL,
                    channel INTEGER NOT NULL,
                    rssi INTEGER NOT NULL,
                    crc_ok INTEGER NOT NULL,
                    address TEXT NOT NULL,
                    addr_type TEXT NOT NULL,
                    pdu_type TEXT NOT NULL,
                    raw_hex TEXT NOT NULL,
                    ad_offset INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    sources TEXT NOT NULL DEFAULT '',
                    rec_method TEXT,
                    rec_hex TEXT,
                    rec_bits INTEGER NOT NULL DEFAULT 0,
                    rec_conf REAL NOT NULL DEFAULT 0,
                    rec_candidates INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_packets_address ON packets(address, id)")
        }

        /**
         * Adds new columns in place rather than starting again.
         *
         * The previous version of this dropped the table, which threw away a
         * capture that was still wanted the moment the schema gained the
         * deduplication and recovery columns. A capture is the expensive thing
         * here - it can take hours to collect and cannot be re-recorded - so a
         * schema change must never be the reason one disappears.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                for (column in listOf(
                    "sources TEXT NOT NULL DEFAULT ''",
                    "rec_method TEXT",
                    "rec_hex TEXT",
                    "rec_bits INTEGER NOT NULL DEFAULT 0",
                    "rec_conf REAL NOT NULL DEFAULT 0",
                    "rec_candidates INTEGER NOT NULL DEFAULT 0",
                )) {
                    // Tolerate a column that is somehow already there rather
                    // than failing the upgrade and losing everything.
                    runCatching { db.execSQL("ALTER TABLE packets ADD COLUMN $column") }
                }
            }
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // Write-ahead logging keeps the capture writer from blocking reads
            // that the list is doing on the main thread's behalf.
            db.enableWriteAheadLogging()
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val insertSql =
        "INSERT INTO packets (ts_ms, dev_us, channel, rssi, crc_ok, address, addr_type, " +
            "pdu_type, raw_hex, ad_offset, source, sources, rec_method, rec_hex, rec_bits, " +
            "rec_conf, rec_candidates) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

    /**
     * Writes a batch in one transaction and returns the packets with their
     * assigned row ids. Callers batch rather than inserting per packet because a
     * transaction per packet cannot keep up with a busy channel.
     */
    fun insertBatch(packets: List<BlePacket>): List<BlePacket> {
        if (packets.isEmpty()) return emptyList()

        val database = db
        val statement = database.compileStatement(insertSql)
        val stored = ArrayList<BlePacket>(packets.size)

        database.beginTransaction()
        try {
            for (packet in packets) {
                statement.clearBindings()
                statement.bindLong(1, packet.timestampMs)
                statement.bindLong(2, packet.deviceTimeUs)
                statement.bindLong(3, packet.channel.toLong())
                statement.bindLong(4, packet.rssi.toLong())
                statement.bindLong(5, if (packet.crcOk) 1 else 0)
                statement.bindString(6, packet.address)
                statement.bindString(7, packet.addressType)
                statement.bindString(8, packet.pduType)
                statement.bindString(9, packet.rawHex)
                statement.bindLong(10, packet.adOffset.toLong())
                statement.bindString(11, packet.source.id)
                statement.bindString(12, packet.sources.joinToString(",") { it.id })
                val recovery = packet.recovery
                if (recovery == null) {
                    statement.bindNull(13)
                    statement.bindNull(14)
                    statement.bindLong(15, 0)
                    statement.bindDouble(16, 0.0)
                    statement.bindLong(17, 0)
                } else {
                    statement.bindString(13, recovery.method.id)
                    statement.bindString(14, recovery.correctedHex)
                    statement.bindLong(15, recovery.bitsCorrected.toLong())
                    statement.bindDouble(16, recovery.confidence.toDouble())
                    statement.bindLong(17, recovery.candidatesEvaluated.toLong())
                }
                stored += packet.copy(id = statement.executeInsert())
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            statement.close()
        }

        trimIfNeeded(database)
        return stored
    }

    private fun trimIfNeeded(database: SQLiteDatabase) {
        val count = count()
        if (count <= maxRows) return

        // Drop a chunk at a time rather than one row per insert, so the delete
        // does not run on every batch.
        val excess = count - maxRows + 10_000
        database.execSQL(
            "DELETE FROM packets WHERE id IN (SELECT id FROM packets ORDER BY id ASC LIMIT ?)",
            arrayOf(excess.toString()),
        )
    }

    fun count(): Long =
        db.rawQuery("SELECT COUNT(*) FROM packets", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    /** Most recent packets first. */
    fun recent(limit: Int): List<BlePacket> =
        query("SELECT * FROM packets ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))

    /** Most recent packets for one advertiser, newest first. */
    fun forAddress(address: String, limit: Int): List<BlePacket> =
        query(
            "SELECT * FROM packets WHERE address = ? ORDER BY id DESC LIMIT ?",
            arrayOf(address, limit.toString()),
        )

    /**
     * Rebuilds the accumulation groups from storage. Used when the app restarts
     * on top of an interrupted capture; during a live capture the groups are
     * maintained incrementally instead.
     */
    fun loadGroups(): List<PacketGroup> {
        val totals = mutableListOf<Triple<String, IntArray, Long>>()
        val typesByAddress = mutableMapOf<String, Set<String>>()
        db.rawQuery(
            """
            SELECT address, COUNT(*) AS n,
                   SUM(CASE WHEN crc_ok = 0 THEN 1 ELSE 0 END) AS errs,
                   SUM(CASE WHEN crc_ok = 1
                             OR (rec_method IS NOT NULL AND rec_method <> 'unrecoverable')
                            THEN 1 ELSE 0 END) AS confirmed,
                   SUM(CASE WHEN crc_ok = 0
                             AND rec_method IS NOT NULL
                             AND rec_method <> 'unrecoverable'
                            THEN 1 ELSE 0 END) AS repaired,
                   MAX(id) AS last_id,
                   GROUP_CONCAT(DISTINCT pdu_type) AS types
            FROM packets GROUP BY address
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)
                totals += Triple(
                    address,
                    intArrayOf(
                        cursor.getInt(1),
                        cursor.getInt(2),
                        cursor.getInt(3),
                        cursor.getInt(4),
                    ),
                    cursor.getLong(5),
                )
                // GROUP_CONCAT always joins on a comma in its DISTINCT form, and
                // a PDU type never contains one - the host's compound labels use
                // ", " but arrive as a single value, so splitting on the bare
                // comma would tear them apart. Split on "," followed by a
                // non-space to keep them whole.
                typesByAddress[address] = cursor.getString(6).orEmpty()
                    .split(Regex(",(?! )"))
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        }
        if (totals.isEmpty()) return emptyList()

        val lastById = mutableMapOf<Long, BlePacket>()
        val ids = totals.joinToString(",") { it.third.toString() }
        for (packet in query("SELECT * FROM packets WHERE id IN ($ids)", null)) {
            lastById[packet.id] = packet
        }

        return totals.mapNotNull { (address, counts, lastId) ->
            val last = lastById[lastId] ?: return@mapNotNull null
            PacketGroup(
                address = address,
                addressType = last.addressType,
                count = counts[0],
                lastRssi = last.rssi,
                lastTimestampMs = last.timestampMs,
                lastPduType = last.pduType,
                crcErrors = counts[1],
                confirmedPackets = counts[2],
                repaired = counts[3],
                pduTypes = typesByAddress[address].orEmpty(),
                // Names are never taken from a packet that failed CRC; see the
                // note in SnifferViewModel.accumulate.
                name = if (last.crcOk) {
                    com.blesniffer.model.AdvertisingDecoder.extractName(last.rawHex, last.adOffset)
                } else {
                    null
                },
            )
        }.sortedByDescending { it.count }
    }

    /**
     * Timestamps, channels and CRC flags for one advertiser since [sinceMs].
     *
     * The timeline chart spans up to ten minutes, far more than the packet list
     * keeps in memory, and reading full rows for that range would be wasteful.
     * Selecting only the three columns the chart plots keeps a ten minute window
     * cheap even for a device that advertises continuously.
     */
    fun timelinePoints(address: String, sinceMs: Long): List<TimelinePoint> {
        val out = mutableListOf<TimelinePoint>()
        db.rawQuery(
            "SELECT ts_ms, channel, crc_ok FROM packets WHERE address = ? AND ts_ms >= ? " +
                "ORDER BY id ASC",
            arrayOf(address, sinceMs.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += TimelinePoint(
                    timestampMs = cursor.getLong(0),
                    channel = cursor.getInt(1),
                    crcOk = cursor.getInt(2) != 0,
                )
            }
        }
        return out
    }

    /**
     * Wall clock time of the most recent packet from one advertiser.
     *
     * Backs the chart's "last data" mode, which ends its window at the newest
     * packet rather than at the current time - for a device that has stopped
     * advertising, those are not the same place, and the ten second window
     * around "now" contains nothing to draw.
     */
    fun lastTimestampMs(address: String): Long? =
        db.rawQuery(
            "SELECT MAX(ts_ms) FROM packets WHERE address = ?",
            arrayOf(address),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    /**
     * Streams the whole capture oldest-first. Exports use this so a large
     * session never has to be materialised in memory.
     */
    fun forEachPacket(block: (BlePacket) -> Unit) {
        db.rawQuery("SELECT * FROM packets ORDER BY id ASC", null).use { cursor ->
            val columns = Columns(cursor)
            while (cursor.moveToNext()) {
                block(columns.read(cursor))
            }
        }
    }

    /**
     * Aggregates for the recovery statistics screen.
     *
     * Computed in SQL rather than by walking the packet list: the list holds a
     * recent window, while these numbers are about the whole session, and a
     * corrupt packet from an hour ago counts exactly as much as one from a
     * second ago.
     */
    fun recoveryStats(): RecoveryStats {
        fun rows(sql: String): List<Pair<String, Int>> =
            db.rawQuery(sql, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((cursor.getString(0) ?: "") to cursor.getInt(1))
                    }
                }
            }

        val byMethod = rows(
            "SELECT rec_method, COUNT(*) FROM packets WHERE rec_method IS NOT NULL " +
                "GROUP BY rec_method",
        )
        val byChannel = rows(
            "SELECT CAST(channel AS TEXT), COUNT(*) FROM packets WHERE crc_ok = 0 GROUP BY channel",
        )
        val repairedByChannel = rows(
            "SELECT CAST(channel AS TEXT), COUNT(*) FROM packets " +
                "WHERE rec_method IS NOT NULL AND rec_method <> 'unrecoverable' GROUP BY channel",
        )
        val totalByChannel = rows(
            "SELECT CAST(channel AS TEXT), COUNT(*) FROM packets GROUP BY channel",
        )
        val bySource = rows("SELECT source, COUNT(*) FROM packets GROUP BY source")
        val errorsBySource = rows(
            "SELECT source, COUNT(*) FROM packets WHERE crc_ok = 0 GROUP BY source",
        )
        val bitsHistogram = rows(
            "SELECT CAST(rec_bits AS TEXT), COUNT(*) FROM packets " +
                "WHERE rec_method IS NOT NULL AND rec_method <> 'unrecoverable' " +
                "GROUP BY rec_bits ORDER BY rec_bits",
        )

        val confidence = db.rawQuery(
            "SELECT AVG(rec_conf), MIN(rec_conf), MAX(rec_conf) FROM packets " +
                "WHERE rec_method IS NOT NULL AND rec_method <> 'unrecoverable'",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                Triple(cursor.getFloat(0), cursor.getFloat(1), cursor.getFloat(2))
            } else {
                Triple(0f, 0f, 0f)
            }
        }

        val corroborated = db.rawQuery(
            "SELECT COUNT(*) FROM packets WHERE sources LIKE '%,%'", null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val methodDetail = db.rawQuery(
            """
            SELECT rec_method, COUNT(*), AVG(rec_bits), AVG(rec_conf),
                   MIN(rec_bits), MAX(rec_bits), AVG(rec_candidates)
            FROM packets
            WHERE rec_method IS NOT NULL AND rec_method <> 'unrecoverable'
            GROUP BY rec_method
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList<MethodDetail> {
                while (cursor.moveToNext()) {
                    add(
                        MethodDetail(
                            method = cursor.getString(0) ?: "",
                            count = cursor.getInt(1),
                            averageBits = cursor.getFloat(2),
                            averageConfidence = cursor.getFloat(3),
                            minBits = cursor.getInt(4),
                            maxBits = cursor.getInt(5),
                            averageCandidates = cursor.getFloat(6),
                        ),
                    )
                }
            }
        }

        return RecoveryStats(
            methodDetail = methodDetail,
            total = count(),
            crcErrors = byChannel.sumOf { it.second },
            byMethod = byMethod.toMap(),
            errorsByChannel = byChannel.toMap(),
            repairedByChannel = repairedByChannel.toMap(),
            totalByChannel = totalByChannel.toMap(),
            bySource = bySource.toMap(),
            errorsBySource = errorsBySource.toMap(),
            bitsCorrected = bitsHistogram.toMap(),
            averageConfidence = confidence.first,
            minConfidence = confidence.second,
            maxConfidence = confidence.third,
            corroborated = corroborated,
        )
    }

    /**
     * Packets from any of [addresses], newest first.
     *
     * Backs the collapsed row of unidentifiable advertisers, which stands for
     * hundreds of one-off addresses at once. Chunked because SQLite caps how
     * many parameters one statement may carry.
     */
    fun packetsForAddresses(addresses: List<String>, limit: Int): List<BlePacket> {
        if (addresses.isEmpty()) return emptyList()

        val out = mutableListOf<BlePacket>()
        for (chunk in addresses.chunked(400)) {
            if (out.size >= limit) break
            val placeholders = chunk.joinToString(",") { "?" }
            out += query(
                "SELECT * FROM packets WHERE address IN ($placeholders) " +
                    "ORDER BY id DESC LIMIT ?",
                (chunk + (limit - out.size).toString()).toTypedArray(),
            )
        }
        return out.sortedByDescending { it.id }.take(limit)
    }

    fun clear() {
        db.execSQL("DELETE FROM packets")
        db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'packets'")
    }

    /** Wall clock time of the first packet still stored, or null when empty. */
    fun firstTimestampMs(): Long? =
        db.rawQuery("SELECT MIN(ts_ms) FROM packets", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun query(sql: String, args: Array<String>?): List<BlePacket> {
        val out = mutableListOf<BlePacket>()
        db.rawQuery(sql, args).use { cursor ->
            val columns = Columns(cursor)
            while (cursor.moveToNext()) {
                out += columns.read(cursor)
            }
        }
        return out
    }

    /** Resolves column indices once per query instead of once per row. */
    private class Columns(cursor: android.database.Cursor) {
        val id = cursor.getColumnIndexOrThrow("id")
        val tsMs = cursor.getColumnIndexOrThrow("ts_ms")
        val devUs = cursor.getColumnIndexOrThrow("dev_us")
        val channel = cursor.getColumnIndexOrThrow("channel")
        val rssi = cursor.getColumnIndexOrThrow("rssi")
        val crcOk = cursor.getColumnIndexOrThrow("crc_ok")
        val address = cursor.getColumnIndexOrThrow("address")
        val addrType = cursor.getColumnIndexOrThrow("addr_type")
        val pduType = cursor.getColumnIndexOrThrow("pdu_type")
        val rawHex = cursor.getColumnIndexOrThrow("raw_hex")
        val adOffset = cursor.getColumnIndexOrThrow("ad_offset")
        val source = cursor.getColumnIndexOrThrow("source")
        val sources = cursor.getColumnIndexOrThrow("sources")
        val recMethod = cursor.getColumnIndexOrThrow("rec_method")
        val recHex = cursor.getColumnIndexOrThrow("rec_hex")
        val recBits = cursor.getColumnIndexOrThrow("rec_bits")
        val recConf = cursor.getColumnIndexOrThrow("rec_conf")
        val recCandidates = cursor.getColumnIndexOrThrow("rec_candidates")

        fun read(cursor: android.database.Cursor) = BlePacket(
            id = cursor.getLong(id),
            timestampMs = cursor.getLong(tsMs),
            deviceTimeUs = cursor.getLong(devUs),
            channel = cursor.getInt(channel),
            rssi = cursor.getInt(rssi),
            crcOk = cursor.getInt(crcOk) != 0,
            address = cursor.getString(address),
            addressType = cursor.getString(addrType),
            pduType = cursor.getString(pduType),
            rawHex = cursor.getString(rawHex),
            adOffset = cursor.getInt(adOffset),
            source = SourceKind.fromId(cursor.getString(source)),
            sources = cursor.getString(sources).orEmpty()
                .split(',').filter { it.isNotBlank() }.map(SourceKind::fromId).toSet()
                .ifEmpty { setOf(SourceKind.fromId(cursor.getString(source))) },
            recovery = RecoveryMethod.fromId(
                if (cursor.isNull(recMethod)) null else cursor.getString(recMethod),
            )?.let { method ->
                RecoveryInfo(
                    method = method,
                    correctedHex = if (cursor.isNull(recHex)) "" else cursor.getString(recHex),
                    bitsCorrected = cursor.getInt(recBits),
                    confidence = cursor.getFloat(recConf),
                    candidatesEvaluated = cursor.getInt(recCandidates),
                    originalCrcOk = false,
                )
            },
        )
    }
}
