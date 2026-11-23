package com.p2pchat.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.p2pchat.app.MainActivity
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.R

/**
 * 背面でもプロセスを保つだけの薄いフォアグラウンドサービス。通信そのものは AppContainer の部品が行い、
 * いつ開始・停止するかは [BackgroundConnectionController] が決める。
 *
 * 種別は `remoteMessaging`（端末間のテキストメッセージの転送向け。`dataSync` と違い Android 15 の時間制限が無い）。
 */
class BackgroundConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } catch (e: RuntimeException) {
            // 開始が許されない・権限の不足など。前面のみの動作に落とす。
            giveUp()
        }
        // OS に止められたあとに勝手に再開しない（再開は、前面→背面の切り替えで行う）
        return START_NOT_STICKY
    }

    /** Android 15 以降、種別ごとの時間制限に達したときに呼ばれる。時間内に止めないとクラッシュする。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        giveUp()
    }

    private fun giveUp() {
        (application as? P2PChatApp)?.container?.backgroundConnectionController?.onServiceStopped()
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_connection_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_connection_title))
            .setContentText(getString(R.string.notification_connection_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1
    }
}
