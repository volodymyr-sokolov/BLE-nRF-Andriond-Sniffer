package com.blesniffer.source

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.blesniffer.model.AdvertisingDecoder
import com.blesniffer.model.BlePacket
import com.blesniffer.model.ChannelSelection
import com.blesniffer.model.SourceKind
import com.blesniffer.model.SourceStatus
import com.blesniffer.model.hexToBytes
import com.blesniffer.model.toHex
import com.blesniffer.recover.Crc24
import com.blesniffer.recover.Whitening
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Reads packets from the nRF52840 over USB.
 *
 * The board speaks the line protocol documented in firmware/src/main.cpp. This
 * class owns the reader thread, translates `$P` lines into [BlePacket]s and
 * forwards channel and start/stop changes so the firmware stops transmitting
 * while the capture is paused rather than filling the pipe with packets that
 * would only be discarded.
 */
class UsbSnifferSource(
    context: Context,
    private val listener: PacketSource.Listener,
) : PacketSource {

    private companion object {
        const val DROP_WARNING_PREFIX = "Board dropped"

        /** Legacy advertising payload maximum, excluding the 2 byte header. */
        const val MAX_ADV_PAYLOAD = 37
    }

    /**
     * Asks the board for raw capture mode, or for the parsed mode it boots in.
     *
     * Remembered so the request can be repeated after a reconnect - the board
     * always comes up parsed, and a mode the user selected once should not
     * quietly lapse the next time the cable is replugged.
     */
    fun setRawMode(enabled: Boolean) {
        desiredRawMode = enabled
        send(if (enabled) "raw on" else "raw off")
    }

    /** What the board last told us it is doing: "raw", "parsed", or null. */
    @Volatile
    var reportedMode: String? = null
        private set

    private var desiredRawMode = false

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _status = MutableStateFlow(SourceStatus(detail = "Not connected"))
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override val supportsChannelSelection = true

    private var connection: UsbCdcConnection? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var scanning = false

    private var channel: ChannelSelection = ChannelSelection.ALL

    /**
     * The board reports a monotonic microsecond clock. Anchoring it to wall
     * clock time once per capture preserves the true spacing between packets,
     * which arrival time cannot do because USB delivers them in bursts.
     */
    private var anchorWallMs = 0L
    private var anchorDeviceUs = -1L
    private var lastDeviceUs = 0L

    /** Firmware banner and counters, surfaced in the status line. */
    @Volatile
    var firmwareVersion: String? = null
        private set

    fun findDevice(): UsbDevice? = UsbCdcConnection.findCandidate(usbManager)

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Stops the capture and resets the board into its bootloader, ready for a
     * firmware update. Returns false when there is nothing connected to reset.
     */
    fun rebootIntoBootloader(): Boolean {
        val cdc = connection ?: return false
        runCatching { cdc.write("stop\n".toByteArray(Charsets.US_ASCII)) }
        running = false
        scanning = false
        reader?.interrupt()
        reader = null

        return runCatching {
            cdc.touchIntoBootloader()
            cdc.close()
            connection = null
            anchorDeviceUs = -1L
            _status.value = SourceStatus(connected = false, detail = "In bootloader")
            true
        }.getOrElse {
            Log.e(UsbCdcConnection.TAG, "could not reset into the bootloader", it)
            false
        }
    }

    /** Opens a device for a firmware update, bypassing the packet reader. */
    fun openForUpdate(device: UsbDevice): UsbCdcConnection =
        UsbCdcConnection.open(usbManager, device)

    /**
     * Opens the board. Call only once permission has been granted; [findDevice]
     * and [hasPermission] let the caller arrange that first.
     */
    fun connect(device: UsbDevice) {
        disconnect()
        try {
            val cdc = UsbCdcConnection.open(usbManager, device)
            connection = cdc

            // The handshake goes out before the reader thread starts. A write
            // failure is then unambiguously the write's fault rather than
            // something to untangle from concurrent traffic on the same
            // connection, and the board's reply simply waits in its TX FIFO.
            cdc.write("ver\n".toByteArray(Charsets.US_ASCII))
            cdc.write("ch ${channel.command}\n".toByteArray(Charsets.US_ASCII))
            cdc.write(
                (if (desiredRawMode) "raw on\n" else "raw off\n").toByteArray(Charsets.US_ASCII),
            )

            running = true
            reader = thread(name = "usb-sniffer-reader", isDaemon = true) { readLoop(cdc) }

            _status.value = SourceStatus(
                connected = true,
                scanning = false,
                detail = device.productName ?: "nRF52840",
                error = cdc.controlWarning,
            )
        } catch (e: Exception) {
            Log.e(UsbCdcConnection.TAG, "connect failed", e)
            runCatching { connection?.close() }
            connection = null
            running = false
            _status.value = SourceStatus(
                connected = false,
                detail = "Not connected",
                error = e.message ?: "Could not open the USB device.",
            )
        }
    }

    fun disconnect() {
        running = false
        scanning = false
        reader?.interrupt()
        reader = null
        connection?.close()
        connection = null
        anchorDeviceUs = -1L
        _status.value = SourceStatus(connected = false, detail = "Not connected")
    }

    override fun start(channel: ChannelSelection) {
        this.channel = channel
        if (connection == null) {
            _status.value = _status.value.copy(error = "Connect the nRF52840 first.")
            return
        }
        anchorDeviceUs = -1L
        send("reset")
        send("ch ${channel.command}")
        send("start")
        scanning = true
        _status.value = _status.value.copy(scanning = true, error = null)
    }

    override fun stop() {
        if (connection == null) return
        send("stop")
        scanning = false
        _status.value = _status.value.copy(scanning = false)
    }

    override fun setChannel(channel: ChannelSelection) {
        this.channel = channel
        if (connection != null) send("ch ${channel.command}")
    }

    override fun close() = disconnect()

    private fun send(command: String) {
        val cdc = connection ?: return
        try {
            cdc.write((command + "\n").toByteArray(Charsets.US_ASCII))
            Log.i(UsbCdcConnection.TAG, "-> $command")
        } catch (e: IOException) {
            Log.e(UsbCdcConnection.TAG, "write '$command' failed", e)
            _status.value = _status.value.copy(error = "USB write failed: ${e.message}")
        }
    }

    private fun readLoop(cdc: UsbCdcConnection) {
        // bulkTransfer is documented to be unreliable above 16 KB on some
        // devices, so stay comfortably under that in one go.
        val buffer = ByteArray(8 * 1024)
        val line = StringBuilder(256)

        while (running) {
            val read = try {
                cdc.read(buffer, timeoutMs = 200)
            } catch (e: Exception) {
                if (running) {
                    _status.value = _status.value.copy(error = "USB read failed: ${e.message}")
                }
                break
            }

            if (read <= 0) continue

            for (i in 0 until read) {
                val c = buffer[i].toInt().toChar()
                if (c == '\n') {
                    if (line.isNotEmpty()) {
                        handleLine(line.toString())
                        line.setLength(0)
                    }
                } else if (c != '\r') {
                    if (line.length < 512) line.append(c) else line.setLength(0)
                }
            }
        }
    }

    /**
     * What the status strip says about the board.
     *
     * The board's own answer about which capture mode it is in, rather than the
     * mode that was asked for - the toggle already shows the request, and the
     * only thing worth a line on screen is whether the board agreed.
     */
    private fun modeDetail(): String = when (reportedMode) {
        "on" -> "raw on"
        "off" -> "raw off"
        else -> "connected"
    }

    private fun handleLine(line: String) {
        // Everything except the packet firehose is worth having in the log.
        if (!line.startsWith("\$P,")) Log.i(UsbCdcConnection.TAG, "<- $line")

        when {
            line.startsWith("\$P,") -> parsePacket(line)
            line.startsWith("\$R,") -> parseRawPacket(line)
            line.startsWith("\$I,") -> {
                val parts = line.split(',')
                if (parts.size >= 3) {
                    firmwareVersion = parts[2]
                    // Firmware 1.2.0 onwards reports which capture mode it is
                    // in. Older builds do not, and cannot do raw at all.
                    reportedMode = parts.getOrNull(5)
                    _status.value = _status.value.copy(
                        detail = modeDetail(),
                        firmware = parts[2],
                        error = null,
                    )
                }
            }
            line.startsWith("\$S,") -> parseStatus(line)
            line.startsWith("\$A,") -> {
                val parts = line.split(',')
                val command = parts.getOrNull(1).orEmpty()
                val ok = parts.getOrNull(2) == "ok"

                when {
                    command == "raw" && ok -> {
                        reportedMode = parts.getOrNull(3)
                        _status.value = _status.value.copy(
                            detail = modeDetail(),
                            error = null,
                        )
                    }
                    // An older firmware answers an unknown command rather than
                    // the raw one, which would otherwise look like raw mode
                    // silently doing nothing.
                    !ok && (command == "raw" || command == "?") && desiredRawMode -> {
                        _status.value = _status.value.copy(
                            error = "This firmware has no raw capture mode - update the " +
                                "board to 1.2.0 or later.",
                        )
                    }
                    !ok -> _status.value = _status.value.copy(
                        error = "Board rejected '$command': ${parts.getOrNull(3).orEmpty()}",
                    )
                }
            }
        }
    }

    private fun parseStatus(line: String) {
        // $S,<running>,<channel>,<received>,<crcErrors>,<dropped>,<queued>,<tUs>
        val parts = line.split(',')
        if (parts.size < 6) return
        val dropped = parts[5].toLongOrNull() ?: 0L
        val current = _status.value

        if (dropped > 0) {
            _status.value = current.copy(
                error = "$DROP_WARNING_PREFIX $dropped packets - the USB link cannot keep up.",
            )
        } else if (current.error?.startsWith(DROP_WARNING_PREFIX) == true) {
            // Counters were reset, so retire our own warning rather than leaving
            // a stale one covering an error the user still needs to see.
            _status.value = current.copy(error = null)
        }
    }

    /**
     * Parses a raw capture line: `$R,seq,tUs,channel,rssi,hexRaw`.
     *
     * The board sends 42 bytes exactly as they arrived - still whitened, CRC
     * included, length field untrusted. Everything the radio would normally do
     * happens here instead, which is the point: the received CRC survives, so
     * syndrome decoding has something to work with.
     */
    private fun parseRawPacket(line: String) {
        val parts = line.split(',')
        if (parts.size < 6) return

        val deviceUs = parts[2].toLongOrNull() ?: return
        val channel = parts[3].toIntOrNull() ?: return
        val rssi = parts[4].toIntOrNull() ?: return
        val whitened = parts[5].hexToBytes()
        if (whitened.size < 5) return

        val plain = Whitening.apply(whitened, channel)

        // The length field may itself be corrupt; clamp it and let the CRC be
        // the judge rather than trusting it.
        val payloadLength = (plain[1].toInt() and 0xFF).coerceAtMost(MAX_ADV_PAYLOAD)
        val bodyLength = 2 + payloadLength
        if (bodyLength + 3 > plain.size) return

        val crcOk = Crc24.compute(plain, 0, bodyLength) ==
            Crc24.fromBytes(plain, bodyLength)

        // Body plus its CRC: what layers 1 and 4 need.
        val frame = plain.copyOfRange(0, bodyLength + 3)
        val layout = AdvertisingDecoder.locate(frame)

        emit(
            deviceUs = deviceUs,
            channel = channel,
            rssi = rssi,
            crcOk = crcOk,
            rawHex = frame.toHex(),
            layout = layout,
            hasCrcField = true,
        )
    }

    private fun parsePacket(line: String) {
        // $P,<seq>,<tUs>,<channel>,<rssi>,<crc>,<hexPdu>
        val parts = line.split(',')
        if (parts.size < 7) return

        val deviceUs = parts[2].toLongOrNull() ?: return
        val channel = parts[3].toIntOrNull() ?: return
        val rssi = parts[4].toIntOrNull() ?: return
        val crcOk = parts[5] == "1"
        val rawHex = parts[6]

        val raw = rawHex.hexToBytes()
        emit(deviceUs, channel, rssi, crcOk, rawHex, AdvertisingDecoder.locate(raw), false)
    }

    /** Shared tail of both packet formats: anchor the clock and publish. */
    private fun emit(
        deviceUs: Long,
        channel: Int,
        rssi: Int,
        crcOk: Boolean,
        rawHex: String,
        layout: AdvertisingDecoder.PduLayout?,
        hasCrcField: Boolean,
    ) {
        if (anchorDeviceUs < 0 || deviceUs < lastDeviceUs) {
            anchorDeviceUs = deviceUs
            anchorWallMs = System.currentTimeMillis()
        }
        lastDeviceUs = deviceUs

        listener.onPacket(
            BlePacket(
                id = 0,
                timestampMs = anchorWallMs + (deviceUs - anchorDeviceUs) / 1000L,
                deviceTimeUs = deviceUs,
                channel = channel,
                rssi = rssi,
                crcOk = crcOk,
                address = layout?.address.orEmpty(),
                addressType = layout?.addressType.orEmpty(),
                pduType = layout?.pduType.orEmpty(),
                rawHex = rawHex,
                adOffset = layout?.adOffset ?: -1,
                source = SourceKind.USB,
                hasCrcField = hasCrcField,
            ),
        )
    }
}
