package com.blesniffer.dfu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DfuPackageTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(extra: String = "") = """
        {"manifest":{$extra"application":{"bin_file":"firmware.bin","dat_file":"firmware.dat"}}}
    """.trimIndent().toByteArray()

    @Test
    fun `reads an application package`() {
        val pkg = DfuPackage.read(
            ByteArrayInputStream(
                zipOf(
                    "manifest.json" to manifest(),
                    "firmware.bin" to ByteArray(1234) { it.toByte() },
                    "firmware.dat" to byteArrayOf(1, 2, 3, 4),
                ),
            ),
        )
        assertEquals(1234, pkg.sizeBytes)
        assertEquals(4, pkg.initPacket.size)
    }

    @Test
    fun `a package that would rewrite the bootloader is refused`() {
        // Installing one of these from the phone risks bricking the board with
        // no way back, so it is rejected rather than half-applied.
        val error = assertThrows(IllegalArgumentException::class.java) {
            DfuPackage.read(
                ByteArrayInputStream(
                    zipOf(
                        "manifest.json" to manifest("\"bootloader\":{},"),
                        "firmware.bin" to ByteArray(16),
                        "firmware.dat" to ByteArray(4),
                    ),
                ),
            )
        }
        assertTrue(error.message!!.contains("bootloader"))
    }

    @Test
    fun `a softdevice package is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DfuPackage.read(
                ByteArrayInputStream(
                    zipOf(
                        "manifest.json" to manifest("\"softdevice\":{},"),
                        "firmware.bin" to ByteArray(16),
                        "firmware.dat" to ByteArray(4),
                    ),
                ),
            )
        }
    }

    @Test
    fun `something that is not a DFU package is refused`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DfuPackage.read(ByteArrayInputStream(zipOf("readme.txt" to "hello".toByteArray())))
        }
        assertTrue(error.message!!.contains("manifest"))
    }

    @Test
    fun `a manifest naming a file the archive lacks is refused`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DfuPackage.read(
                ByteArrayInputStream(
                    zipOf(
                        "manifest.json" to manifest(),
                        "firmware.dat" to ByteArray(4),
                    ),
                ),
            )
        }
        assertTrue(error.message!!.contains("firmware.bin"))
    }
}
