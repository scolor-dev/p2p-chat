package com.p2pchat.app.data.network

import com.p2pchat.app.data.settings.ServerSettings

/**
 * signaling サーバーの HTTP API。
 *
 * 接続の確認（`GET /healthz`）、端末の登録（`POST /devices`）、端末の取得（`GET /devices/{id}`）。
 */
interface ApiClient {
    /** [server] の `GET /healthz` を呼び、200 が返れば成功。失敗の理由は例外で返す。 */
    suspend fun healthz(server: ServerSettings): Result<Unit>

    /**
     * [server] の `POST /devices` を呼んで端末を登録する。
     *
     * 失敗の理由は例外で返す。サーバーが 400 などのエラーを返したときは [ApiException]、
     * 到達できない・タイムアウト・応答が読めないときは [java.io.IOException]。
     *
     * @param publicKey X.509（DER）を base64 にした公開鍵
     */
    suspend fun registerDevice(server: ServerSettings, name: String, publicKey: String): Result<RegisteredDevice>

    /**
     * [server] の `GET /devices/{id}` を呼んで、相手の端末の名前と公開鍵を取得する。
     *
     * 存在しない `deviceId` なら [ApiException]（status 404）。ほかの失敗は [registerDevice] と同じ。
     */
    suspend fun getDevice(server: ServerSettings, deviceId: String): Result<RemoteDevice>
}

/** `GET /devices/{id}` の結果。[publicKey] は X.509（DER）を base64 にした値。 */
data class RemoteDevice(val deviceId: String, val name: String, val publicKey: String)

/**
 * 登録の結果（`201`）。[token] は**このレスポンスでしか返らない**ので、受け取ったらすぐ保存する。
 * [toString] には token を出さない（ログや例外メッセージに混ざらないように）。
 */
data class RegisteredDevice(val deviceId: String, val token: String) {
    override fun toString(): String = "RegisteredDevice(deviceId=$deviceId, token=***)"
}

/** サーバーが返したエラー（`{ code, message }`）。[status] は HTTP の状態コード。 */
class ApiException(val status: Int, val code: String?, val serverMessage: String?) :
    Exception("HTTP $status${code?.let { " ($it)" }.orEmpty()}")
