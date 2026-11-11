package com.p2pchat.app.data.device

import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ViewModel のテスト用の偽物。[register] の呼び出しを記録し、結果を差し替えられる。
 * [gate] を渡すと、それが完了するまで [register] が止まる（登録中の状態や二重タップを試すため）。
 */
class FakeDeviceRepository(
    initial: DeviceIdentity? = null,
    var result: Result<DeviceIdentity> = Result.success(DeviceIdentity("newdevice00000001", "newtoken")),
    var gate: CompletableDeferred<Unit>? = null,
    /** 設定すると、register がこの例外を投げる（想定外の例外のテスト用）。 */
    var throwable: Throwable? = null,
) : DeviceRepository {
    override val identity = MutableStateFlow(initial)

    /** register に渡された (接続先, 名前)。 */
    val registerCalls = mutableListOf<Pair<ServerSettings, String>>()

    override suspend fun register(server: ServerSettings, name: String): Result<DeviceIdentity> {
        registerCalls += server to name
        gate?.await()
        throwable?.let { throw it }
        result.onSuccess { identity.value = it }
        return result
    }
}
