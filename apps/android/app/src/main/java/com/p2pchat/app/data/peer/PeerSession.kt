package com.p2pchat.app.data.peer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** DataChannel の label。`docs/design.md` の「6」で `chat` と決まっている。 */
const val DATA_CHANNEL_LABEL = "chat"

/** 接続を試みてから open になるまでの上限（設計書 5-4 の 60 秒）。 */
const val CONNECT_TIMEOUT_MILLIS = 60_000L

/**
 * 相手 1 人との P2P 接続 1 回分。使い捨てで、[PeerState.Disconnected] か [PeerState.Failed] になったら終わり。
 * つなぎ直すときは [PeerConnector.connect] で新しい [PeerSession] を作る。
 */
interface PeerSession {
    val peerDeviceId: String

    val state: StateFlow<PeerState>

    /**
     * DataChannel で届いた文字列フレーム。[PeerState.Open] になってから流れる。
     * 購読していない間に届いたものは捨てる（session を受け取ったらすぐ購読すること）。
     */
    val incoming: Flow<String>

    /** 文字列を 1 フレームで送る。[PeerState.Open] でなければ、または送信を予約できなければ false。 */
    fun send(text: String): Boolean

    /** 自分から切る。[PeerState.Disconnected] になる。すでに終わっていれば何もしない。 */
    fun close()
}

/** [PeerSession] の状態。表示する文言は画面側（strings.xml）で決める。 */
sealed interface PeerState {
    /** offer / answer / candidate をやりとりしている。 */
    data object Connecting : PeerState

    /** DataChannel が open。[PeerSession.send] で送れる。 */
    data object Open : PeerState

    /** open だった接続が切れた、または自分から [PeerSession.close] した。 */
    data object Disconnected : PeerState

    /** open になる前に失敗した。 */
    data class Failed(val reason: PeerFailure) : PeerState
}

enum class PeerFailure {
    /** 宛先が WebSocket につながっていない（`peer-offline`）。「オンラインではありません」。 */
    PeerOffline,

    /** 相手が接続の申請を拒否した（`reject`）。「接続を拒否されました」。 */
    Rejected,

    /** [CONNECT_TIMEOUT_MILLIS] 以内に open にならなかった。「応答がありません」。 */
    Timeout,

    /** シグナリングが `ready` でなく、offer / answer を送れなかった。 */
    SignalingUnavailable,

    /** ICE が失敗した、または SDP の処理に失敗した。 */
    ConnectionFailed,
}

val PeerState.isTerminal: Boolean get() = this is PeerState.Disconnected || this is PeerState.Failed
