package com.p2pchat.app.data.peer

import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.ErrorCodes
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.OutgoingSignal
import com.p2pchat.app.data.signaling.SdpPayload
import com.p2pchat.app.data.signaling.SignalingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [PeerSession] の実体。offer / answer / candidate の取り回し、offer の衝突解決、candidate の保留、
 * タイムアウト、エラーの扱いをここに持つ。libwebrtc には触らず、[RtcPeerConnection] 越しに動かす。
 *
 * すべての出来事（相手からのシグナル、PeerConnection の通知、タイムアウト、close）は 1 本のキューに入れ、
 * 1 つのコルーチンで順番に処理する。スレッドをまたぐ競合と、順序の入れ替わりを避けるため。
 */
internal class DefaultPeerSession(
    override val peerDeviceId: String,
    private val selfDeviceId: String,
    private val signaling: SignalingClient,
    private val factory: RtcPeerConnectionFactory,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    /** null なら話しかける側（offer を送る）。非 null なら受ける側で、この offer に answer を返す。 */
    private val remoteOffer: IncomingSignal.Offer? = null,
    initialRemoteCandidates: List<CandidatePayload> = emptyList(),
) : PeerSession {

    private sealed interface Event {
        data object Begin : Event
        data class Signal(val signal: IncomingSignal) : Event
        data class LocalCandidate(val generation: Int, val candidate: CandidatePayload) : Event
        data class ConnectionState(val generation: Int, val state: RtcConnectionState) : Event
        data class ChannelOpen(val generation: Int) : Event
        data class ChannelClosed(val generation: Int) : Event
        data class Message(val generation: Int, val text: String) : Event
        data object Timeout : Event
        data object Close : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<PeerState>(PeerState.Connecting)
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override val state: StateFlow<PeerState> = _state.asStateFlow()
    override val incoming: Flow<String> = _incoming.asSharedFlow()

    // 以下は処理用のコルーチンの中でだけ書き換える（pc と awaitingAnswer は外から読むので @Volatile）
    @Volatile private var pc: RtcPeerConnection? = null
    private var generation = 0
    private var remoteDescriptionSet = false
    private var localSdpSent = false
    private val pendingRemoteCandidates = initialRemoteCandidates.toMutableList()
    private val pendingLocalCandidates = mutableListOf<CandidatePayload>()

    /** 自分の offer を送って answer を待っている間は true。衝突解決の対象になる。 */
    @Volatile
    var awaitingAnswer: Boolean = remoteOffer == null
        private set

    private var timeoutJob: Job? = null
    private val loopJob: Job = scope.launch(start = CoroutineStart.LAZY) { run() }

    /** 処理を始める。作った直後に 1 回だけ呼ぶ。 */
    fun start() {
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            events.trySend(Event.Timeout)
        }
        events.trySend(Event.Begin)
        loopJob.start()
    }

    /** 始めずに失敗にする（シグナリングが `ready` でないときなど）。 */
    fun abort(reason: PeerFailure) {
        setState(PeerState.Failed(reason))
        events.close()
        loopJob.cancel()
    }

    fun onSignal(signal: IncomingSignal) {
        events.trySend(Event.Signal(signal))
    }

    override fun send(text: String): Boolean = state.value == PeerState.Open && pc?.send(text) == true

    override fun close() {
        if (state.value.isTerminal) return
        setState(PeerState.Disconnected)
        events.trySend(Event.Close)
    }

    private fun setState(next: PeerState) {
        // 終わった状態は、あとから書き換えない
        _state.update { if (it.isTerminal) it else next }
    }

    private suspend fun run() {
        try {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // SDP の処理の失敗など。中身（SDP）はログにも出さない
                    setState(PeerState.Failed(PeerFailure.ConnectionFailed))
                }
                if (state.value.isTerminal) break
            }
        } finally {
            setState(PeerState.Disconnected) // 外から止められたときの保険（終わっていれば変わらない）
            timeoutJob?.cancel()
            events.close()
            pc?.close()
        }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            Event.Begin -> begin()
            is Event.Signal -> handleSignal(event.signal)
            is Event.LocalCandidate -> if (event.generation == generation) sendLocalCandidate(event.candidate)
            is Event.ConnectionState -> if (event.generation == generation) handleRtcState(event.state)
            is Event.ChannelOpen -> if (event.generation == generation && state.value == PeerState.Connecting) {
                setState(PeerState.Open)
                timeoutJob?.cancel()
            }
            is Event.ChannelClosed -> if (event.generation == generation) disconnected()
            is Event.Message -> if (event.generation == generation) _incoming.tryEmit(event.text)
            Event.Timeout -> if (state.value == PeerState.Connecting) setState(PeerState.Failed(PeerFailure.Timeout))
            Event.Close -> setState(PeerState.Disconnected)
        }
    }

    private suspend fun begin() {
        val offer = remoteOffer
        if (offer == null) {
            val conn = newConnection()
            val sdp = conn.createOffer()
            if (!signaling.send(OutgoingSignal.Offer(peerDeviceId, SdpPayload(sdp)))) {
                setState(PeerState.Failed(PeerFailure.SignalingUnavailable))
                return
            }
            localSdpSent = true
            flushLocalCandidates()
        } else {
            answer(offer)
        }
    }

    /** 相手の offer を受け入れて answer を返す。 */
    private suspend fun answer(offer: IncomingSignal.Offer) {
        val conn = pc ?: newConnection()
        val sdp = conn.acceptOffer(offer.payload.sdp)
        remoteDescriptionSet = true
        flushRemoteCandidates(conn)
        if (!signaling.send(OutgoingSignal.Answer(peerDeviceId, SdpPayload(sdp)))) {
            setState(PeerState.Failed(PeerFailure.SignalingUnavailable))
            return
        }
        localSdpSent = true
        flushLocalCandidates()
    }

    private suspend fun handleSignal(signal: IncomingSignal) {
        when (signal) {
            is IncomingSignal.Offer -> handleCollidingOffer(signal)
            is IncomingSignal.Answer -> if (awaitingAnswer && !remoteDescriptionSet) {
                val conn = pc ?: return
                conn.acceptAnswer(signal.payload.sdp)
                remoteDescriptionSet = true
                awaitingAnswer = false
                flushRemoteCandidates(conn)
            }
            is IncomingSignal.Candidate -> {
                val conn = pc
                if (remoteDescriptionSet && conn != null) addCandidate(conn, signal.payload)
                else pendingRemoteCandidates += signal.payload
            }
            is IncomingSignal.Reject -> if (state.value == PeerState.Connecting) {
                setState(PeerState.Failed(PeerFailure.Rejected))
            }
            is IncomingSignal.ServerError ->
                if (signal.code == ErrorCodes.PEER_OFFLINE && state.value == PeerState.Connecting) {
                    setState(PeerState.Failed(PeerFailure.PeerOffline))
                }
            is IncomingSignal.Ready -> Unit
        }
    }

    /**
     * 自分の offer を送ったあと、相手からも offer が届いた（設計書 5-6）。
     * deviceId が小さいほうの offer を採用する。
     */
    private suspend fun handleCollidingOffer(offer: IncomingSignal.Offer) {
        if (!awaitingAnswer || remoteDescriptionSet) return
        if (selfDeviceId < peerDeviceId) return // 自分のほうが小さい: 相手の offer を無視する

        // 自分のほうが大きい: 自分の offer を捨てて（rollback の代わりに PeerConnection ごと作り直す）、相手の offer を受け入れる
        pc?.close()
        pc = null
        generation++
        awaitingAnswer = false
        localSdpSent = false
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        answer(offer)
    }

    private fun handleRtcState(rtc: RtcConnectionState) {
        when (rtc) {
            RtcConnectionState.Failed, RtcConnectionState.Closed -> disconnected()
            RtcConnectionState.Connected, RtcConnectionState.Other -> Unit
        }
    }

    /** open だった接続なら切断、そうでなければ失敗。 */
    private fun disconnected() {
        when (state.value) {
            PeerState.Open -> setState(PeerState.Disconnected)
            PeerState.Connecting -> setState(PeerState.Failed(PeerFailure.ConnectionFailed))
            else -> Unit
        }
    }

    private fun newConnection(): RtcPeerConnection {
        val gen = ++generation
        val listener = object : RtcListener {
            override fun onLocalCandidate(candidate: CandidatePayload) {
                events.trySend(Event.LocalCandidate(gen, candidate))
            }

            override fun onConnectionState(state: RtcConnectionState) {
                events.trySend(Event.ConnectionState(gen, state))
            }

            override fun onChannelOpen() {
                events.trySend(Event.ChannelOpen(gen))
            }

            override fun onChannelClosed() {
                events.trySend(Event.ChannelClosed(gen))
            }

            override fun onMessage(text: String) {
                events.trySend(Event.Message(gen, text))
            }
        }
        return factory.create(listener).also { pc = it }
    }

    /** offer / answer を送るより前に candidate を送ると、相手が session を持っていないので、送ったあとまで溜める。 */
    private fun sendLocalCandidate(candidate: CandidatePayload) {
        if (localSdpSent) signaling.send(OutgoingSignal.Candidate(peerDeviceId, candidate))
        else pendingLocalCandidates += candidate
    }

    private fun flushLocalCandidates() {
        pendingLocalCandidates.forEach { signaling.send(OutgoingSignal.Candidate(peerDeviceId, it)) }
        pendingLocalCandidates.clear()
    }

    private suspend fun flushRemoteCandidates(conn: RtcPeerConnection) {
        val candidates = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        candidates.forEach { addCandidate(conn, it) }
    }

    /** 古い offer の candidate など、追加に失敗するものがあっても接続全体は失敗にしない。 */
    private suspend fun addCandidate(conn: RtcPeerConnection, candidate: CandidatePayload) {
        try {
            conn.addRemoteCandidate(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 無視
        }
    }
}
