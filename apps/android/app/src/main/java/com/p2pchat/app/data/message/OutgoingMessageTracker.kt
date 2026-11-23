package com.p2pchat.app.data.message

/**
 * 送信したメッセージの状態を動かす場所。「送信を始めた」「ack を受けた」「送れなかった」の扱いはここに集める。
 *
 * #26 の既定実装は [DefaultOutgoingMessageTracker]（SENDING で保存 → ack で SENT / 失敗で FAILED）。
 * #28 は、ここを差し替える・包むことで、10 秒の ack タイムアウト（FAILED）や手動再送（同じ id で再び
 * [onSendStarted]）を足す。送受信の実体（[P2PChatMessageSender]、[IncomingMessageHandler]）は変えずに済む。
 */
interface OutgoingMessageTracker {
    /** 送信を始めた。[message]（status = SENDING）を保存する。 */
    suspend fun onSendStarted(message: Message)

    /** [deviceId] の相手から [id] の `ack` が届いた。 */
    suspend fun onAckReceived(deviceId: String, id: String)

    /** [id] を送れなかった（暗号化の失敗、DataChannel に載せられなかった等）。 */
    suspend fun onSendFailed(id: String)
}

class DefaultOutgoingMessageTracker(
    private val messageRepository: MessageRepository,
) : OutgoingMessageTracker {
    override suspend fun onSendStarted(message: Message) {
        messageRepository.upsertMessage(message)
    }

    override suspend fun onAckReceived(deviceId: String, id: String) {
        val message = messageRepository.getMessage(id) ?: return
        // 自分が、その相手に送ったメッセージの ack だけを受け付ける（他人の id や受信分は無視）。
        if (message.deviceId != deviceId || message.direction != MessageDirection.SENT) return
        if (message.status != MessageStatus.SENT) messageRepository.updateStatus(id, MessageStatus.SENT)
    }

    override suspend fun onSendFailed(id: String) {
        messageRepository.updateStatus(id, MessageStatus.FAILED)
    }
}
