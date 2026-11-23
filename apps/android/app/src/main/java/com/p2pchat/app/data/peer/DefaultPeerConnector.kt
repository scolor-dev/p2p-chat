package com.p2pchat.app.data.peer

import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.ErrorCodes
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.SignalingClient
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PeerConnector] の実体。シグナリングで届いたメッセージを、相手ごとの [DefaultPeerSession] に振り分ける。
 *
 * - 連絡先にいる相手の offer だけ自動で受け入れる（5-3）。いない相手は [unknownOfferHandler] に渡す（既定は無視。#27 が差し込む）。
 * - 自分の offer が通っている最中に相手の offer が届いたら、session の中で衝突を解決する（5-6）。
 */
class DefaultPeerConnector(
    private val signaling: SignalingClient,
    private val contactRepository: ContactRepository,
    private val factory: RtcPeerConnectionFactory,
    private val scope: CoroutineScope,
    private val unknownOfferHandler: UnknownOfferHandler = UnknownOfferHandler.Ignore,
    private val timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    private val iceDisconnectedGraceMillis: Long = ICE_DISCONNECTED_GRACE_MILLIS,
) : PeerConnector {
    private val _sessions = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
    private val _accepted = MutableSharedFlow<PeerSession>(extraBufferCapacity = 16)
    private val started = AtomicBoolean(false)

    override val sessions: StateFlow<Map<String, PeerSession>> = _sessions.asStateFlow()
    override val acceptedSessions: SharedFlow<PeerSession> = _accepted.asSharedFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { signaling.incoming.collect { handle(it) } }
    }

    @Synchronized
    override fun connect(peerDeviceId: String): PeerSession {
        _sessions.value[peerDeviceId]?.let { if (!it.state.value.isTerminal) return it }
        return register(newSession(peerDeviceId, offer = null, candidates = emptyList()))
    }

    override fun disconnect(peerDeviceId: String) {
        _sessions.value[peerDeviceId]?.close()
    }

    @Synchronized
    override fun acceptOffer(offer: IncomingSignal.Offer, candidates: List<CandidatePayload>): PeerSession {
        _sessions.value[offer.from]?.close()
        val session = register(newSession(offer.from, offer, candidates))
        _accepted.tryEmit(session)
        return session
    }

    private fun newSession(
        peer: String,
        offer: IncomingSignal.Offer?,
        candidates: List<CandidatePayload>,
    ): DefaultPeerSession {
        val self = (signaling.state.value as? SignalingState.Ready)?.deviceId
        val session = DefaultPeerSession(
            peerDeviceId = peer,
            selfDeviceId = self.orEmpty(),
            signaling = signaling,
            factory = factory,
            scope = scope,
            timeoutMillis = timeoutMillis,
            iceDisconnectedGraceMillis = iceDisconnectedGraceMillis,
            remoteOffer = offer,
            initialRemoteCandidates = candidates,
        )
        if (self == null) session.abort(PeerFailure.SignalingUnavailable) else session.start()
        return session
    }

    private fun register(session: DefaultPeerSession): DefaultPeerSession {
        _sessions.update { it + (session.peerDeviceId to session) }
        return session
    }

    private suspend fun handle(signal: IncomingSignal) {
        when (signal) {
            is IncomingSignal.Offer -> handleOffer(signal)
            is IncomingSignal.Answer -> route(signal.from, signal)
            is IncomingSignal.Candidate -> route(signal.from, signal)
            is IncomingSignal.Reject -> route(signal.from, signal)
            is IncomingSignal.ServerError -> {
                val to = signal.to
                if (signal.code == ErrorCodes.PEER_OFFLINE && to != null) route(to, signal)
            }
            is IncomingSignal.Ready -> Unit
        }
    }

    private suspend fun handleOffer(offer: IncomingSignal.Offer) {
        // 自分も offer を出している最中なら、衝突の解決は session が決める
        val existing = _sessions.value[offer.from] as? DefaultPeerSession
        if (existing != null && existing.awaitingAnswer && !existing.state.value.isTerminal) {
            existing.onSignal(offer)
            return
        }
        if (contactRepository.getContact(offer.from) == null) {
            unknownOfferHandler.onOffer(offer)
            return
        }
        acceptOffer(offer)
    }

    /** 相手の session が無い、または終わっているなら、何もしない（candidate など）。 */
    private fun route(peer: String, signal: IncomingSignal) {
        (_sessions.value[peer] as? DefaultPeerSession)?.takeIf { !it.state.value.isTerminal }?.onSignal(signal)
    }
}
