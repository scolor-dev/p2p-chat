package com.p2pchat.app.data.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * シグナリングの WebSocket で送受信するメッセージ。形式は `docs/design.md` の「6-1」「6-3」を正とする。
 * すべて JSON テキストで、`type` で種類を見分ける。
 */

/** offer / answer の payload。 */
@Serializable
data class SdpPayload(val sdp: String)

/**
 * candidate の payload。
 *
 * [sdpMLineIndex] は既定値を持たせない。`Json` は既定値と同じ値を送らないので、
 * 既定値があると `0` のときに項目ごと消え、他のアプリ（iOS など）が読めなくなる。
 */
@Serializable
data class CandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int,
)

/** サーバーから届くメッセージ。 */
@Serializable
sealed interface IncomingSignal {
    /** 接続と認証が完了した。これを受け取るまで、メッセージは送らない。 */
    @Serializable
    @SerialName("ready")
    data class Ready(val deviceId: String) : IncomingSignal

    @Serializable
    @SerialName("offer")
    data class Offer(val from: String, val payload: SdpPayload) : IncomingSignal

    @Serializable
    @SerialName("answer")
    data class Answer(val from: String, val payload: SdpPayload) : IncomingSignal

    @Serializable
    @SerialName("candidate")
    data class Candidate(val from: String, val payload: CandidatePayload) : IncomingSignal

    /** 相手が接続の申請を拒否した。payload はない。 */
    @Serializable
    @SerialName("reject")
    data class Reject(val from: String) : IncomingSignal

    /**
     * サーバーからのエラー。[code] は [ErrorCodes] のどれか。
     * [to] には、取り次ぎに失敗した宛先が入る（`peer-offline` のとき）。
     */
    @Serializable
    @SerialName("error")
    data class ServerError(val code: String, val message: String = "", val to: String? = null) : IncomingSignal
}

/** こちらから送るメッセージ。宛先は [to] の deviceId。 */
@Serializable
sealed interface OutgoingSignal {
    val to: String

    @Serializable
    @SerialName("offer")
    data class Offer(override val to: String, val payload: SdpPayload) : OutgoingSignal

    @Serializable
    @SerialName("answer")
    data class Answer(override val to: String, val payload: SdpPayload) : OutgoingSignal

    @Serializable
    @SerialName("candidate")
    data class Candidate(override val to: String, val payload: CandidatePayload) : OutgoingSignal

    /** 接続の申請を拒否する。payload はない。 */
    @Serializable
    @SerialName("reject")
    data class Reject(override val to: String) : OutgoingSignal
}

/** `error` の `code`。 */
object ErrorCodes {
    /** 不正なメッセージ（未知の `type`、`to` がない、自分宛て）。 */
    const val BAD_MESSAGE = "bad-message"

    /** 宛先の端末が WebSocket に接続していない。 */
    const val PEER_OFFLINE = "peer-offline"
}

/** メッセージと JSON の変換。 */
object SignalingCodec {
    private val json = Json {
        classDiscriminator = "type"
        // サーバーが項目を足しても読めるように、知らない項目は無視する
        ignoreUnknownKeys = true
        // sdpMid が null のときは、項目ごと送らない
        explicitNulls = false
    }

    /** 受信した JSON を読む。知らない `type` や壊れた JSON は null（呼び出し側で無視する）。 */
    fun decode(text: String): IncomingSignal? = try {
        json.decodeFromString<IncomingSignal>(text)
    } catch (e: IllegalArgumentException) {
        // SerializationException も IllegalArgumentException の仲間
        null
    }

    fun encode(signal: OutgoingSignal): String = json.encodeToString<OutgoingSignal>(signal)
}
