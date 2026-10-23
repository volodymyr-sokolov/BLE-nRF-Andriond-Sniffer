package com.blesniffer

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blesniffer.model.ExportFormat
import com.blesniffer.model.CaptureSource
import com.blesniffer.export.SessionFiles
import com.blesniffer.ui.AboutRecoveryScreen
import com.blesniffer.ui.ActionRequiredBanner
import com.blesniffer.ui.FirmwareUpdateDialog
import com.blesniffer.ui.SnifferActions
import com.blesniffer.ui.RecoveryStatsScreen
import com.blesniffer.ui.SnifferScreen
import com.blesniffer.model.TimelinePoint
import com.blesniffer.source.PhoneBleSource
import com.blesniffer.ui.ViewerScreen
import com.blesniffer.ui.theme.BleSnifferTheme

private const val ACTION_USB_PERMISSION = "com.blesniffer.USB_PERMISSION"

/**
 * Where to send the user to get Bluetooth turned on.
 *
 * `ACTION_REQUEST_ENABLE` is the good path - one dialog, straight back to the
 * app - but from API 31 it is guarded by BLUETOOTH_CONNECT and, when that is
 * missing, it does not fail: it finishes instantly with RESULT_CANCELED and the
 * user sees nothing. Once the permission has been refused outright there is no
 * way to raise that dialog at all, so the settings page is the fallback. It is
 * a worse experience than the prompt and a far better one than a dead button.
 */
private fun bluetoothEnableIntent(phone: PhoneBleSource): Intent =
    if (phone.canRequestEnable()) {
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    } else {
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }

class MainActivity : ComponentActivity() {

    private var pendingExportFormat: ExportFormat = ExportFormat.JSON

    /** Chart data held between opening the save picker and the result coming back. */
    private var pendingChart: Pair<List<TimelinePoint>, String>? = null

    private fun chartFileName(address: String, scaleLabel: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val device = address.replace(":", "").ifEmpty { "capture" }
        return "ble-timeline-$device-${scaleLabel.replace(" ", "")}-$stamp.png"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the system bars deliberately, with the bar icon contrast
        // set from the theme, rather than letting the platform default decide.
        enableEdgeToEdge()

        setContent {
            BleSnifferTheme {
                Surface(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App()
                }
            }
        }
    }

    @Composable
    private fun App() {
        val viewModel: SnifferViewModel = viewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val groupDetail by viewModel.groupDetail.collectAsStateWithLifecycle()
        val viewer by viewModel.viewer.collectAsStateWithLifecycle()
        val chartPoints by viewModel.chartPoints.collectAsStateWithLifecycle()

        // Bumped whenever USB attach/detach or a permission result should cause
        // the banner to re-evaluate which device is available.
        var usbGeneration by remember { mutableIntStateOf(0) }
        var bluetoothGeneration by remember { mutableIntStateOf(0) }

        val exportLauncher = rememberExportLauncher(viewModel)
        val openLauncher = rememberOpenLauncher(viewModel)

        val enableBluetoothLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { bluetoothGeneration++ }

        // Set while a permission is being requested for the sole purpose of
        // turning Bluetooth on, so the result can carry straight on to the
        // system prompt instead of returning the user to a banner they have
        // already pressed once.
        var enableAfterPermission by remember { mutableStateOf(false) }

        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            bluetoothGeneration++
            if (enableAfterPermission) {
                enableAfterPermission = false
                enableBluetoothLauncher.launch(bluetoothEnableIntent(viewModel.phone()))
            }
        }

