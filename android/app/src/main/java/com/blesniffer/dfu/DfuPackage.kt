package com.blesniffer.dfu

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The contents of a `firmware.zip` produced by PlatformIO or `adafruit-nrfutil
 * pkg generate`: an application image plus the init packet describing it.
 */
data class DfuPackage(
    val firmware: ByteArray,
    val initPacket: ByteArray,
    val name: String,
) {
    val sizeBytes: Int get() = firmware.size

    companion object {

        /**
         * Reads a DFU package.
         *
         * Only application updates are accepted. A package containing a
         * SoftDevice or bootloader image is rejected rather than half-applied:
         * those rewrite the part of flash that makes recovery possible, and this
         * app has no way to put it back.
         */
        fun read(input: InputStream): DfuPackage {
            var manifest: String? = null
            val files = mutableMapOf<String, ByteArray>()

            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val bytes = zip.readAllBytesCompat()
                    if (entry.name.endsWith("manifest.json")) {
                        manifest = bytes.toString(Charsets.UTF_8)
                    } else {
                        files[entry.name.substringAfterLast('/')] = bytes
                    }
                }
            }

            val manifestText = manifest
                ?: throw IllegalArgumentException("No manifest.json - this is not a DFU package.")

            val root = JSONObject(manifestText).optJSONObject("manifest")
                ?: throw IllegalArgumentException("manifest.json has no \"manifest\" object.")

            for (unsupported in listOf("softdevice", "bootloader", "softdevice_bootloader")) {
                if (root.has(unsupported)) {
                    throw IllegalArgumentException(
                        "This package updates the $unsupported. Only application updates can be " +
                            "installed from the phone - use a PC for that one.",
                    )
                }
            }

            val application = root.optJSONObject("application")
                ?: throw IllegalArgumentException("This package contains no application image.")

            val binName = application.optString("bin_file")
            val datName = application.optString("dat_file")

            val firmware = files[binName.substringAfterLast('/')]
                ?: throw IllegalArgumentException("Package is missing $binName.")
            val initPacket = files[datName.substringAfterLast('/')]
                ?: throw IllegalArgumentException("Package is missing $datName.")

            return DfuPackage(firmware, initPacket, binName)
        }

        private fun ZipInputStream.readAllBytesCompat(): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DfuPackage && name == other.name && firmware.contentEquals(other.firmware)

    override fun hashCode(): Int = name.hashCode() * 31 + firmware.contentHashCode()
}
