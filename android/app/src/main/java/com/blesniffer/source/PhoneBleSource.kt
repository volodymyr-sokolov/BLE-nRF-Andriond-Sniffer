package com.blesniffer.source

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.blesniffer.model.BlePacket
import com.blesniffer.model.ChannelSelection
import com.blesniffer.model.SourceKind
import com.blesniffer.model.SourceStatus
import com.blesniffer.model.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scans with the phone's own Bluetooth radio.
 *
 * Android hands back a parsed [ScanResult] rather than a link layer PDU, so two
 * things the nRF52840 provides are simply not available here: the advertising
 * channel a packet arrived on, and packets that failed CRC (the controller drops
 * those before the application ever sees them). Channel is reported as unknown
 * and every packet is marked CRC-OK, which is the truth as far as this source
 * can tell.
 */
class PhoneBleSource(
    context: Context,
    private val listener: PacketSource.Listener,
) : PacketSource {

    private val appContext = context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _status = MutableStateFlow(SourceStatus(detail = "Phone radio"))
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)

    /**
     * Whether the radio is on, as a stream rather than as a question to ask.
     *
     * Asking was the bug. The banner offering to turn Bluetooth on only
     * re-checked when an activity result came back, so turning the radio on from
     * the quick settings shade - which produces no result at all - left the
     * banner sitting there insisting Bluetooth was off. Worse, the in-app path
     * had the same fault in miniature: `ACTION_REQUEST_ENABLE` returns as soon
     * as the user agrees, while the adapter is still in `TURNING_ON`, so the
     * check ran a moment too early and got the old answer even when everything
     * had gone right.
     *
     * The adapter broadcasts its state changes. Following those is the only way
     * to be right regardless of who turned the radio on or how.
     */
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val stateWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                // TURNING_ON is deliberately not "on". The stack cannot scan
                // yet, and treating it as ready means the banner disappears a
                // beat before Start would work.
                BluetoothAdapter.STATE_ON -> {
                    _bluetoothEnabled.value = true
                    // Clear whatever was complained about while the radio was
                    // off. Nothing else ever would, so pressing Start too early
                    // once left "Bluetooth is turned off" in the status strip
                    // for the rest of the session.
                    _status.value = _status.value.copy(error = null)
                }

                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF,
                -> {
                    _bluetoothEnabled.value = false
                    // The scan went with the radio. Without this the button
                    // still reads Stop and the strip still claims to be
                    // scanning, and the only way back is to press Stop on a
                    // scan that stopped by itself.
                    if (scanning) {
                        scanning = false
                        _status.value = _status.value.copy(
                            connected = false,
                            scanning = false,
                            error = "Bluetooth was turned off.",
                        )
                    }
                }
            }
        }
    }

    /** A single radio scanning all three channels; there is nothing to select. */
    override val supportsChannelSelection = false

    /** Converts SystemClock.elapsedRealtime based scan stamps to wall clock. */
    private val bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private var scanning = false

    // Registered here, below everything the receiver touches, rather than
    // beside the receiver itself. Broadcasts are delivered on the main thread
    // and so cannot arrive mid-construction today, but a listener that reads
    // fields declared after the line that starts it is a hazard sitting there
    // waiting for someone to move a declaration.
    init {
        ContextCompat.registerReceiver(
            appContext,
            stateWatcher,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emit(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::emit)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _status.value = _status.value.copy(scanning = false, error = describeFailure(errorCode))
        }
    }

    fun isSupported(): Boolean = adapter != null

    fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return granted(permission)
    }

    /**
     * Whether the app may ask the system to turn Bluetooth on.
     *
     * From API 31 `ACTION_REQUEST_ENABLE` is guarded by BLUETOOTH_CONNECT, and
     * the guard fails silently: the system activity finishes with RESULT_CANCELED
     * the moment it starts, so the button appears to do nothing at all. Nothing
     * about turning the radio on suggests a *connect* permission is involved,
     * which is why this is asked about by name rather than left to the scan
     * permission check next to it.
     */
    fun canRequestEnable(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** The permission the caller should request before starting. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // CONNECT is asked for alongside SCAN rather than only when the
            // radio turns out to be off: both are in the same system dialog, and
            // splitting them means a second prompt at the worst moment.
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun start(channel: ChannelSelection) {
        if (scanning) return

        val adapter = this.adapter
        if (adapter == null) {
            _status.value = _status.value.copy(error = "This device has no Bluetooth adapter.")
            return
        }
        if (!adapter.isEnabled) {
            _status.value = _status.value.copy(error = "Bluetooth is turned off.")
            return
        }
        if (!hasScanPermission()) {
            _status.value = _status.value.copy(error = "Bluetooth scan permission was not granted.")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _status.value = _status.value.copy(error = "Bluetooth scanner is unavailable.")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Report every advertisement rather than one per device, and
                    // include extended advertising where the radio supports it.
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()

        try {
            // An empty filter list still reports everything, but passing one
            // explicit match-all filter keeps some vendor stacks from throttling.
            scanner.startScan(listOf(ScanFilter.Builder().build()), settings, callback)
            scanning = true
            _status.value = SourceStatus(connected = true, scanning = true, detail = "Phone radio")
        } catch (e: Exception) {
            _status.value = _status.value.copy(error = "Could not start scanning: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _status.value = _status.value.copy(scanning = false)
    }

    /** No-op: the platform scan API gives no control over channels. */
    override fun setChannel(channel: ChannelSelection) = Unit

    override fun close() {
        stop()
        runCatching { appContext.unregisterReceiver(stateWatcher) }
    }

    @SuppressLint("MissingPermission")
    private fun emit(result: ScanResult) {
        val record = result.scanRecord
        val rawHex = record?.bytes?.toHex().orEmpty()

        // Android already stripped the PDU header, so AD structures start at 0.
        val adOffset = if (rawHex.isEmpty()) -1 else 0

        val timestampMs = bootWallMs + (result.timestampNanos / 1_000_000L)

        listener.onPacket(
            BlePacket(
                id = 0,
                timestampMs = timestampMs,
                deviceTimeUs = result.timestampNanos / 1000L,
                channel = BlePacket.CHANNEL_UNKNOWN,
                rssi = result.rssi,
                crcOk = true,
                address = result.device.address.orEmpty(),
                addressType = addressTypeOf(result),
                pduType = pduTypeOf(result),
                rawHex = rawHex,
                adOffset = adOffset,
                source = SourceKind.PHONE,
            ),
        )
    }

    private fun addressTypeOf(result: ScanResult): String {
        // A locally administered first octet marks a resolvable or random
        // address; the platform only exposes the type directly on newer APIs.
        val address = result.device.address ?: return ""
        val firstOctet = address.substringBefore(':').toIntOrNull(16) ?: return ""
        return if (firstOctet and 0x02 != 0) "random" else "public"
    }

    private fun pduTypeOf(result: ScanResult): String = buildString {
        append(if (result.isLegacy) "legacy" else "extended")
        if (result.isConnectable) append(", connectable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            result.dataStatus == ScanResult.DATA_TRUNCATED
        ) {
            append(", truncated")
        }
    }

    private fun describeFailure(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A scan is already running."
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "The system refused to register the scan."
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth stack error."
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
            "This radio does not support the requested scan mode."
        else -> "Scan failed (code $errorCode)."
    }
}
