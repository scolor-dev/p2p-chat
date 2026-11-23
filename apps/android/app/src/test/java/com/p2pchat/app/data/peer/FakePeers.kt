package com.p2pchat.app.data.peer

import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.IncomingSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** テスト用の PeerSession。状態を外から動かし、送った文字列を記録し、受信を [receive] で流せる。 */
class FakePeerSession(
    override val peerDeviceId: String,
    initial: PeerState = PeerState.Open,
) : PeerSession {
    override val state = MutableStateFlow(initial)
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incoming: Flow<String> = _incoming

    val sent = mutableListOf<String>()

    /** [send] の戻り値。false にすると「DataChannel に載せられなかった」を再現できる。 */
    var sendResult = true

    /** [send] が呼ばれた時点の呼び出し側の記録用フック（順序の確認に使う）。 */
    var onSend: (String) -> Unit = {}

    override fun send(text: String): Boolean {
        onSend(text)
        if (!sendResult) return false
        sent += text
        return true
    }

    override fun close() {
        state.value = PeerState.Disconnected
    }

    suspend fun receive(text: String) = _incoming.emit(text)
}

/** テスト用の PeerConnector。[connect] で返す session を差し込める。 */
class FakePeerConnector : PeerConnector {
    private val _sessions = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
    override val sessions: StateFlow<Map<String, PeerSession>> = _sessions
    override val acceptedSessions: SharedFlow<PeerSession> = MutableSharedFlow()

    val connectCalls = mutableListOf<String>()

    /** [connect] で新しく作る session の初期状態。 */
    var initialState: PeerState = PeerState.Open

    fun put(session: PeerSession) {
        _sessions.value = _sessions.value + (session.peerDeviceId to session)
    }

    override fun start() = Unit

    override fun connect(peerDeviceId: String): PeerSession {
        connectCalls += peerDeviceId
        _sessions.value[peerDeviceId]?.let { if (!it.state.value.isTerminal) return it }
        return FakePeerSession(peerDeviceId, initialState).also { put(it) }
    }

    override fun disconnect(peerDeviceId: String) = Unit

    override fun acceptOffer(offer: IncomingSignal.Offer, candidates: List<CandidatePayload>): PeerSession =
        FakePeerSession(offer.from).also { put(it) }
}
