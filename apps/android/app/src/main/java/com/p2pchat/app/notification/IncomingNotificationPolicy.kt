package com.p2pchat.app.notification

import com.p2pchat.app.data.message.Message
import com.p2pchat.app.data.message.MessageDirection

/** 受信したメッセージを通知するかの判定。Android に依存しない。 */
object IncomingNotificationPolicy {
    /**
     * - 自分が送った分（[MessageDirection.SENT]）は通知しない。
     * - 連絡先にいない相手は通知しない（名前を出せない・信頼できない）。
     * - アプリが前面で、その相手のチャットを開いているときだけ通知しない。
     *   背面なら、チャットを開いたままでも通知する。別の画面・別の相手のチャットを見ているときも通知する。
     */
    fun shouldNotify(
        message: Message,
        isContactKnown: Boolean,
        isAppForeground: Boolean,
        activeChatDeviceId: String?,
    ): Boolean {
        if (message.direction != MessageDirection.RECEIVED) return false
        if (!isContactKnown) return false
        if (isAppForeground && activeChatDeviceId == message.deviceId) return false
        return true
    }
}
