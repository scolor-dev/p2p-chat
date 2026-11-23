package com.p2pchat.app.ui.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.PublicKeyFingerprint
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** QR の読み取り画面が表示に使う状態。 */
data class QrScanUiState(
    /** サーバーに問い合わせ中・保存中。 */
    val isProcessing: Boolean = false,
    /** 確認済みにできた。画面はこれを見て結果を見せる。 */
    val success: QrScanSuccess? = null,
    /** 失敗の理由。[QrScanViewModel.dismissError] で閉じるまで、新しい読み取りは受け付けない。 */
    val error: QrScanError? = null,
)

/** 確認済みにできた相手。[wasAlreadyContact] が true なら、すでに連絡先にいた相手を確認済みにした。 */
data class QrScanSuccess(val name: String, val wasAlreadyContact: Boolean)

/** 読み取りに失敗した理由。画面はこれを文言にする。 */
enum class QrScanError {
    /** このアプリの連絡先の QR ではない。 */
    NotContactQr,

    /** 連絡先の QR だが、壊れている（id / fp が無いなど）。 */
    BrokenQr,

    /** 自分自身の QR。 */
    Self,

    /** サーバーの接続先が保存されていない。 */
    ServerNotConfigured,

    /** その ID の端末がサーバーにない。 */
    NotFound,

    /** サーバーに届かない・応答が読めない・サーバーのエラー。 */
    Network,

    /** サーバーの公開鍵が QR の `fp` と合わない。サーバーが鍵を差し替えた可能性がある。保存しない。 */
    FingerprintMismatch,

    /** 端末への保存に失敗した。 */
    StorageFailed,
}

/**
 * 相手の QR を読み取って、公開鍵を確かめてから連絡先に保存する（`docs/design.md` の 5-2）。
 *
 * カメラは知らない。読み取った文字列を [onQrScanned] で受け取る。
 * 1. 文字列を [ContactQrParser] で読む
 * 2. 自分の ID なら断る
 * 3. `GET /devices/{id}` で公開鍵を取り、`fp` と照合する
 * 4. 一致したら確認済みで保存（すでにいる未確認の相手なら確認済みに更新）。不一致なら何も保存しない
 */
class QrScanViewModel(
    private val apiClient: ApiClient,
    private val serverSettingsRepository: ServerSettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val contactRepository: ContactRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QrScanUiState())
    val uiState: StateFlow<QrScanUiState> = _uiState.asStateFlow()

    /** カメラが QR を読み取るたびに呼ばれる（同じ QR で何度も呼ばれてよい）。 */
    fun onQrScanned(text: String) {
        val state = _uiState.value
        if (state.isProcessing || state.success != null || state.error != null) return

        when (val parsed = ContactQrParser.parse(text)) {
            ContactQrParseResult.NotContactQr -> fail(QrScanError.NotContactQr)
            ContactQrParseResult.Broken -> fail(QrScanError.BrokenQr)
            is ContactQrParseResult.Success -> verifyAndSave(parsed)
        }
    }

    /** エラーを閉じて、もう一度読み取れるようにする。 */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun fail(error: QrScanError) {
        _uiState.update { it.copy(isProcessing = false, error = error) }
    }

    private fun verifyAndSave(qr: ContactQrParseResult.Success) {
        // launch の前に立てる。続けて呼ばれても、2 回目はここで止まる
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                if (qr.deviceId == deviceRepository.identity.first()?.deviceId) {
                    fail(QrScanError.Self)
                    return@launch
                }
                val server = serverSettingsRepository.settings.first()
                if (server == null) {
                    fail(QrScanError.ServerNotConfigured)
                    return@launch
                }
                val device = apiClient.getDevice(server, qr.deviceId).getOrElse { e ->
                    fail(if (e is ApiException && e.status == 404) QrScanError.NotFound else QrScanError.Network)
                    return@launch
                }
                // 読めない公開鍵（null）も、QR の fp とは合わない扱いにする
                if (PublicKeyFingerprint.fromBase64(device.publicKey) != qr.fingerprint) {
                    fail(QrScanError.FingerprintMismatch)
                    return@launch
                }
                val existing = contactRepository.getContact(qr.deviceId)
                when {
                    existing == null -> contactRepository.saveContact(
                        Contact(
                            deviceId = qr.deviceId,
                            name = device.name,
                            publicKey = device.publicKey,
                            isVerified = true,
                            createdAt = clock(),
                        ),
                    )
                    // 保存済みの公開鍵がサーバーの鍵と違うまま、確認済みにはしない
                    existing.publicKey != device.publicKey -> {
                        fail(QrScanError.FingerprintMismatch)
                        return@launch
                    }
                    !existing.isVerified -> contactRepository.updateVerified(qr.deviceId, true)
                }
                val success = QrScanSuccess(name = existing?.name ?: device.name, wasAlreadyContact = existing != null)
                _uiState.update { it.copy(isProcessing = false, success = success) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存・読み出しの想定外の失敗。例外のメッセージは画面に出さない
                fail(QrScanError.StorageFailed)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                val c = app.container
                QrScanViewModel(c.apiClient, c.serverSettingsRepository, c.deviceRepository, c.contactRepository)
            }
        }
    }
}
