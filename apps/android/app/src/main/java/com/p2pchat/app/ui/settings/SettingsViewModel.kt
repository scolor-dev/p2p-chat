package com.p2pchat.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 設定画面の ViewModel。接続先の入力・検証・保存と、接続テストを行う。
 *
 * 接続テストは、保存前の入力中の値で試せる（保存してから試す必要はない）。
 */
class SettingsViewModel(
    private val repository: ServerSettingsRepository,
    private val apiClient: ApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = repository.settings.first()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    host = saved?.host.orEmpty(),
                    port = (saved?.port ?: ServerSettings.DEFAULT_PORT).toString(),
                    isSaved = saved != null,
                )
            }
        }
    }

    fun onHostChange(host: String) {
        _uiState.update {
            it.copy(host = host, hostError = false, isSaved = false, connectionTest = ConnectionTestState.Idle)
        }
    }

    fun onPortChange(port: String) {
        _uiState.update {
            it.copy(port = port, portError = false, isSaved = false, connectionTest = ConnectionTestState.Idle)
        }
    }

    fun save() {
        val settings = validate() ?: return
        viewModelScope.launch {
            repository.save(settings)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    fun testConnection() {
        val settings = validate() ?: return
        _uiState.update { it.copy(connectionTest = ConnectionTestState.Running) }
        viewModelScope.launch {
            val state = apiClient.healthz(settings).fold(
                onSuccess = { ConnectionTestState.Success },
                onFailure = { e -> ConnectionTestState.Failure(e.message ?: e.javaClass.simpleName) },
            )
            _uiState.update { it.copy(connectionTest = state) }
        }
    }

    /** 入力が正しければ [ServerSettings] を返す。誤りがあれば、エラーを画面に出して null を返す。 */
    private fun validate(): ServerSettings? {
        val state = _uiState.value
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull()?.takeIf { it in PORT_RANGE }
        val hostValid = HOST_PATTERN.matches(host)
        _uiState.update { it.copy(hostError = !hostValid, portError = port == null) }
        return if (hostValid && port != null) ServerSettings(host, port) else null
    }

    companion object {
        /**
         * IP アドレスかホスト名だけを受け付ける。
         * `http://` やポート（`:8080`）を含めて入力されたときに、気づけるようにする。
         */
        private val HOST_PATTERN = Regex("[A-Za-z0-9.-]+")
        private val PORT_RANGE = 1..65535

        /** AppContainer から部品を取り出して ViewModel を作る。 */
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                SettingsViewModel(app.container.serverSettingsRepository, app.container.apiClient)
            }
        }
    }
}
