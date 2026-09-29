package org.itantra.app.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.transport.crypto.PairingInfo

private enum class PairingTab { MY_CODE, SCAN, PAIRED }

/**
 * Pairing screen: shows this device's QR ([DeviceIdentityHolder]), scans a peer's QR (CameraX +
 * ZXing, see [decodeQrFromImageProxy]) or accepts a pasted code, and lists/removes already-paired
 * devices ([PairedDevicesStore]). Accepting a code additionally wires it into the live transport
 * via [TalkService.pairWith] (encryption + ALERT-signature trust) when the service is running.
 */
@Composable
fun PairingScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(PairingTab.MY_CODE) }
    var pairedDevices by remember { mutableStateOf(PairedDevicesStore.list(context)) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun acceptCode(raw: String) {
        val info = runCatching { PairingInfo.fromQrString(raw.trim()) }.getOrNull()
        if (info == null || info.deviceId == DeviceIdentityHolder.deviceId(context)) {
            toast = context.getString(R.string.pairing_invalid)
            return
        }
        PairedDevicesStore.add(context, info)
        TalkService.instance?.pairWith(DeviceIdentityHolder.identity(context), info)
        pairedDevices = PairedDevicesStore.list(context)
        toast = context.getString(R.string.pairing_added)
        tab = PairingTab.PAIRED
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            stringResource(R.string.pairing_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 14.dp),
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PairingTab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = PairingTab.entries.size),
                ) {
                    Text(
                        when (t) {
                            PairingTab.MY_CODE -> stringResource(R.string.pairing_my_qr)
                            PairingTab.SCAN -> stringResource(R.string.pairing_scan)
                            PairingTab.PAIRED -> stringResource(R.string.pairing_paired_devices)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (tab) {
            PairingTab.MY_CODE -> MyCodeTab(context)
            PairingTab.SCAN -> ScanTab(onCodeAccepted = ::acceptCode)
            PairingTab.PAIRED -> PairedTab(
                devices = pairedDevices,
                onRemove = { deviceId ->
                    PairedDevicesStore.remove(context, deviceId)
                    pairedDevices = PairedDevicesStore.list(context)
                },
            )
        }

        toast?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun MyCodeTab(context: android.content.Context) {
    val deviceId = remember { DeviceIdentityHolder.deviceId(context) }
    val qrString = remember { DeviceIdentityHolder.identity(context).pairingInfo(deviceId).toQrString() }
    val bitmap: Bitmap = remember(qrString) { encodeQrBitmap(qrString) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.pairing_my_qr_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Surface(
            color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pairing_my_qr),
                modifier = Modifier.padding(18.dp).width(220.dp).aspectRatio(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.pairing_device_id, deviceId.takeLast(8)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanTab(onCodeAccepted: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    var pasteText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pairing_scan_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))

        if (hasCameraPermission) {
            CameraScanner(onCodeDetected = onCodeAccepted)
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp).height(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.pairing_camera_permission), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.pairing_grant_camera))
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.pairing_paste), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pasteText,
            onValueChange = { pasteText = it },
            placeholder = { Text(stringResource(R.string.pairing_paste_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (pasteText.isNotBlank()) { onCodeAccepted(pasteText); pasteText = "" } },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.pairing_paste_action))
        }
    }
}

/** CameraX preview + a throttled [ImageAnalysis] frame reader decoding QR codes via ZXing (see
 * [decodeQrFromImageProxy]); fires [onCodeDetected] at most once, on the first frame that decodes. */
@Composable
private fun CameraScanner(onCodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var fired by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { imageProxy ->
                if (!fired) {
                    val text = decodeQrFromImageProxy(imageProxy)
                    if (text != null) {
                        fired = true
                        onCodeDetected(text)
                    }
                }
                imageProxy.close()
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun PairedTab(devices: List<PairedDevice>, onRemove: (String) -> Unit) {
    if (devices.isEmpty()) {
        Text(stringResource(R.string.pairing_no_devices), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(devices, key = { it.info.deviceId }) { device ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.pairing_device_id, device.info.deviceId.takeLast(8)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        dateFormat.format(Date(device.pairedAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRemove(device.info.deviceId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pairing_remove), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.pairing_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
