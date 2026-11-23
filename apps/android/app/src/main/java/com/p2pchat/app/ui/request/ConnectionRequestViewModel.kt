package com.p2pchat.app.ui.request

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.peer.ConnectionRequest
import com.p2pchat.app.data.peer.ConnectionRequestManager
import kotlinx.coroutines.flow.StateFlow

/** 接続の申請の確認ダイアログ。状態は [ConnectionRequestManager] が持つ。 */
class ConnectionRequestViewModel(private val manager: ConnectionRequestManager) : ViewModel() {
    val request: StateFlow<ConnectionRequest?> = manager.current

    fun allow() = manager.allow()

    fun deny() = manager.deny()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                ConnectionRequestViewModel(app.container.connectionRequestManager)
            }
        }
    }
}
