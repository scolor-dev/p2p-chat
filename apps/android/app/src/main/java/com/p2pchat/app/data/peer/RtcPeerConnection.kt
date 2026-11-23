package com.p2pchat.app.data.peer

import com.p2pchat.app.data.signaling.CandidatePayload

/**
 * libwebrtc の PeerConnection を、ユニットテストで偽物に差し替えられる形で包んだもの。
 * SDP の中身は文字列のまま扱う。実体は [WebRtcPeerConnection]。
 */
interface RtcPeerConnection {
    /** DataChannel `chat` を作り、offer を作って setLocalDescription する。offer の SDP を返す。 */
    suspend fun createOffer(): String

    /** 相手の offer を setRemoteDescription し、answer を作って setLocalDescription する。answer の SDP を返す。 */
    suspend fun acceptOffer(sdp: String): String

    /** 相手の answer を setRemoteDescription する。 */
    suspend fun acceptAnswer(sdp: String)

    /** setRemoteDescription のあとで呼ぶ。 */
    suspend fun addRemoteCandidate(candidate: CandidatePayload)

    fun send(text: String): Boolean

    fun close()
}

/** [RtcPeerConnection] から、状態の変化を受け取る。どのスレッドからでも呼ばれうる。 */
interface RtcListener {
    fun onLocalCandidate(candidate: CandidatePayload)
    fun onConnectionState(state: RtcConnectionState)
    fun onChannelOpen()
    fun onChannelClosed()
    fun onMessage(text: String)
}

/**
 * ICE の接続状態のうち、このアプリで区別するもの。
 * [Disconnected] は一時的な不通。すぐ [Connected] に戻ることがあるので、[DefaultPeerSession] が少し待ってから切断とみなす。
 */
enum class RtcConnectionState { Connected, Disconnected, Failed, Closed, Other }

fun interface RtcPeerConnectionFactory {
    fun create(listener: RtcListener): RtcPeerConnection
}
