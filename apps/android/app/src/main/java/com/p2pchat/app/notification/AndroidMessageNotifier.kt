package com.p2pchat.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.p2pchat.app.MainActivity
import com.p2pchat.app.R

/** チャットを開く deep link（通知のタップと Navigation で共有する）。 */
object ChatDeepLink {
    const val BASE = "p2pchat://chat"

    fun uri(deviceId: String): Uri = Uri.parse("$BASE/$deviceId")
}

/**
 * 通知の実装。相手ごとに 1 つの通知（MessagingStyle）を更新してまとめ、全体を 1 つのグループにする。
 *
 * ロック画面には本文を出さない: 通知は VISIBILITY_PRIVATE で、公開用の差し替え（[buildPublic]）は本文も送り主も含めない。
 * 権限（POST_NOTIFICATIONS）が無いときは何もしない（拒否されても動き続ける）。
 */
class AndroidMessageNotifier(private val context: Context) : MessageNotifier {
    private val manager = NotificationManagerCompat.from(context)

    /** 相手ごとの、通知に出している直近のメッセージ（メモリのみ）。 */
    private val histories = HashMap<String, ArrayDeque<Entry>>()

    private data class Entry(val text: String, val timestamp: Long)

    @Synchronized
    override fun show(deviceId: String, senderName: String, body: String, timestamp: Long) {
        if (!canNotify()) return
        ensureChannel()
        val history = histories.getOrPut(deviceId) { ArrayDeque() }
        history.addLast(Entry(body, timestamp))
        while (history.size > MAX_HISTORY) history.removeFirst()

        val sender = Person.Builder().setName(senderName).setKey(deviceId).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.notification_message_you)).build())
        history.forEach { style.addMessage(it.text, it.timestamp, sender) }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(senderName)
            .setContentText(body)
            .setWhen(timestamp)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublic())
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openChatIntent(deviceId))
            .build()
        try {
            manager.notify(deviceId, MESSAGE_NOTIFICATION_ID, notification)
            manager.notify(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID, buildSummary())
        } catch (e: SecurityException) {
            // 権限が取り消された直後など。通知できなくても続ける
        }
    }

    @Synchronized
    override fun cancel(deviceId: String) {
        histories.remove(deviceId)
        manager.cancel(deviceId, MESSAGE_NOTIFICATION_ID)
        if (histories.isEmpty()) manager.cancel(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE }
        manager.createNotificationChannel(channel)
    }

    /** ロック画面に出す、本文も送り主も含まない通知。 */
    private fun buildPublic() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_message_public_text))
        .build()

    private fun buildSummary() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_message_public_text))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(buildPublic())
        .setGroup(GROUP_KEY)
        .setGroupSummary(true)
        .setAutoCancel(true)
        .build()

    private fun openChatIntent(deviceId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, ChatDeepLink.uri(deviceId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            deviceId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val CHANNEL_ID = "messages"
        const val GROUP_KEY = "com.p2pchat.app.MESSAGES"
        const val MESSAGE_NOTIFICATION_ID = 2
        const val SUMMARY_NOTIFICATION_ID = 3
        const val SUMMARY_TAG = "summary"
        const val MAX_HISTORY = 5
    }
}
