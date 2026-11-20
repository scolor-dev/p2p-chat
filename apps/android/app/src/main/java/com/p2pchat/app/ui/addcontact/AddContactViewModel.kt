package com.p2pchat.app.ui.addcontact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * ID で連絡先を追加する画面の ViewModel。
 *
 * 1. [search]: 入力された `deviceId` をサーバーに問い合わせて、名前と公開鍵を取得する
 * 2. [add]: 見つかった相手を「未確認」の連絡先として保存する（確認済みにするのは QR の照合の役目）
 *
 * 自分の ID と追加済みの ID は、サーバーに問い合わせる前に断る。
 */
class AddContactViewModel(
    private val apiClient: ApiClient,
    private val serverSettingsRepository: ServerSettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val contactRepository: ContactRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddContactUiState())
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onDeviceIdChange(deviceId: String) {
        // 入力が変わったら、検索中だった問い合わせも見つかった相手も無効にする
        searchJob?.cancel()
        _uiState.update {
            it.copy(deviceId = deviceId, isSearching = false, found = null, error = null)
        }
    }

    fun search() {
        val state = _uiState.value
        if (state.isSearching || state.isAdding || state.isAdded) return

        val id = state.deviceId.trim()
        if (id.isEmpty()) {
            _uiState.update { it.copy(error = AddContactError.Empty) }
            return
        }

        // launch の前に立てる。続けて 2 回呼ばれても、2 回目はここで止まる
        _uiState.update { it.copy(isSearching = true, found = null, error = null) }
        searchJob = viewModelScope.launch {
            try {
                val server = serverSettingsRepository.settings.first()
                val error = when {
                    server == null -> AddContactError.ServerNotConfigured
                    id == deviceRepository.identity.first()?.deviceId -> AddContactError.Self
                    contactRepository.getContact(id) != null -> AddContactError.AlreadyAdded
                    else -> null
                }
                if (error != null || server == null) {
                    _uiState.update { it.copy(isSearching = false, error = error) }
                    return@launch
                }
                apiClient.getDevice(server, id).fold(
                    onSuccess = { device -> _uiState.update { it.copy(isSearching = false, found = device) } },
                    onFailure = { e ->
                        val reason = if (e is ApiException && e.status == 404) AddContactError.NotFound else AddContactError.Network
                        _uiState.update { it.copy(isSearching = false, error = reason) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 想定外の例外でも、「検索中」のまま固まらないようにする
                _uiState.update { it.copy(isSearching = false, error = AddContactError.Network) }
            }
        }
    }

    fun add() {
        val state = _uiState.value
        val found = state.found ?: return
        if (state.isSearching || state.isAdding || state.isAdded) return

        _uiState.update { it.copy(isAdding = true, error = null) }
        viewModelScope.launch {
            try {
                contactRepository.saveContact(
                    Contact(
                        deviceId = found.deviceId,
                        name = found.name,
                        publicKey = found.publicKey,
                        isVerified = false,
                        createdAt = clock(),
                    ),
                )
                _uiState.update { it.copy(isAdding = false, isAdded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isAdding = false, error = AddContactError.StorageFailed) }
            }
        }
    }

    companion object {
        /** AppContainer から部品を取り出して ViewModel を作る。 */
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                val c = app.container
                AddContactViewModel(c.apiClient, c.serverSettingsRepository, c.deviceRepository, c.contactRepository)
            }
        }
    }
}
