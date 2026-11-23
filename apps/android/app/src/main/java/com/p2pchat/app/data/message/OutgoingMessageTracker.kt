package com.p2pchat.app.data.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 送信したメッセージの状態を動かす場所。「送信を始めた」「ack を受けた」「送れなかった」の扱いはここに集める。
 *
 * 実装は [DefaultOutgoingMessageTracker]（SENDING で保存 → ack で SENT / 失敗・ack が [ACK_TIMEOUT_MILLIS] 来なければ FAILED）。
 * 手動再送は、同じ id で再び [onSendStarted] を呼ぶ（[P2PChatMessageSender.resend]）。
 */
interface OutgoingMessageTracker {
    /** 送信を始めた。[message]（status = SENDING）を保存する。 */
    suspend fun onSendStarted(message: Message)

    /** [deviceId] の相手から [id] の `ack` が届いた。 */
    suspend fun onAckReceived(deviceId: String, id: String)

    /** [id] を送れなかった（暗号化の失敗、DataChannel に載せられなかった等）。 */
    suspend fun onSendFailed(id: String)

    /**
     * アプリの起動時に 1 回呼ぶ。前回のプロセスで SENDING のまま残ったメッセージは、ack を待つ処理がもう無いので FAILED にする
     * （画面に「送信中」が出続けないように。ユーザーは手動で再送できる）。既定は何もしない。
     */
    suspend fun failInterruptedSending() = Unit
}

/** 送信から `ack` が届くまでの待ち時間の上限。設計書「送信状態」。 */
const val ACK_TIMEOUT_MILLIS = 10_000L


/**
 * [OutgoingMessageTracker] の本実装。[onSendStarted] で [ackTimeoutMillis] のタイマーを始め、`ack` で止める。
 * 時間切れで、まだ SENDING なら FAILED にする（時間は [scope] の delay なので、テストでは仮想時間で動かせる）。
 *
 * タイマーはメモリ上にしか無い。アプリが終了すると消えるので、残った SENDING は次の起動で [failInterruptedSending] が FAILED にする。
 * FAILED になったあとに遅れて `ack` が届いた場合は、届いているので SENT にする。
 */
class DefaultOutgoingMessageTracker(
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
    private val ackTimeoutMillis: Long = ACK_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : OutgoingMessageTracker {
    private val timers = HashMap<String, Job>()

    override suspend fun onSendStarted(message: Message) {
        messageRepository.upsertMessage(message)
        startTimer(message.id)
    }

    override suspend fun onAckReceived(deviceId: String, id: String) {
        val message = messageRepository.getMessage(id) ?: return
        // 自分が、その相手に送ったメッセージの ack だけを受け付ける（他人の id や受信分は無視）。
        if (message.deviceId != deviceId || message.direction != MessageDirection.SENT) return
        stopTimer(id)
        if (message.status != MessageStatus.SENT) messageRepository.updateStatus(id, MessageStatus.SENT)
    }

    override suspend fun onSendFailed(id: String) {
        stopTimer(id)
        messageRepository.updateStatus(id, MessageStatus.FAILED)
    }

    override suspend fun failInterruptedSending() {
        messageRepository.failSendingBefore(clock())
    }

    private fun startTimer(id: String) {
        val job = scope.launch {
            delay(ackTimeoutMillis)
            // ack と競ったときのために、まだ SENDING のときだけ FAILED にする。
            if (messageRepository.getMessage(id)?.status == MessageStatus.SENDING) {
                messageRepository.updateStatus(id, MessageStatus.FAILED)
            }
            synchronized(timers) { if (timers[id] === coroutineContext[Job]) timers.remove(id) }
        }
        synchronized(timers) { timers.put(id, job) }?.cancel()
    }

    private fun stopTimer(id: String) {
        synchronized(timers) { timers.remove(id) }?.cancel()
    }
}
