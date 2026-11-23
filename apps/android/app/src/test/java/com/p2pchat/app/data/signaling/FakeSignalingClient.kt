package com.p2pchat.app.data.signaling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** ViewModel のテスト用の偽物。状態を外から差し替えられ、送ったメッセージを記録する。 */
class FakeSignalingClient(initial: SignalingState = SignalingState.Stopped) : SignalingClient {
    override val state = MutableStateFlow(initial)
    override val incoming = MutableSharedFlow<IncomingSignal>(extraBufferCapacity = 64)

    val sent = mutableListOf<OutgoingSignal>()

    override fun send(signal: OutgoingSignal): Boolean {
        if (state.value !is SignalingState.Ready) return false
        sent += signal
        return true
    }

    override fun start() = Unit
    override fun stop() = Unit
}
