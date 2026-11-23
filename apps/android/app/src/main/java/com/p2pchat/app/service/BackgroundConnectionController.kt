package com.p2pchat.app.service

import com.p2pchat.app.data.settings.AppPreferencesRepository
import com.p2pchat.app.data.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * アプリの前面・背面と「バックグラウンドでも通信する」設定から、シグナリングとフォアグラウンドサービスの
 * 開始・停止を決める。Android に依存しない（サービスは [ForegroundServiceLauncher] 越し）。
 *
 * - 前面: シグナリングを動かし、サービスは止める（画面が見えている間は常駐通知が要らない）。
 * - 背面で設定オン: サービスを開始する。開始できたらシグナリングはそのまま保つ。
 *   開始が許されなかった（[ForegroundServiceLauncher.start] が false）ときは、前面のみの動作に落として
 *   シグナリングを止める。
 * - 背面で設定オフ: サービスを止め、シグナリングを止める（従来どおり）。
 * - OS がサービスを止めた（[onServiceStopped]）とき、背面ならシグナリングを止める。
 *
 * P2P の接続（PeerConnector）はシグナリングとは別に、プロセスが生きている間は残る。
 */
class BackgroundConnectionController(
    private val preferences: AppPreferencesRepository,
    private val signaling: SignalingClient,
    private val launcher: ForegroundServiceLauncher,
    private val scope: CoroutineScope,
) {
    /** null は、まだ前面・背面のどちらも通知されていない状態。 */
    private val foreground = MutableStateFlow<Boolean?>(null)
    private val started = AtomicBoolean(false)

    /** 設定と前面・背面の購読を始める。1 回だけ有効。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(preferences.backgroundCommsEnabled, foreground.filterNotNull()) { enabled, fg -> enabled to fg }
                .collect { (enabled, fg) -> apply(enabled, fg) }
        }
    }

    /** アプリが前面に来た。 */
    fun onForeground() {
        foreground.value = true
    }

    /** アプリが背面に回った。 */
    fun onBackground() {
        foreground.value = false
    }

    /** OS の都合（時間制限・開始の拒否など）でサービスが止まった。背面なら前面のみの動作に落とす。 */
    fun onServiceStopped() {
        if (foreground.value == false) signaling.stop()
    }

    private fun apply(enabled: Boolean, isForeground: Boolean) {
        when {
            isForeground -> {
                launcher.stop()
                signaling.start()
            }
            enabled -> {
                // 開始が許されないことがある（Android 12 以降の背面からの開始の制限など）。そのときは前面のみにする。
                if (!launcher.start()) signaling.stop()
            }
            else -> {
                launcher.stop()
                signaling.stop()
            }
        }
    }
}
