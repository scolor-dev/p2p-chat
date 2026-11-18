package com.p2pchat.app.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.crypto.DeviceKeyStore
import com.p2pchat.app.data.device.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

data class QrUiState(
    val isLoading: Boolean = true,
    val deviceId: String? = null,
    val qrBitmap: Bitmap? = null,
    val errorMessage: String? = null,
)

class QrViewModel(
    private val deviceRepository: DeviceRepository,
    private val deviceKeyStore: DeviceKeyStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QrUiState())
    val uiState: StateFlow<QrUiState> = _uiState.asStateFlow()

    init {
        loadContactInfo()
    }

    private fun loadContactInfo() {
        viewModelScope.launch {
            try {
                // 1. deviceId を取得
                val identity = deviceRepository.identity.first()
                val deviceId = identity?.deviceId

                if (deviceId == null) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "端末が登録されていません") }
                    return@launch
                }

                // 2. 公開鍵から Fingerprint (fp) を計算 (SHA-256 + unpadded base64url)
                val publicKey = deviceKeyStore.getOrCreatePublicKey()
                val fp = generateFingerprint(publicKey)

                // 3. 連絡先 URL の組み立て: p2pchat://contact?id=<deviceId>&fp=<fingerprint>
                val contactUrl = "p2pchat://contact?id=$deviceId&fp=$fp"

                // 4. ZXing で QR コードの Bitmap を生成
                val bitmap = generateQrBitmap(contactUrl, 512, 512)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        deviceId = deviceId,
                        qrBitmap = bitmap,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "エラーが発生しました") }
            }
        }
    }

    private fun generateFingerprint(publicKey: PublicKey): String {
        val derBytes = publicKey.encoded
        val sha256 = MessageDigest.getInstance("SHA-256").digest(derBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256)
    }

    private fun generateQrBitmap(content: String, width: Int, height: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                QrViewModel(app.container.deviceRepository, app.container.deviceKeyStore)
            }
        }
    }
}