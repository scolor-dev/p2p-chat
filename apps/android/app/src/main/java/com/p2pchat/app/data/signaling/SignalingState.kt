package com.p2pchat.app.data.signaling

/** シグナリング（WebSocket）の接続状態。画面の表示と、#25 以降の「ready か」の判断に使う。 */
sealed interface SignalingState {
    /** 動かしていない（アプリが背面にある）。 */
    data object Stopped : SignalingState

    /** 接続先のサーバーが未設定か、端末が未登録なので、つなげない。 */
    data object NotConfigured : SignalingState

    /** 接続して `ready` を待っている。[attempt] は 1 から数える（2 以上は再接続）。 */
    data class Connecting(val attempt: Int) : SignalingState

    /** `ready` を受け取った。メッセージを送れる。 */
    data class Ready(val deviceId: String) : SignalingState

    /**
     * 切断・失敗のあと、再接続まで待っている。
     * [failures] は続けて失敗した回数、[delayMillis] は待ち時間、[reason] は直前の切断・失敗の理由。
     */
    data class WaitingToReconnect(val failures: Int, val delayMillis: Long, val reason: String?) : SignalingState

    /**
     * 接続が `401` で拒否された（token が無効）。同じ token で何度試しても通らないので、
     * ネットワークの切り替え・設定の変更・アプリの再表示があるまで再試行しない。
     */
    data object Unauthorized : SignalingState
}
