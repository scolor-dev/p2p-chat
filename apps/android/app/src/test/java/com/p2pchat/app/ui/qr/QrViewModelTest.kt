package com.p2pchat.app.ui.qr

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.crypto.DeviceKeyStore
import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.FakeDeviceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.security.PrivateKey
import java.security.PublicKey

class QrViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakePublicKey(private val der: ByteArray) : PublicKey {
        override fun getAlgorithm() = "EC"
        override fun getFormat() = "X.509"
        override fun getEncoded() = der
    }

    private class FakeDeviceKeyStore(private val key: () -> PublicKey) : DeviceKeyStore {
        override fun getOrCreatePublicKey(): PublicKey = key()
        override fun privateKey(): PrivateKey = error("QR では使わない")
    }

    private val keyStore = FakeDeviceKeyStore { FakePublicKey("abc".toByteArray()) }

    private fun viewModel(
        device: FakeDeviceRepository = FakeDeviceRepository(initial = DeviceIdentity("a1b2c3d4e5f60718", "tok")),
        keys: DeviceKeyStore = keyStore,
    ) = QrViewModel(device, keys, UnconfinedTestDispatcher())

    @Test
    fun 登録済みならQRに入れるURLができる() = runTest {
        val state = viewModel().uiState.first { !it.isLoading }

        assertEquals("a1b2c3d4e5f60718", state.deviceId)
        assertEquals(
            "p2pchat://contact?id=a1b2c3d4e5f60718&fp=ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0",
            state.contactUrl,
        )
        assertNull(state.error)
    }

    @Test
    fun 未登録ならNotRegisteredで鍵は触らない() = runTest {
        var touched = false
        val vm = viewModel(
            device = FakeDeviceRepository(initial = null),
            keys = FakeDeviceKeyStore { touched = true; FakePublicKey(ByteArray(1)) },
        )

        val state = vm.uiState.first { !it.isLoading }

        assertEquals(QrError.NotRegistered, state.error)
        assertNull(state.contactUrl)
        assertEquals(false, touched)
    }

    @Test
    fun 鍵を取り出せなければFailedで例外の中身は出さない() = runTest {
        val vm = viewModel(keys = FakeDeviceKeyStore { throw IllegalStateException("secret detail") })

        val state = vm.uiState.first { !it.isLoading }

        assertEquals(QrError.Failed, state.error)
        assertNull(state.contactUrl)
    }
}
