package com.p2pchat.app.service

/**
 * 背面でプロセスを保つためのフォアグラウンドサービスの入口。Android の Service を直接触らないための境界。
 * テストでは Fake に差し替える。
 */
interface ForegroundServiceLauncher {
    /** サービスの開始を要求する。開始が許されない・失敗したときは例外ではなく false を返す。 */
    fun start(): Boolean

    /** サービスを止める。動いていなくても安全。 */
    fun stop()
}
