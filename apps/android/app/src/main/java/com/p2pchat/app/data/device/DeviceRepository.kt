package com.p2pchat.app.data.device

import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.flow.Flow

/** この端末の身元（登録済みかどうか、`deviceId`）と、端末登録の窓口。ViewModel はこの interface だけを知る。 */
interface DeviceRepository {
    /** 登録済みならその身元。未登録なら null。 */
    val identity: Flow<DeviceIdentity?>

    /**
     * [server] に端末を登録し、`deviceId` と `token` を保存する。
     *
     * すでに登録済みなら、通信も鍵の生成もせず、保存済みの身元を返す（2 回目以降は登録しない）。
     * 保存できなかったときは、登録済みとは見なさず [RegistrationException.StorageFailed] を返す。
     *
     * @param name 端末の名前。前後の空白を除いて 1〜64 文字（呼び出し側で検証する）
     */
    suspend fun register(server: ServerSettings, name: String): Result<DeviceIdentity>
}

/** 登録に失敗した理由。メッセージには、秘密鍵・token・公開鍵を入れない。 */
sealed class RegistrationException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 鍵を作れなかった、または取り出せなかった。 */
    class KeyFailed(cause: Throwable) : RegistrationException("鍵を準備できませんでした", cause)

    /** サーバーに届かない・タイムアウト・応答が読めない。 */
    class Network(cause: Throwable) : RegistrationException("サーバーに接続できませんでした", cause)

    /** サーバーがエラーを返した（400 など）。 */
    class Rejected(val status: Int, val code: String?, val serverMessage: String?) :
        RegistrationException("サーバーに拒否されました (HTTP $status)")

    /**
     * サーバーへの登録は済んだが、端末への保存に失敗した。
     * token は保存できなかった分が失われるので、登録済みとは見なさない（もう一度登録すると新しい deviceId になる）。
     */
    class StorageFailed(cause: Throwable) : RegistrationException("端末への保存に失敗しました", cause)
}
