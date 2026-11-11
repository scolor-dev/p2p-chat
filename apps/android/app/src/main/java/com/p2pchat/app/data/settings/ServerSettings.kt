package com.p2pchat.app.data.settings

/**
 * 接続先の signaling サーバー。ユーザーが設定画面で入力する。
 *
 * 他の Issue（端末登録、WebSocket など）は、宛先の URL をここから組み立てる。
 */
data class ServerSettings(
    val host: String,
    val port: Int = DEFAULT_PORT,
) {
    /** HTTP API のベース URL（例: `http://192.168.1.10:8080`）。 */
    val httpBaseUrl: String get() = "http://$host:$port"

    /** シグナリングの WebSocket の URL（例: `ws://192.168.1.10:8080/ws`）。 */
    val webSocketUrl: String get() = "ws://$host:$port/ws"

    companion object {
        /** サーバーの既定のポート（`services/signaling` の `SIGNALING_ADDR` の初期値）。 */
        const val DEFAULT_PORT = 8080
    }
}
