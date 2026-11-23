package com.p2pchat.app.data.message

/**
 * チャット画面から呼ばれる「メッセージを相手に送る」処理の境界。
 *
 * ViewModel はこの interface だけを知る。実体（P2P の DataChannel で暗号化して送り、
 * 送信したメッセージを [MessageRepository] に保存する処理）は #26 後半で差し込む。
 *
 * - [body] は ViewModel が「空白だけでない・UTF-8 で 4096 バイト以内」を確かめた本文。
 *   制御文字の除去など、送信直前の整形は実装側で行い、整形後にもう一度長さを確かめること。
 * - 送ったメッセージの保存（[MessageRepository.upsertMessage]）と送信状態は実装側が扱う。
 *   画面は [MessageRepository.observeMessages] を見ているので、保存すれば自動で表示される。
 * - 成功なら [Result.success]、送れなかったなら [Result.failure]。失敗時、画面は入力欄の文字を残し、
 *   エラーを 1 行出す（例外のメッセージは画面に出さない）。
 */
interface ChatMessageSender {
    suspend fun send(deviceId: String, body: String): Result<Unit>
}

/** 送信の実体ができるまでの既定実装。常に「接続されていない」として失敗を返す。 */
class NotConnectedChatMessageSender : ChatMessageSender {
    override suspend fun send(deviceId: String, body: String): Result<Unit> =
        Result.failure(IllegalStateException("not connected"))
}
