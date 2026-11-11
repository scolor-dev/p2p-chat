package com.p2pchat.app.ui.navigation

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
        }

        val viewModel = StartViewModel(broken)

        assertEquals(StartDestination.Registration, viewModel.start.value)
    }
}
