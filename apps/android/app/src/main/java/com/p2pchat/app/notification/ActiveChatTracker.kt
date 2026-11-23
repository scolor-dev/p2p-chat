package com.p2pchat.app.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** いまチャット画面を開いている相手（端末 ID）。開いていなければ null。通知を出すかの判定に使う。 */
interface ActiveChatTracker {
    val activeDeviceId: StateFlow<String?>

    /** [deviceId] のチャット画面を開いた。 */
    fun opened(deviceId: String)

    /** [deviceId] のチャット画面を閉じた。別の相手を開いていたら何もしない。 */
    fun closed(deviceId: String)

    /** 何も追跡しない実装（ViewModel の既定値・テスト用）。 */
    data object None : ActiveChatTracker {
        override val activeDeviceId: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override fun opened(deviceId: String) = Unit
        override fun closed(deviceId: String) = Unit
    }
}

class DefaultActiveChatTracker : ActiveChatTracker {
    private val _activeDeviceId = MutableStateFlow<String?>(null)
    override val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

    override fun opened(deviceId: String) {
        _activeDeviceId.value = deviceId
    }

    override fun closed(deviceId: String) {
        _activeDeviceId.compareAndSet(deviceId, null)
    }
}

/** アプリ（プロセス）が前面にあるか。`P2PChatApp` が前面・背面の切り替えで更新する。 */
class AppForegroundState {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun set(foreground: Boolean) {
        _isForeground.value = foreground
    }
}
