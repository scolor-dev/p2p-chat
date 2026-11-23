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
 * **サービスは、アプリが前面にいる間に開始する。** Android 12 以降は、背面になってからのフォアグラウンド
 * サービスの開始が（ほとんどの場合）許されず、例外になるため。前面で開始しておけば、そのまま背面でも動き続ける。
 *
 * - 前面で設定オン: シグナリングを動かし、サービスを開始する（すでに動いていれば何も変わらない）。
 *   開始できなかった（[ForegroundServiceLauncher.start] が false）ときは、前面のみの動作になる。
 * - 前面で設定オフ: シグナリングを動かし、サービスは止める。
 * - 背面に回った: サービスが動いていればシグナリングをそのまま保つ。動いていなければシグナリングを止める
 *   （従来どおり前面のみ）。背面からサービスを開始しようとはしない。
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

    /** サービスが動いている（前面で開始できた）か。 */
    @Volatile
    private var serviceRunning = false

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
        serviceRunning = false
        if (foreground.value == false) signaling.stop()
    }

    private fun apply(enabled: Boolean, isForeground: Boolean) {
        if (isForeground) {
            signaling.start()
            if (enabled) {
                // 前面にいる間なら、サービスを開始できる。開始できなければ、背面では前面のみの動作になる
                serviceRunning = launcher.start()
            } else {
                launcher.stop()
                serviceRunning = false
            }
            return
        }
        // 背面。設定オフ・サービスが動いていないときは、従来どおりシグナリングを止める
        if (!enabled) {
            launcher.stop()
            serviceRunning = false
        }
        if (!enabled || !serviceRunning) signaling.stop()
    }
}
