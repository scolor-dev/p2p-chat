package com.p2pchat.app.ui.navigation

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.FakeDeviceRepository
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
}
