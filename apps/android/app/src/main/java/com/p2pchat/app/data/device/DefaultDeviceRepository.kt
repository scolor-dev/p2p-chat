package com.p2pchat.app.data.device

import com.p2pchat.app.data.crypto.DeviceKeyStore
import com.p2pchat.app.data.crypto.PublicKeyEncoder
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 鍵の準備 → `POST /devices` → 保存、を順に行う。
 *
 * 鍵ペアは冪等に作る。登録が途中で失敗しても、次の登録では同じ鍵を使う。
 */
class DefaultDeviceRepository(
    private val keyStore: DeviceKeyStore,
    private val apiClient: ApiClient,
    private val identityStore: DeviceIdentityStore,
) : DeviceRepository {

    /** 登録を同時に 2 つ走らせない（二重登録で deviceId が 2 つできるのを防ぐ）。 */
    private val registerLock = Mutex()

    override val identity: Flow<DeviceIdentity?> get() = identityStore.identity

    override suspend fun register(server: ServerSettings, name: String): Result<DeviceIdentity> =
        registerLock.withLock {
            identityStore.identity.first()?.let { return@withLock Result.success(it) }

            val publicKey = try {
                PublicKeyEncoder.toBase64(keyStore.getOrCreatePublicKey())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock Result.failure(RegistrationException.KeyFailed(e))
            }

            val registered = apiClient.registerDevice(server, name, publicKey).getOrElse { e ->
                if (e is CancellationException) throw e
                return@withLock Result.failure(
                    if (e is ApiException) RegistrationException.Rejected(e.status, e.code, e.serverMessage)
                    else RegistrationException.Network(e),
                )
            }

            val identity = DeviceIdentity(registered.deviceId, registered.token)
            try {
                identityStore.save(identity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock Result.failure(RegistrationException.StorageFailed(e))
            }
            Result.success(identity)
        }
}
