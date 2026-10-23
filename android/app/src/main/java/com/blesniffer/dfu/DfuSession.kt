package com.blesniffer.dfu

import android.util.Log
import com.blesniffer.source.UsbCdcConnection
import java.io.IOException

/** Progress of an update, for the UI. */
sealed interface DfuProgress {
    data class Step(val message: String) : DfuProgress
    data class Sending(val sentBytes: Int, val totalBytes: Int) : DfuProgress
    data object Done : DfuProgress
    /**
     * [boardInBootloader] distinguishes a failure before the board was touched
     * from one after it was already reset into DFU. Telling someone to replug a
     * board that was never reset sends them chasing a problem that is not there.
     */
    data class Failed(val message: String, val boardInBootloader: Boolean) : DfuProgress
}

/**
 * Drives one firmware update over the CDC port.
 *
 * Timings follow the reference tool rather than being guessed: the bootloader
 * erases flash synchronously after the start packet and blocks its CPU while
 * writing each page, so the waits below are the difference between a working
 * update and a corrupt one.
 */
class DfuSession(private val port: UsbCdcConnection) {

    private companion object {
        const val TAG = "BleDfu"

        const val FLASH_PAGE_SIZE = 4096

        /** Worst case page erase on an nRF52840, from the reference tool. */
        const val FLASH_PAGE_ERASE_MS = 90L

        /** Worst case word write, times the words in a page. */
        const val FLASH_PAGE_WRITE_MS = (FLASH_PAGE_SIZE / 4) * 100L / 1000L

        const val ACK_TIMEOUT_MS = 2_000
    }

    private var sequence = 0

    fun install(pkg: DfuPackage, onProgress: (DfuProgress) -> Unit) {
        try {
            onProgress(DfuProgress.Step("Starting update…"))
            sequence = 0

            send(DfuProtocol.startPacket(pkg.sizeBytes))

            // The bootloader erases the destination bank before it will accept
            // anything else, and it does so with the CPU blocked.
            val eraseMs = maxOf(500L, ((pkg.sizeBytes / FLASH_PAGE_SIZE) + 1) * FLASH_PAGE_ERASE_MS)
            onProgress(DfuProgress.Step("Erasing flash…"))
            Thread.sleep(eraseMs)

            onProgress(DfuProgress.Step("Sending image information…"))
            send(DfuProtocol.initPacket(pkg.initPacket))

            var sent = 0
            var chunkIndex = 0
            while (sent < pkg.firmware.size) {
                val end = minOf(sent + DfuProtocol.DFU_PACKET_MAX_SIZE, pkg.firmware.size)
                send(DfuProtocol.dataPacket(pkg.firmware.copyOfRange(sent, end)))
                sent = end
                chunkIndex++

                // Every eight chunks fills a flash page, and the bootloader
                // stalls while it writes one.
                if (chunkIndex % 8 == 0) {
                    Thread.sleep(FLASH_PAGE_WRITE_MS)
                }
                onProgress(DfuProgress.Sending(sent, pkg.firmware.size))
            }

            Thread.sleep(FLASH_PAGE_WRITE_MS)
            send(DfuProtocol.stopPacket())

            // The bootloader now copies the new image into place and resets. It
            // must not be interrupted, and it will not answer again.
            onProgress(DfuProgress.Step("Activating…"))
            Thread.sleep(eraseMs + ((pkg.sizeBytes / FLASH_PAGE_SIZE) + 1) * FLASH_PAGE_WRITE_MS)

            onProgress(DfuProgress.Done)
        } catch (e: Exception) {
            Log.e(TAG, "update failed", e)
            onProgress(DfuProgress.Failed(e.message ?: "The update failed.", boardInBootloader = true))
        }
    }

    private fun send(payload: ByteArray) {
        sequence = (sequence + 1) % 8
        val frame = DfuProtocol.packet(sequence, payload)

        port.write(frame, timeoutMs = 3_000)

        val ack = awaitAck()
            ?: throw IOException("The board stopped acknowledging at packet $sequence.")

        val expected = (sequence + 1) % 8
        if (ack != expected) {
            throw IOException("Board acknowledged $ack where $expected was expected.")
        }
    }

    /** Reads until a complete reply frame arrives, or the timeout expires. */
    private fun awaitAck(): Int? {
        val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
        val buffer = ByteArray(256)
        var collected = ByteArray(0)

        while (System.currentTimeMillis() < deadline) {
            val read = port.read(buffer, timeoutMs = 200)
            if (read > 0) {
                collected += buffer.copyOfRange(0, read)
                DfuProtocol.parseAck(collected)?.let { return it }
            }
        }
        return null
    }
}
