package com.p2pchat.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.device.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 起動時の最初の画面を決める。登録済みなら連絡先一覧、未登録なら登録画面。
 * 決めるのは起動時の 1 回だけ（登録した後の画面遷移は、登録画面が行う）。
 */
class StartViewModel(deviceRepository: DeviceRepository) : ViewModel() {

    private val _start = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val start: StateFlow<StartDestination> = _start.asStateFlow()

    init {
        viewModelScope.launch {
            _start.value =
                if (deviceRepository.identity.first() != null) StartDestination.Contacts
                else StartDestination.Registration
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
