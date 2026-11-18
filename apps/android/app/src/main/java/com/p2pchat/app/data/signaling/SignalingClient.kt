package com.p2pchat.app.data.signaling

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * シグナリングサーバーとの WebSocket 接続を、つないだままに保つ。
 *
 * [start] してから [stop] するまで、`Authorization: Bearer <token>` を付けて接続し、`ready` を待つ。
 * 切れたら待ち時間を延ばしながら再接続し、ネットワークが切り替わったらすぐにつなぎ直す。
 *
 * offer / answer の中身を作る・処理するのは、ここではなく #25 以降。ここは流すだけ。
 */
interface SignalingClient {
    val state: StateFlow<SignalingState>

    /** サーバーから届いたメッセージ（`ready` 以外）。購読していない間に届いたものは捨てる。 */
    val incoming: SharedFlow<IncomingSignal>

    /**
     * メッセージを送る。`ready` の状態で、送信の予約ができたら true。
     * まだ `ready` でない・切断中などで送れないときは false（溜めて後で送ることはしない）。
     */
    fun send(signal: OutgoingSignal): Boolean

    /** 接続を始める（アプリが前面に来たとき）。動いている間に呼んでも何もしない。 */
    fun start()

    /** 切断して止める（アプリが背面に回ったとき）。 */
    fun stop()
}
