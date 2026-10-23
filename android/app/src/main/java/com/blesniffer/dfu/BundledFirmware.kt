package com.blesniffer.dfu

import android.content.Context
import android.util.Log

/**
 * The firmware image shipped inside the app.
 *
 * Packaged by the `bundleFirmware` Gradle task from whatever PlatformIO last
 * built, so the board can be updated with nothing to download and no file to go
 * looking for. Absent on a build made without the firmware artefact present, in
 * which case the app simply does not offer the option.
 */
object BundledFirmware {

    private const val TAG = "BleDfu"
    private const val PACKAGE_ASSET = "firmware/firmware.zip"
    private const val VERSION_ASSET = "firmware/version.txt"

    /** Firmware version built into this app, or null when none was bundled. */
    fun version(context: Context): String? = runCatching {
        context.assets.open(VERSION_ASSET).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun isAvailable(context: Context): Boolean = runCatching {
        context.assets.open(PACKAGE_ASSET).close()
        true
    }.getOrDefault(false)

    /** Reads the bundled package. Throws if this build has none. */
    fun read(context: Context): DfuPackage =
        context.assets.open(PACKAGE_ASSET).use { DfuPackage.read(it) }
            .also { Log.i(TAG, "bundled firmware ${version(context)}, ${it.sizeBytes} bytes") }
}
