package com.p2pchat.app.data.network

import com.p2pchat.app.data.settings.ServerSettings

/**
 * signaling サーバーの HTTP API。
 *
 * 今は接続の確認（`GET /healthz`）だけ。端末登録（`POST /devices`）などは、各 Issue でここに足す。
 */
interface ApiClient {
    /** [server] の `GET /healthz` を呼び、200 が返れば成功。失敗の理由は例外で返す。 */
    suspend fun healthz(server: ServerSettings): Result<Unit>
}
