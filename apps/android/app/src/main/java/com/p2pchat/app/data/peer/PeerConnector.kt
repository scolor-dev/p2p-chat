package com.p2pchat.app.data.peer

import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.IncomingSignal
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 相手との P2P 接続を作る・受け入れる・切る。シグナリング（#20 の [com.p2pchat.app.data.signaling.SignalingClient]）の上で動く。
 *
 * 暗号化・msg / ack・再接続・知らない相手への確認は、ここではやらない（#26 / #29 / #27）。
 */
interface PeerConnector {
    /** 相手の deviceId ごとの、いちばん新しい [PeerSession]。終わった session も、次の接続で置き換わるまで残る。 */
    val sessions: StateFlow<Map<String, PeerSession>>

    /** 相手からの offer を受け入れて作った [PeerSession]（連絡先にいる相手のみ）。 */
    val acceptedSessions: SharedFlow<PeerSession>

    /** シグナリングの受信を始める。アプリの起動時に 1 回呼ぶ。2 回目以降は何もしない。 */
    fun start()

    /**
     * [peerDeviceId] に話しかける（offer を送る）。すぐに [PeerState.Connecting] の session を返す。
     * 接続中・open の session があればそれを返す。終わった session があれば新しく作る。
     * シグナリングが `ready` でなければ、[PeerFailure.SignalingUnavailable] で失敗した session を返す。
     */
    fun connect(peerDeviceId: String): PeerSession

    /** [peerDeviceId] との接続を切る。無ければ何もしない。 */
    fun disconnect(peerDeviceId: String)

    /**
     * 連絡先の確認をせずに offer を受け入れる。#27 が、知らない相手を許可したあとに使う。
     * [candidates] は、確認を待っている間に溜めていた、その相手の candidate。
     */
    fun acceptOffer(offer: IncomingSignal.Offer, candidates: List<CandidatePayload> = emptyList()): PeerSession
}

/**
 * 連絡先にいない相手から offer が届いたときの扱い。既定は何もしない（無視）。
 * #27 は、ここで offer を保留して確認を出し、許可されたら [PeerConnector.acceptOffer] を呼ぶ。
 * 保留中に届く candidate は渡されない（#27 が [com.p2pchat.app.data.signaling.SignalingClient.incoming] から集める）。
 */
fun interface UnknownOfferHandler {
    suspend fun onOffer(offer: IncomingSignal.Offer)

    companion object {
        val Ignore = UnknownOfferHandler { }
    }
}
