package com.p2pchat.app.notification

/** 受信メッセージの通知を出す・消す窓口。Android の NotificationManager を直接触らないための境界。 */
interface MessageNotifier {
    /** [deviceId] の相手からのメッセージを通知する。同じ相手の通知は 1 つにまとめる。 */
    fun show(deviceId: String, senderName: String, body: String, timestamp: Long)

    /** [deviceId] の相手の通知を消す（チャットを開いたとき）。 */
    fun cancel(deviceId: String)
}