        // Turning the radio on: straight to the system prompt when we are
        // allowed to raise it, and otherwise through the permission it needs.
        // Pressing the banner must always visibly do something - the bug this
        // replaces was a button that silently did nothing at all.
        val onEnableBluetooth = {
            if (viewModel.phone().canRequestEnable()) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                enableAfterPermission = true
                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            }
        }

        RegisterUsbReceiver(
            onPermissionResult = { device, granted ->
                if (granted && device != null) {
                    // A firmware update in progress owns the device; handing it
                    // to the packet reader instead would break the transfer.
                    if (viewModel.dfu.value != null) {
                        viewModel.onUsbPermissionGranted(device)
                    } else {
                        viewModel.connectUsb(device)
                    }
                }
                usbGeneration++
            },
            onAttachedOrDetached = { usbGeneration++ },
        )

        val chartLauncher = rememberChartLauncher(viewModel)

        val firmwareLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                viewModel.updateFirmware(uri) { device -> requestUsbPermission(device) }
            }
        }

        val bundledVersion = remember { viewModel.bundledFirmwareVersion() }
        var showRecoveryHelp by remember { mutableStateOf(false) }

        val dfu by viewModel.dfu.collectAsStateWithLifecycle()
        dfu?.let { FirmwareUpdateDialog(progress = it, onDismiss = viewModel::dismissDfu) }

        val actions = SnifferActions(
            onSource = viewModel::setSource,
            onChannel = viewModel::setChannel,
            onToggleMode = viewModel::toggleMode,
            onAddressFilter = viewModel::setAddressFilter,
            onPduTypeFilter = viewModel::setPduTypeFilter,
            onRawCapture = viewModel::setRawCapture,
            onToggleScan = viewModel::toggleScanning,
            onExport = { format ->
                pendingExportFormat = format
                exportLauncher.launch(SessionFiles.suggestFileName(format))
            },
            onOpenFile = { openLauncher.launch(arrayOf("*/*")) },
            onUpdateFirmware = { firmwareLauncher.launch(arrayOf("application/zip", "*/*")) },
            onFlashBundledFirmware = bundledVersion?.let {
                { viewModel.updateFirmwareFromBundle { device -> requestUsbPermission(device) } }
            },
            onAboutRecovery = { showRecoveryHelp = true },
            onShowStats = viewModel::openStats,
            onClear = viewModel::clearHistory,
            onOpenGroup = viewModel::openGroup,
            onCloseGroup = viewModel::closeGroup,
            onDismissMessage = viewModel::dismissMessage,
            onChartVisible = viewModel::setChartVisible,
            onChartScale = viewModel::setChartScale,
            onChartAtLastData = viewModel::setChartAtLastData,
            onSaveChart = { points, address ->
                pendingChart = points to address
                chartLauncher.launch(chartFileName(address, state.chartScale.label))
            },
        )

        // Back unwinds to the main list instead of leaving the app. It is only
        // enabled while there is somewhere to go: from the list itself, back
        // still behaves the way every other app does.
        BackHandler(enabled = viewer != null) { viewModel.closeViewer() }
        BackHandler(enabled = viewer == null && groupDetail != null) { viewModel.closeGroup() }
        BackHandler(
            enabled = viewer == null && groupDetail == null && state.filtered,
        ) {
            viewModel.setAddressFilter("")
            viewModel.setPduTypeFilter("")
        }

        val stats by viewModel.stats.collectAsStateWithLifecycle()
        val currentViewer = viewer

        // Every screen that covers the main one is a branch of this, rather
        // than an early `return` partway down the function.
        //
        // The returns were not merely untidy. Leaving the composition removes
        // the main screen from the tree, and with it everything it remembers -
        // so opening the statistics and coming back put the packet list at the
        // top again, having thrown away whatever the user had scrolled to and
        // whichever packet they had open. The holder below is what fixes that:
        // it keeps the saveable state of a screen that is not currently
        // composed, which is precisely what a navigation library does and what
        // `rememberSaveable` alone cannot, since its store dies with the node.
        val screens = rememberSaveableStateHolder()

        when {
            stats != null -> {
                BackHandler { viewModel.closeStats() }
                RecoveryStatsScreen(stats = stats!!, onClose = viewModel::closeStats)
            }

            showRecoveryHelp -> {
                BackHandler { showRecoveryHelp = false }
                AboutRecoveryScreen(
                    bundledFirmwareVersion = bundledVersion,
                    connectedFirmwareVersion = state.status.firmware,
                    onClose = { showRecoveryHelp = false },
                )
            }

            currentViewer != null -> ViewerScreen(
                viewer = currentViewer,
                onMode = viewModel::setViewerMode,
                onClose = viewModel::closeViewer,
            )

            else -> screens.SaveableStateProvider("sniffer") {
                SnifferScreen(
                    state = state,
                    groupDetail = groupDetail,
                    chartPoints = chartPoints,
                    channelSelectable = state.source.usesUsb,
                    actions = actions,
                    banner = {
                        SourceBanner(
                            viewModel = viewModel,
                            source = state.source,
                            usbConnected = state.status.connected,
                            usbGeneration = usbGeneration,
                            bluetoothGeneration = bluetoothGeneration,
                            onRequestPermission = { permissionLauncher.launch(it) },
                            onEnableBluetooth = onEnableBluetooth,
                            onRequestUsb = { device -> requestUsbPermission(device) },
                            onConnectUsb = viewModel::connectUsb,
                            onRescan = { usbGeneration++ },
                        )
                    },
                )
            }
        }
    }

    /**
     * Tells the user what is standing between them and a capture, and offers the
     * single action that fixes it.
     *
     * [usbGeneration] and [bluetoothGeneration] are not read directly. They are
     * counters bumped by hotplug and permission callbacks, and their only job is
     * to be parameters that change so Compose re-runs the device and permission
     * checks below - neither of which is observable state on its own.
     *
     * Whether Bluetooth is on is *not* one of those. It changes without the app
     * being involved at all - from the quick settings shade, or a moment after
     * the enable dialog has already returned - so it is collected from the
     * adapter's own broadcasts rather than re-asked when something else happens
     * to prompt a recomposition.
     */
    @Composable
    private fun SourceBanner(
        viewModel: SnifferViewModel,
        source: CaptureSource,
        usbConnected: Boolean,
        usbGeneration: Int,
        bluetoothGeneration: Int,
        onRequestPermission: (Array<String>) -> Unit,
        onEnableBluetooth: () -> Unit,
        onRequestUsb: (UsbDevice) -> Unit,
        onConnectUsb: (UsbDevice) -> Unit,
        onRescan: () -> Unit,
    ) {
        val bluetoothOn by viewModel.phone().bluetoothEnabled.collectAsStateWithLifecycle()

        when (source) {
            CaptureSource.PHONE -> {
                val phone = viewModel.phone()

                when {
                    !phone.isSupported() -> ActionRequiredBanner(
                        message = "This device has no Bluetooth LE radio. Use the nRF52840 source.",
                        actionLabel = "Switch",
                        onAction = { viewModel.setSource(CaptureSource.USB) },
                    )

                    !phone.hasScanPermission() -> ActionRequiredBanner(
                        message = "Scanning needs permission to use Bluetooth.",
                        actionLabel = "Grant",
                        onAction = { onRequestPermission(phone.requiredPermissions()) },
                    )

                    !bluetoothOn -> ActionRequiredBanner(
                        message = "Bluetooth is turned off.",
                        actionLabel = "Turn on",
                        onAction = onEnableBluetooth,
                    )
                }
            }

            CaptureSource.USB, CaptureSource.BOTH -> {
                val device = viewModel.usbDevice()

                when {
                    device == null -> ActionRequiredBanner(
                        message = "No nRF52840 found. Connect it with an OTG cable.",
                        actionLabel = "Rescan",
                        onAction = onRescan,
                    )

                    !viewModel.usbHasPermission(device) -> ActionRequiredBanner(
                        message = "Permission is needed to talk to ${device.productName ?: "the board"}.",
                        actionLabel = "Allow",
                        onAction = { onRequestUsb(device) },
                    )

                    !usbConnected -> ActionRequiredBanner(
                        message = "${device.productName ?: "nRF52840"} is ready.",
                        actionLabel = "Connect",
                        onAction = { onConnectUsb(device) },
                    )
                }
            }
        }
    }

    @Composable
    private fun rememberExportLauncher(viewModel: SnifferViewModel) =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            if (uri != null) viewModel.export(uri, pendingExportFormat)
        }

    @Composable
    private fun rememberChartLauncher(viewModel: SnifferViewModel) =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("image/png"),
        ) { uri: Uri? ->
            val (points, address) = pendingChart ?: return@rememberLauncherForActivityResult
            pendingChart = null
            if (uri != null) {
                viewModel.exportChart(uri, points, "BLE packets - $address")
            }
        }

    @Composable
    private fun rememberOpenLauncher(viewModel: SnifferViewModel) =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) viewModel.openSessionFile(uri, displayNameOf(uri))
        }

    /**
     * Registers for USB permission results and hotplug events for as long as the
     * composition is alive.
     */
    @Composable
    private fun RegisterUsbReceiver(
        onPermissionResult: (UsbDevice?, Boolean) -> Unit,
        onAttachedOrDetached: () -> Unit,
    ) {
        val context = this
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    when (intent.action) {
                        ACTION_USB_PERMISSION -> onPermissionResult(
                            device,
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                        )
                        UsbManager.ACTION_USB_DEVICE_ATTACHED,
                        UsbManager.ACTION_USB_DEVICE_DETACHED,
                        -> onAttachedOrDetached()
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        manager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty().ifEmpty { "Saved capture" }
    }
}
