package com.p2pchat.app.ui.registration

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.FakeDeviceRepository
import com.p2pchat.app.data.device.RegistrationException
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class RegistrationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeServerSettingsRepository(initial: ServerSettings? = null) : ServerSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: ServerSettings) {
            this.settings.value = settings
        }
    }

    private val server = ServerSettings("192.168.1.10", 8080)

    private fun viewModel(
        device: FakeDeviceRepository = FakeDeviceRepository(),
        settings: FakeServerSettingsRepository = FakeServerSettingsRepository(server),
    ) = RegistrationViewModel(device, settings)

    @Test
    fun 未登録で接続先があれば入力できる状態になる() = runTest {
        val state = viewModel().uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.isServerConfigured)
        assertNull(state.registeredDeviceId)
    }

    @Test
    fun 名前が空なら登録せずエラー() = runTest {
        val device = FakeDeviceRepository()
        val viewModel = viewModel(device)

        viewModel.onNameChange("   ")
        viewModel.register()

        assertEquals(NameError.Empty, viewModel.uiState.value.nameError)
        assertTrue(device.registerCalls.isEmpty())
    }

    @Test
    fun 名前が65文字以上なら登録せずエラー() = runTest {
        val device = FakeDeviceRepository()
        val viewModel = viewModel(device)

        viewModel.onNameChange("a".repeat(65))
        viewModel.register()

        assertEquals(NameError.TooLong, viewModel.uiState.value.nameError)
        assertTrue(device.registerCalls.isEmpty())
    }

    @Test
    fun 名前は文字数で数え64文字ちょうどは登録できる() = runTest {
        val device = FakeDeviceRepository()
        val viewModel = viewModel(device)

        // 絵文字は UTF-16 では 2 単位だが 1 文字として数える
        viewModel.onNameChange("😀".repeat(64))
        viewModel.register()

        assertNull(viewModel.uiState.value.nameError)
        assertEquals(1, device.registerCalls.size)
    }

    @Test
    fun 登録に成功するとdeviceIdが表示される() = runTest {
        val device = FakeDeviceRepository(result = Result.success(DeviceIdentity("a1b2c3d4e5f60718", "tok")))
        val viewModel = viewModel(device)

        viewModel.onNameChange("  Pixel 8  ")
        viewModel.register()

        val state = viewModel.uiState.value
        assertEquals("a1b2c3d4e5f60718", state.registeredDeviceId)
        assertFalse(state.isRegistering)
        assertNull(state.error)
        // 前後の空白は除いて送る
        assertEquals(server to "Pixel 8", device.registerCalls.single())
    }

    @Test
    fun サーバー未設定なら登録できないことが示され呼ばれない() = runTest {
        val device = FakeDeviceRepository()
        val settings = FakeServerSettingsRepository(null)
        val viewModel = viewModel(device, settings)
        assertFalse(viewModel.uiState.value.isServerConfigured)

        viewModel.onNameChange("Pixel 8")
        viewModel.register()

        assertEquals(RegistrationError.ServerNotConfigured, viewModel.uiState.value.error)
        assertTrue(device.registerCalls.isEmpty())

        // 設定画面で保存して戻ってくると、登録できる状態になり案内も消える
        settings.save(server)
        assertTrue(viewModel.uiState.value.isServerConfigured)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun 通信に失敗するとエラーを表示し再試行できる() = runTest {
        val device = FakeDeviceRepository(result = Result.failure(RegistrationException.Network(IOException("x"))))
        val viewModel = viewModel(device)
        viewModel.onNameChange("Pixel 8")

        viewModel.register()

        assertEquals(RegistrationError.Network, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isRegistering)
        assertNull(viewModel.uiState.value.registeredDeviceId)

        device.result = Result.success(DeviceIdentity("a1b2c3d4e5f60718", "tok"))
        viewModel.register()
        assertEquals("a1b2c3d4e5f60718", viewModel.uiState.value.registeredDeviceId)
    }

    @Test
    fun サーバーが400を返すと理由を表示する() = runTest {
        val device = FakeDeviceRepository(
            result = Result.failure(RegistrationException.Rejected(400, "bad-request", "name must be 1-64 characters")),
        )
        val viewModel = viewModel(device)
        viewModel.onNameChange("Pixel 8")

        viewModel.register()

        assertEquals(
            RegistrationError.Rejected("bad-request: name must be 1-64 characters"),
            viewModel.uiState.value.error,
        )
    }

    @Test
    fun 保存に失敗すると登録済みにならずエラーを表示する() = runTest {
        val device = FakeDeviceRepository(result = Result.failure(RegistrationException.StorageFailed(IOException("x"))))
        val viewModel = viewModel(device)
        viewModel.onNameChange("Pixel 8")

        viewModel.register()

        assertEquals(RegistrationError.StorageFailed, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.registeredDeviceId)
    }

    @Test
    fun 鍵の準備に失敗するとエラーを表示する() = runTest {
        val device = FakeDeviceRepository(result = Result.failure(RegistrationException.KeyFailed(IllegalStateException())))
        val viewModel = viewModel(device)
        viewModel.onNameChange("Pixel 8")

        viewModel.register()

        assertEquals(RegistrationError.KeyFailed, viewModel.uiState.value.error)
    }

    @Test
    fun 登録中に続けて押しても1回しか呼ばれない() = runTest {
        val gate = CompletableDeferred<Unit>()
        val device = FakeDeviceRepository(gate = gate)
        val viewModel = viewModel(device)
        viewModel.onNameChange("Pixel 8")

        viewModel.register()
        assertTrue(viewModel.uiState.value.isRegistering)
        viewModel.register()
        viewModel.register()
        gate.complete(Unit)

        assertEquals(1, device.registerCalls.size)
        assertFalse(viewModel.uiState.value.isRegistering)
        assertEquals("newdevice00000001", viewModel.uiState.value.registeredDeviceId)
    }

    @Test
    fun 登録済みなら登録処理を呼ばずdeviceIdを表示する() = runTest {
        val device = FakeDeviceRepository(initial = DeviceIdentity("a1b2c3d4e5f60718", "tok"))
        val viewModel = viewModel(device)

        viewModel.onNameChange("Pixel 8")
        viewModel.register()

        assertEquals("a1b2c3d4e5f60718", viewModel.uiState.value.registeredDeviceId)
        assertTrue(device.registerCalls.isEmpty())
    }
}
