package com.p2pchat.app.ui.registration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.device.RegistrationException
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登録画面の ViewModel。名前を検証して、端末を登録する。
 *
 * - サーバーの接続先は [ServerSettingsRepository] から取る。未保存なら登録しない
 * - 登録中は、もう一度押されても何もしない（二重登録を防ぐ）
 * - すでに登録済みなら、登録処理は呼ばず、`deviceId` の表示だけにする
 */
class RegistrationViewModel(
    private val deviceRepository: DeviceRepository,
    private val serverSettingsRepository: ServerSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegistrationUiState())
    val uiState: StateFlow<RegistrationUiState> = _uiState.asStateFlow()

    init {
        // 設定画面で接続先を保存して戻ってきたときも反映されるよう、流れ続けて受け取る
        viewModelScope.launch {
            combine(serverSettingsRepository.settings, deviceRepository.identity) { server, identity ->
                (server != null) to identity?.deviceId
            }.collect { (configured, deviceId) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isServerConfigured = configured,
                        registeredDeviceId = deviceId ?: it.registeredDeviceId,
                        // 接続先を保存できたら、「未設定」の案内は消す
                        error = if (configured && it.error == RegistrationError.ServerNotConfigured) null else it.error,
                    )
                }
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name, nameError = null, error = null) }
    }

    fun register() {
        val state = _uiState.value
        if (state.isLoading || state.isRegistering || state.registeredDeviceId != null) return

        val name = state.name.trim()
        val nameError = validateName(name)
        if (nameError != null) {
            _uiState.update { it.copy(nameError = nameError) }
            return
        }

        // launch の前に立てる。続けて 2 回呼ばれても、2 回目はここで止まる
        _uiState.update { it.copy(isRegistering = true, error = null, nameError = null) }
        viewModelScope.launch {
            val server = serverSettingsRepository.settings.first()
            if (server == null) {
                _uiState.update {
                    it.copy(isRegistering = false, isServerConfigured = false, error = RegistrationError.ServerNotConfigured)
                }
                return@launch
            }
            deviceRepository.register(server, name).fold(
                onSuccess = { identity ->
                    _uiState.update { it.copy(isRegistering = false, registeredDeviceId = identity.deviceId) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isRegistering = false, error = e.toRegistrationError()) }
                },
            )
        }
    }

    private fun validateName(name: String): NameError? = when {
        name.isEmpty() -> NameError.Empty
        // サーバーは文字数（Unicode のコードポイント）で数えるので、同じ数え方にする
        name.codePointCount(0, name.length) > MAX_NAME_LENGTH -> NameError.TooLong
        else -> null
    }

    private fun Throwable.toRegistrationError(): RegistrationError = when (this) {
        is RegistrationException.KeyFailed -> RegistrationError.KeyFailed
        is RegistrationException.Rejected ->
            RegistrationError.Rejected(listOfNotNull(code, serverMessage).joinToString(": ").ifEmpty { "HTTP $status" })
        is RegistrationException.StorageFailed -> RegistrationError.StorageFailed
        // RegistrationException.Network と、想定外の例外
        else -> RegistrationError.Network
    }

    companion object {
        const val MAX_NAME_LENGTH = 64

        /** AppContainer から部品を取り出して ViewModel を作る。 */
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                RegistrationViewModel(app.container.deviceRepository, app.container.serverSettingsRepository)
            }
        }
    }
}
