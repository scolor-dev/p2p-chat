package com.p2pchat.app.ui.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.ui.theme.P2PChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 自分の連絡先を QR コードで見せる画面（#23）。読み取りは [QrScanScreen]（#24）。 */
@Composable
fun QrScreen(
    onBack: () -> Unit,
    onScanClick: () -> Unit,
    viewModel: QrViewModel = viewModel(factory = QrViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    QrContent(uiState = uiState, onBack = onBack, onScanClick = onScanClick)
}

/** ViewModel を知らない、表示だけの部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrContent(uiState: QrUiState, onBack: () -> Unit, onScanClick: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.qr_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_arrow_back_ios_new_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onScanClick) { Text(stringResource(R.string.qr_scan_open)) }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.error != null -> Text(
                    text = stringResource(
                        when (uiState.error) {
                            QrError.NotRegistered -> R.string.qr_error_not_registered
                            else -> R.string.qr_error_failed
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                uiState.contactUrl != null && uiState.deviceId != null -> QrBody(uiState.contactUrl, uiState.deviceId)
            }
        }
    }
}

@Composable
private fun QrBody(contactUrl: String, deviceId: String) {
    // 画像の生成は重いので、メインスレッドの外で行う。URL が変わらない限り作り直さない
    val image by produceState<ImageBitmap?>(initialValue = null, contactUrl) {
        value = withContext(Dispatchers.Default) { createQrBitmap(contactUrl).asImageBitmap() }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.qr_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        val bitmap = image
        if (bitmap == null) {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.qr_image_description),
                modifier = Modifier.size(280.dp),
            )
        }
        Text(stringResource(R.string.qr_device_id_label), style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(deviceId, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun QrContentPreview() {
    P2PChatTheme {
        QrContent(
            uiState = QrUiState(
                isLoading = false,
                deviceId = "a1b2c3d4e5f60718",
                contactUrl = "p2pchat://contact?id=a1b2c3d4e5f60718&fp=ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0",
            ),
            onBack = {},
            onScanClick = {},
        )
    }
}
