package com.p2pchat.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.p2pchat.app.di.AppContainer

/**
 * アプリ全体で 1 つだけ作られる Application。
 * 部品（DB・Repository など）は [AppContainer] が持ち、ViewModel の生成時にここから取り出す。
 */
class P2PChatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 相手からの offer を取りこぼさないように、起動時から受信を始める（libwebrtc は最初の接続まで読み込まない）
        container.peerConnector.start()

        // シグナリングは、アプリが前面にある間だけつなぐ（docs/design.md 5-7。MVP は背面では動かさない）。
        // 画面の回転では止まらないように、Activity ではなくプロセス全体の前面・背面を見る。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.signalingClient.start()
            override fun onStop(owner: LifecycleOwner) = container.signalingClient.stop()
        })
    }
}
