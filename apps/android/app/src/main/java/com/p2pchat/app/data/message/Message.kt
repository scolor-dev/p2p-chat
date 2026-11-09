package com.p2pchat.app.data.message

/** 送受信の方向 */
enum class MessageDirection {
    SENT,/**送信（自分が送ったメッセージ）*/
    RECEIVED,/**受信（相手から受け取ったメッセージ）*/
}

/** 送信状態 */
enum class MessageStatus {
    SENDING,/**送信中*/
    SENT,/**送信済み*/
    FAILED,/**送信失敗*/
}

/** 画面やドメイン層で使うメッセージ。 */
data class Message(
    val id: String,/**メッセージのID*/
    val deviceId: String,/**相手のメッセージのID*/
    val direction: MessageDirection,
    val body: String,/**メッセージの本文*/
    val status: MessageStatus,/**送信状態*/
    val sentAt: Long,/**送信日時*/
)