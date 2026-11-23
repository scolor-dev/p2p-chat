package com.p2pchat.app.data.message

/** 送受信の方向。 */
enum class MessageDirection {
    /** 送信（自分が送ったメッセージ）。 */
    SENT,

    /** 受信（相手から受け取ったメッセージ）。 */
    RECEIVED,
}

/** 送信状態。 */
enum class MessageStatus {
    /** 送信中。 */
    SENDING,

    /** 送信済み。 */
    SENT,

    /** 送信失敗。 */
    FAILED,
}

/**
 * 画面やドメイン層で使うメッセージ。
 *
 * @property id メッセージの ID。同じ ID を 2 回保存しても 1 件になる
 * @property deviceId やり取りの相手の端末 ID（送信・受信のどちらでも相手の側）
 * @property direction 送信か受信か
 * @property body メッセージの本文
 * @property status 送信状態
 * @property sentAt 送信日時（エポックミリ秒）
 */
data class Message(
    val id: String,
    val deviceId: String,
    val direction: MessageDirection,
    val body: String,
    val status: MessageStatus,
    val sentAt: Long,
)
