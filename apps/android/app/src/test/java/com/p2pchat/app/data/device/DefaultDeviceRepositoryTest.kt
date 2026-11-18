package com.p2pchat.app.data.device

import com.p2pchat.app.data.crypto.DeviceKeyStore
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.network.RegisteredDevice
import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDeviceRepositoryTest {
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private class FakeKeyStore(private val pair: java.security.KeyPair, var fail: Boolean = false) : DeviceKeyStore {
        var calls = 0
        override fun getOrCreatePublicKey(): PublicKey {
            calls++
            if (fail) throw IllegalStateException("鍵を作れない")
            return pair.public
        }

        override fun privateKey(): PrivateKey = pair.private
    }

    private class FakeApiClient(
        var result: Result<RegisteredDevice> = Result.success(RegisteredDevice("dev0000000000001", "tok")),
        /** 設定すると、registerDevice がこれが完了するまで止まる。 */
        var gate: CompletableDeferred<Unit>? = null,
    ) : ApiClient {
        val registerCalls = mutableListOf<Triple<ServerSettings, String, String>>()
        override suspend fun healthz(server: ServerSettings): Result<Unit> = Result.success(Unit)
        override suspend fun registerDevice(server: ServerSettings, name: String, publicKey: String):
            Result<RegisteredDevice> {
            registerCalls += Triple(server, name, publicKey)
            gate?.await()
            return result
        }
    }

    private class FakeIdentityStore(
        initial: DeviceIdentity? = null,
        var failSave: Boolean = false,
        /** 設定すると、save がこれが完了するまで止まる。 */
        var saveGate: CompletableDeferred<Unit>? = null,
    ) : DeviceIdentityStore {
        override val identity = MutableStateFlow(initial)
        override suspend fun save(identity: DeviceIdentity) {
            saveGate?.await()
            if (failSave) throw IOException("書き込めない")
            this.identity.value = identity
        }
    }

    private val server = ServerSettings("192.168.1.10", 8080)

    private fun repository(
        keyStore: FakeKeyStore = FakeKeyStore(pair),
        api: FakeApiClient = FakeApiClient(),
        store: FakeIdentityStore = FakeIdentityStore(),
    ) = DefaultDeviceRepository(keyStore, api, store, UnconfinedTestDispatcher())

    @Test
    fun 登録に成功すると公開鍵を送り身元を保存する() = runTest {
        val api = FakeApiClient()
        val store = FakeIdentityStore()

        val result = repository(api = api, store = store).register(server, "Pixel 8")

        assertEquals(DeviceIdentity("dev0000000000001", "tok"), result.getOrThrow())
        assertEquals(DeviceIdentity("dev0000000000001", "tok"), store.identity.value)
        val (sentServer, sentName, sentKey) = api.registerCalls.single()
        assertEquals(server, sentServer)
        assertEquals("Pixel 8", sentName)
        assertEquals(Base64.getEncoder().encodeToString(pair.public.encoded), sentKey)
    }

    @Test
    fun 登録済みなら鍵も通信も使わず保存済みの身元を返す() = runTest {
        val api = FakeApiClient()
        val keyStore = FakeKeyStore(pair)
        val saved = DeviceIdentity("existing00000001", "oldtoken")

        val result = repository(keyStore, api, FakeIdentityStore(saved)).register(server, "Pixel 8")

        assertEquals(saved, result.getOrThrow())
        assertTrue(api.registerCalls.isEmpty())
        assertEquals(0, keyStore.calls)
    }

    @Test
    fun 鍵を作れなければKeyFailedで通信しない() = runTest {
        val api = FakeApiClient()

        val result = repository(FakeKeyStore(pair, fail = true), api).register(server, "Pixel 8")

        assertTrue(result.exceptionOrNull() is RegistrationException.KeyFailed)
        assertTrue(api.registerCalls.isEmpty())
    }

    @Test
    fun サーバーが400ならRejectedで保存しない() = runTest {
        val store = FakeIdentityStore()
        val api = FakeApiClient(Result.failure(ApiException(400, "bad-request", "name must be 1-64 characters")))

        val result = repository(api = api, store = store).register(server, "x")

        val e = result.exceptionOrNull() as RegistrationException.Rejected
        assertEquals(400, e.status)
        assertEquals("bad-request", e.code)
        assertNull(store.identity.value)
    }

    @Test
    fun 通信に失敗するとNetworkで保存しない() = runTest {
        val store = FakeIdentityStore()
        val api = FakeApiClient(Result.failure(IOException("timeout")))

        val result = repository(api = api, store = store).register(server, "x")

        assertTrue(result.exceptionOrNull() is RegistrationException.Network)
        assertNull(store.identity.value)
    }

    @Test
    fun 保存に失敗するとStorageFailedで登録済みと見なさない() = runTest {
        val store = FakeIdentityStore(failSave = true)
        val repository = repository(store = store)

        val result = repository.register(server, "x")

        assertTrue(result.exceptionOrNull() is RegistrationException.StorageFailed)
        assertNull(repository.identity.first())
    }

    @Test
    fun 例外のメッセージにtokenを含めない() = runTest {
        val store = FakeIdentityStore(failSave = true)
        val api = FakeApiClient(Result.success(RegisteredDevice("dev0000000000001", "secrettoken")))

        val e = repository(api = api, store = store).register(server, "x").exceptionOrNull()!!

        assertTrue(!e.toString().contains("secrettoken"))
        assertTrue(!RegisteredDevice("d", "secrettoken").toString().contains("secrettoken"))
    }

    @Test
    fun 同時に2回呼んでも通信は1回で同じ身元を返す() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApiClient(gate = gate)
        val repository = repository(api = api)

        val first = async { repository.register(server, "Pixel 8") }
        val second = async { repository.register(server, "Pixel 8") }
        // 1 つ目が通信の応答待ちで止まり、2 つ目が同時に登録を始めようとしている状態にする
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(1, api.registerCalls.size)
        assertEquals(first.await().getOrThrow(), second.await().getOrThrow())
    }

    @Test
    fun サーバー応答後に呼び出しがキャンセルされても保存は完了する() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val store = FakeIdentityStore(saveGate = saveGate)
        val repository = repository(store = store)

        val job = launch { repository.register(server, "Pixel 8") }
        testScheduler.runCurrent()
        // ここで save の途中（ゲート待ち）で止まっている
        job.cancel()
        saveGate.complete(Unit)
        job.join()
        testScheduler.advanceUntilIdle()

        assertEquals(DeviceIdentity("dev0000000000001", "tok"), store.identity.value)
    }
}
