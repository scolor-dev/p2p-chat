package com.p2pchat.app.service

import android.content.Context
import android.content.Intent

/** [BackgroundConnectionService] を開始・停止する実装。例外は握りつぶして false にする（クラッシュさせない）。 */
class AndroidForegroundServiceLauncher(private val context: Context) : ForegroundServiceLauncher {
    override fun start(): Boolean = try {
        // 背面からの開始が許されないと ForegroundServiceStartNotAllowedException（IllegalStateException）になる
        context.startForegroundService(Intent(context, BackgroundConnectionService::class.java))
        true
    } catch (e: RuntimeException) {
        false
    }

    override fun stop() {
        try {
            context.stopService(Intent(context, BackgroundConnectionService::class.java))
        } catch (e: RuntimeException) {
            // 止められなくても影響はない
        }
    }
}
