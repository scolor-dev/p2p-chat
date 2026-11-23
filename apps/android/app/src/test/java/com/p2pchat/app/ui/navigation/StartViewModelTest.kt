package com.p2pchat.app.ui.navigation

import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.FakeDeviceRepository
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun 未登録なら登録画面から始まる() = runTest {
        val viewModel = StartViewModel(FakeDeviceRepository())

        assertEquals(StartDestination.Registration, viewModel.start.value)
    }

    @Test
    fun 登録済みなら連絡先一覧から始まり登録は呼ばれない() = runTest {
        val device = FakeDeviceRepository(initial = DeviceIdentity("a1b2c3d4e5f60718", "tok"))

        val viewModel = StartViewModel(device)

        assertEquals(StartDestination.Contacts, viewModel.start.value)
        assertEquals(0, device.registerCalls.size)
    }

    @Test
    fun 身元を読めずに例外になっても登録画面にしてLoadingで固まらない() = runTest {
        val broken = object : DeviceRepository {
            override val identity: Flow<DeviceIdentity?> = flow { throw IllegalStateException("読めない") }
            override suspend fun register(server: ServerSettings, name: String): Result<DeviceIdentity> =
                error("呼ばれない")
            override suspend fun clearIdentity() = error("呼ばれない")
        }

        val viewModel = StartViewModel(broken)

        assertEquals(StartDestination.Registration, viewModel.start.value)
    }

    @Test
    fun 登録済みの身元が消えるとidentityClearedが1回流れる() = runTest {
        val device = FakeDeviceRepository(initial = DeviceIdentity("a1b2c3d4e5f60718", "tok"))
        val viewModel = StartViewModel(device)
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.identityCleared.toList(events) }

        device.clearIdentity()
        assertEquals(1, events.size)

        // 登録し直しても、登録済みになるだけでは流れない
        device.identity.value = DeviceIdentity("0123456789abcdef", "tok2")
        assertEquals(1, events.size)
    }

    @Test
    fun 起動時から未登録ならidentityClearedは流れない() = runTest {
        val device = FakeDeviceRepository()
        val viewModel = StartViewModel(device)
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.identityCleared.toList(events) }

        device.identity.value = DeviceIdentity("0123456789abcdef", "tok2")

        assertTrue(events.isEmpty())
    }
}
