package com.p2pchat.app.ui.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import java.util.concurrent.Executors

/** 相手の QR をカメラで読み取って、連絡先に（確認済みで）保存する画面（#24）。 */
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    viewModel: QrScanViewModel = viewModel(factory = QrScanViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    QrScanContent(
        uiState = uiState,
        onQrScanned = viewModel::onQrScanned,
        onDismissError = viewModel::dismissError,
        onBack = onBack,
    )
}

/** ViewModel を知らない、表示だけの部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanContent(
    uiState: QrScanUiState,
    onQrScanned: (String) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    var requested by remember { mutableStateOf(false) }
    // 画面を開いたとき、許可されていなければ 1 回だけ要求する
    DisposableEffect(Unit) {
        if (!hasPermission && !requested) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.qr_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_arrow_back_ios_new_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.success != null -> ScanSuccess(uiState.success, onDone = onBack)
                !hasPermission -> PermissionGuide(
                    onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { context.openAppSettings() },
                )
                else -> {
                    CameraPreview(onQrScanned = onQrScanned)
                    Text(
                        text = stringResource(R.string.qr_scan_description),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    )
                    if (uiState.isProcessing) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }

    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = {
                Text(
                    stringResource(
                        if (error == QrScanError.FingerprintMismatch) R.string.qr_scan_warning_title else R.string.qr_scan_error_title,
                    ),
                )
            },
            text = { Text(stringResource(error.messageRes())) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.qr_scan_error_ok)) } },
        )
    }
}

private fun QrScanError.messageRes(): Int = when (this) {
    QrScanError.NotContactQr -> R.string.qr_scan_error_not_contact
    QrScanError.BrokenQr -> R.string.qr_scan_error_broken
    QrScanError.Self -> R.string.qr_scan_error_self
    QrScanError.ServerNotConfigured -> R.string.qr_scan_error_server_not_configured
    QrScanError.NotFound -> R.string.qr_scan_error_not_found
    QrScanError.Network -> R.string.qr_scan_error_network
    QrScanError.FingerprintMismatch -> R.string.qr_scan_error_mismatch
    QrScanError.StorageFailed -> R.string.qr_scan_error_storage
}

@Composable
private fun ScanSuccess(success: QrScanSuccess, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(
                if (success.wasAlreadyContact) R.string.qr_scan_success_updated else R.string.qr_scan_success_added,
                success.name,
            ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onDone) { Text(stringResource(R.string.qr_scan_done)) }
    }
}

@Composable
private fun PermissionGuide(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.qr_scan_permission_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text(stringResource(R.string.qr_scan_permission_request)) }
        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.qr_scan_permission_settings)) }
    }
}

/** CameraX のプレビューと、フレームごとの QR の読み取り。画面が閉じたらカメラも止める。 */
@Composable
private fun CameraPreview(onQrScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { ZxingQrImageDecoder() }
    val latestCallback by androidx.compose.runtime.rememberUpdatedState(onQrScanned)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { image ->
                        try {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val bytes = ByteArray(buffer.remaining()).also { b -> buffer.get(b) }
                            decoder.decode(bytes, plane.rowStride, image.width, image.height)
                                ?.let { text -> ContextCompat.getMainExecutor(context).execute { latestCallback(text) } }
                        } finally {
                            image.close()
                        }
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // カメラを使えない端末・他のアプリが使用中。プレビューが黒いままになる
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
