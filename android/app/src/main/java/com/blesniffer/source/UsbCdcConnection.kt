package com.blesniffer.source

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException
import kotlin.concurrent.withLock

/**
 * Minimal USB CDC-ACM host implementation.
 *
 * The nRF52840 presents itself as a standard communications device, so rather
 * than pulling in a general purpose serial library this speaks the two control
 * requests that matter (line coding and control line state) and then moves bytes
 * over the data interface's bulk endpoints.
 *
 * Asserting DTR through SET_CONTROL_LINE_STATE is not optional: the board's USB
 * stack treats the port as closed until the host raises it, and would silently
 * discard everything we wrote.
 *
 * Everything interesting is logged under [TAG]. Debugging a USB link on a phone
 * is otherwise close to impossible - the failure modes all surface as the same
 * unhelpful -1 from bulkTransfer.
 */
class UsbCdcConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val claimedInterfaces: List<UsbInterface>,
    private val readEndpoint: UsbEndpoint,
    private val writeEndpoint: UsbEndpoint,
    // The class requests below are addressed to the CDC control interface. It is
    // interface 0 on most boards, but not on a composite device that puts
    // storage or HID first, so the real number is looked up rather than assumed.
    private val controlInterfaceId: Int,
    val device: UsbDevice,
) {

    companion object {
        const val TAG = "BleUsb"

        private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val CONTROL_LINE_DTR = 0x01
        private const val CONTROL_LINE_RTS = 0x02

        private const val CONTROL_TIMEOUT_MS = 2000

        /** Adafruit's vendor id, which the Feather nRF52840 boards use. */
        const val VENDOR_ADAFRUIT = 0x239A

        /** Nordic's own vendor id, for boards flashed with the same firmware. */
        const val VENDOR_NORDIC = 0x1915

        /**
         * Picks the device most likely to be our sniffer: a known vendor first,
         * otherwise any device exposing a CDC communications interface.
         */
        fun findCandidate(manager: UsbManager): UsbDevice? {
            val devices = manager.deviceList.values
            return devices.firstOrNull { it.vendorId == VENDOR_ADAFRUIT || it.vendorId == VENDOR_NORDIC }
                ?: devices.firstOrNull { device ->
                    (0 until device.interfaceCount).any {
                        device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM
                    }
                }
        }

        /** Human readable descriptor dump, for the log and the status line. */
        fun describe(device: UsbDevice): String = buildString {
            append("VID=0x%04X PID=0x%04X".format(device.vendorId, device.productId))
            append(" name=${device.deviceName}")
            append(" product=${device.productName ?: "?"}")
            append(" class=${device.deviceClass}/${device.deviceSubclass}")
            append(" interfaces=${device.interfaceCount}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                append("\n  if#${iface.id} alt=${iface.alternateSetting}")
                append(" class=0x%02X sub=0x%02X proto=0x%02X".format(
                    iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol,
                ))
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
                        else -> "ctrl"
                    }
                    append("\n    ep addr=0x%02X %s %s max=%d".format(
                        ep.address, dir, type, ep.maxPacketSize,
                    ))
                }
            }
        }

        /**
         * Opens [device] and configures it for 8N1 at [baudRate]. The rate is
         * cosmetic on a native USB port but some stacks reject a missing line
         * coding, so it is always sent.
         */
        @Throws(IOException::class)
        fun open(manager: UsbManager, device: UsbDevice, baudRate: Int = 115200): UsbCdcConnection {
            Log.i(TAG, "opening:\n${describe(device)}")

            val connection = manager.openDevice(device)
                ?: throw IOException("Could not open ${device.deviceName}. Permission may have been revoked.")

            try {
                val dataInterface = findDataInterface(device)
                    ?: throw IOException("No CDC data interface on ${device.productName ?: device.deviceName}.")

                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (i in 0 until dataInterface.endpointCount) {
                    val endpoint = dataInterface.getEndpoint(i)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
                }
                if (input == null || output == null) {
                    throw IOException("CDC data interface is missing a bulk endpoint pair.")
                }

                // The control interface has to be claimed too, otherwise the
                // class requests below are rejected on some Android builds.
                var controlInterface: UsbInterface? = null
                val claimed = mutableListOf<UsbInterface>()
                for (i in 0 until device.interfaceCount) {
                    val candidate = device.getInterface(i)
                    val isControl = candidate.interfaceClass == UsbConstants.USB_CLASS_COMM
                    if (isControl && controlInterface == null) controlInterface = candidate
                    if (candidate.id == dataInterface.id || isControl) {
                        val ok = connection.claimInterface(candidate, true)
                        Log.i(TAG, "claimInterface(if#${candidate.id}) = $ok")
                        if (ok) claimed += candidate
                    }
                }
                if (claimed.none { it.id == dataInterface.id }) {
                    throw IOException("Could not claim the CDC data interface; another app may hold it.")
                }

                val cdc = UsbCdcConnection(
                    connection = connection,
                    claimedInterfaces = claimed,
                    readEndpoint = input,
                    writeEndpoint = output,
                    controlInterfaceId = (controlInterface ?: dataInterface).id,
                    device = device,
                )

                Log.i(
                    TAG,
                    "endpoints in=0x%02X out=0x%02X control=if#%d".format(
                        input.address, output.address, cdc.controlInterfaceId,
                    ),
                )

                cdc.setLineCoding(baudRate)
                cdc.setControlLineState(dtr = true, rts = true)
                return cdc
            } catch (e: Throwable) {
                Log.e(TAG, "open failed", e)
                connection.close()
                throw e
            }
        }

        private fun findDataInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return candidate
            }
            // Some composite devices mislabel the data interface; fall back to
            // any interface that owns a pair of bulk endpoints.
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (e in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(e)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
                }
                if (hasIn && hasOut) return candidate
            }
            return null
        }
    }

    @Volatile
    private var closed = false

    /**
     * Serialises bulk transfers on this connection.
     *
     * The reader thread sits in a blocking IN transfer more or less
     * continuously, and commands are written from whichever thread the UI hands
     * them to. Concurrent bulkTransfer calls on one UsbDeviceConnection are
     * accepted by some Android USB stacks and not others, and the failure looks
     * identical to every other USB problem: a bare -1. Taking turns costs at
     * most one read timeout of latency on a command, which is nothing next to
     * how rarely commands are sent. The lock is fair so a busy channel cannot
     * starve the writer.
     */
    private val transferLock = java.util.concurrent.locks.ReentrantLock(true)

    /**
     * Set when a control request was rejected. The link often still works, so
     * this is reported rather than thrown, but it is the first thing to look at
     * when the board never answers.
     */
    var controlWarning: String? = null
        private set

    private fun setLineCoding(baudRate: Int) {
        val payload = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            ((baudRate shr 8) and 0xFF).toByte(),
            ((baudRate shr 16) and 0xFF).toByte(),
            ((baudRate shr 24) and 0xFF).toByte(),
            0, // 1 stop bit
            0, // no parity
            8, // 8 data bits
        )
        val result = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            SET_LINE_CODING,
            0,
            controlInterfaceId,
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS,
        )
        Log.i(TAG, "SET_LINE_CODING($baudRate) = $result")
        if (result < 0) controlWarning = "SET_LINE_CODING was rejected"
    }

    private fun setControlLineState(dtr: Boolean, rts: Boolean) {
        var value = 0
        if (dtr) value = value or CONTROL_LINE_DTR
        if (rts) value = value or CONTROL_LINE_RTS
        val result = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            SET_CONTROL_LINE_STATE,
            value,
            controlInterfaceId,
            null,
            0,
            CONTROL_TIMEOUT_MS,
        )
        Log.i(TAG, "SET_CONTROL_LINE_STATE(dtr=$dtr, rts=$rts) = $result")
        if (result < 0) {
            controlWarning = "SET_CONTROL_LINE_STATE was rejected - the board will not see the port as open"
        }
    }

    /**
     * Reads whatever is available. Returns 0 when the timeout expires with no
     * data, which is the normal idle case rather than an error.
     */
    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) return -1
        val read = transferLock.withLock {
            connection.bulkTransfer(readEndpoint, buffer, buffer.size, timeoutMs)
        }
        return if (read < 0) 0 else read
    }

    /**
     * Writes a command. A single failed transfer is retried once with a longer
     * timeout before giving up: a board that has just enumerated can NAK the
     * first OUT transfer while its stack finishes coming up.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int = 2000) {
        if (closed) throw IOException("Port is closed.")

        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(writeEndpoint.maxPacketSize.coerceAtLeast(1), data.size - offset)
            val slice = data.copyOfRange(offset, offset + chunk)

            var written = transferLock.withLock {
                connection.bulkTransfer(writeEndpoint, slice, slice.size, timeoutMs)
            }
            if (written < 0) {
                Log.w(TAG, "bulk OUT failed (${slice.size} bytes), retrying")
                Thread.sleep(50)
                written = transferLock.withLock {
                    connection.bulkTransfer(writeEndpoint, slice, slice.size, timeoutMs * 2)
                }
            }

            if (written < 0) {
                throw IOException(
                    "bulk OUT to endpoint 0x%02X returned -1 after %d of %d bytes".format(
                        writeEndpoint.address, offset, data.size,
                    ),
                )
            }
            if (written == 0) {
                throw IOException("bulk OUT accepted no bytes; the board is not reading the port.")
            }
            offset += written
        }
    }

    /**
     * Resets the board into its bootloader.
     *
     * The Adafruit core watches for the host opening the port at 1200 baud and
     * then dropping DTR - the same "touch" the Arduino IDE and nrfutil use - and
     * reboots into DFU when it sees it. The port dies underneath us immediately
     * afterwards, so the caller must close and wait for the device to enumerate
     * again under its bootloader product id.
     */
    fun touchIntoBootloader() {
        setLineCoding(1200)
        setControlLineState(dtr = true, rts = false)
        Thread.sleep(100)
        setControlLineState(dtr = false, rts = false)
        Log.i(TAG, "1200 bps touch sent; board should re-enumerate in DFU mode")
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { setControlLineState(dtr = false, rts = false) }
        claimedInterfaces.forEach { runCatching { connection.releaseInterface(it) } }
        runCatching { connection.close() }
        Log.i(TAG, "closed")
    }
}
