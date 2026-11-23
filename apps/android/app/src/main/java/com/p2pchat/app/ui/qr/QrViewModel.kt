package com.p2pchat.app.ui.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.crypto.DeviceKeyStore
import com.p2pchat.app.data.device.DeviceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** QR 画面が表示に使う状態。画像そのものは持たず、QR に入れる文字列 [contactUrl] を持つ。 */
data class QrUiState(
    val isLoading: Boolean = true,
    val deviceId: String? = null,
    /** QR に入れる `p2pchat://contact?id=...&fp=...`。作れなかったときは null。 */
    val contactUrl: String? = null,
    val error: QrError? = null,
)

enum class QrError {
    /** 端末がまだ登録されていない。 */
    NotRegistered,

    /** 鍵を取り出せないなど。 */
    Failed,
}

/**
 * QR 画面の ViewModel。登録済みの `deviceId` と公開鍵から、QR に入れる URL を作る（#23）。
 * 鍵の取り出しは Keystore を触るので、[ioDispatcher] で行う。
 */
class QrViewModel(
    private val deviceRepository: DeviceRepository,
    private val deviceKeyStore: DeviceKeyStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QrUiState())
    val uiState: StateFlow<QrUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val deviceId = deviceRepository.identity.first()?.deviceId
                if (deviceId == null) {
                    _uiState.update { it.copy(isLoading = false, error = QrError.NotRegistered) }
                    return@launch
                }
                val url = withContext(ioDispatcher) {
                    ContactQrUrl.build(deviceId, deviceKeyStore.getOrCreatePublicKey().encoded)
                }
                _uiState.update { it.copy(isLoading = false, deviceId = deviceId, contactUrl = url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例外のメッセージは画面に出さない（鍵まわりの情報が混ざらないように）
                _uiState.update { it.copy(isLoading = false, error = QrError.Failed) }
            }
        }
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
