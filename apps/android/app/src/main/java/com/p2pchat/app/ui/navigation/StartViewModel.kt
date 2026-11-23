package com.p2pchat.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.device.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 起動時の最初の画面を決める。登録済みなら連絡先一覧、未登録なら登録画面。
 * 決めるのは起動時の 1 回だけ（登録した後の画面遷移は、登録画面が行う）。
 * 起動後に身元が消えた（再登録）ときは、[identityCleared] で知らせる。
 */
class StartViewModel(private val deviceRepository: DeviceRepository) : ViewModel() {

    private val _start = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val start: StateFlow<StartDestination> = _start.asStateFlow()

    /**
     * 登録済みだった身元が消えたとき（再登録）に 1 回流れる。画面側は、これを受けて登録画面へ戻す。
     * 起動時から未登録の場合は流れない（そちらは [start] が登録画面を選ぶ）。
     */
    val identityCleared: Flow<Unit> = flow {
        var wasRegistered: Boolean? = null
        deviceRepository.identity.map { it != null }.distinctUntilChanged().collect { registered ->
            if (wasRegistered == true && !registered) emit(Unit)
            wasRegistered = registered
        }
    }

    init {
        viewModelScope.launch {
            _start.value = try {
                if (deviceRepository.identity.first() != null) StartDestination.Contacts
                else StartDestination.Registration
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 読めないときも、Loading のまま固まらせず、登録画面にする
                StartDestination.Registration
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                StartViewModel(app.container.deviceRepository)
            }
        }
    }
}

/** 起動時の行き先。読み込み中は [Loading]。 */
sealed interface StartDestination {
    data object Loading : StartDestination
    data object Registration : StartDestination
    data object Contacts : StartDestination
}
