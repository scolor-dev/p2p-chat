package com.p2pchat.app.ui.settings

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeServerSettingsRepository(initial: ServerSettings? = null) : ServerSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: ServerSettings) {
            this.settings.value = settings
        }
    }

    private class FakeApiClient(var result: Result<Unit> = Result.success(Unit)) : ApiClient {
        val calledWith = mutableListOf<ServerSettings>()
        override suspend fun healthz(server: ServerSettings): Result<Unit> {
            calledWith += server
            return result
        }
    }

    @Test
    fun 未保存なら空欄と既定のポートが表示される() = runTest {
        val viewModel = SettingsViewModel(FakeServerSettingsRepository(), FakeApiClient())

        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertEquals("", state.host)
        assertEquals("8080", state.port)
        assertFalse(state.isSaved)
    }

    @Test
    fun 保存済みの値が入力欄に読み込まれる() = runTest {
        val repository = FakeServerSettingsRepository(ServerSettings("192.168.1.10", 9000))
        val viewModel = SettingsViewModel(repository, FakeApiClient())

        val state = viewModel.uiState.value

        assertEquals("192.168.1.10", state.host)
        assertEquals("9000", state.port)
        assertTrue(state.isSaved)
    }

    @Test
    fun 正しい入力を保存するとRepositoryに保存される() = runTest {
        val repository = FakeServerSettingsRepository()
        val viewModel = SettingsViewModel(repository, FakeApiClient())

        viewModel.onHostChange(" 192.168.1.10 ")
        viewModel.onPortChange("8081")
        viewModel.save()

        assertEquals(ServerSettings("192.168.1.10", 8081), repository.settings.value)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun スキームやポート付きのホストはエラーになり保存されない() = runTest {
        val repository = FakeServerSettingsRepository()
        val viewModel = SettingsViewModel(repository, FakeApiClient())

        viewModel.onHostChange("http://192.168.1.10:8080")
        viewModel.save()

        assertTrue(viewModel.uiState.value.hostError)
        assertNull(repository.settings.value)
    }

    @Test
    fun 範囲外のポートはエラーになり保存されない() = runTest {
        val repository = FakeServerSettingsRepository()
        val viewModel = SettingsViewModel(repository, FakeApiClient())

        viewModel.onHostChange("192.168.1.10")
        viewModel.onPortChange("70000")
        viewModel.save()

        assertTrue(viewModel.uiState.value.portError)
        assertNull(repository.settings.value)
    }

    @Test
    fun 接続テストは保存前の入力値で行い成功を表示する() = runTest {
        val repository = FakeServerSettingsRepository()
        val apiClient = FakeApiClient()
        val viewModel = SettingsViewModel(repository, apiClient)

        viewModel.onHostChange("10.0.2.2")
        viewModel.testConnection()

        assertEquals(listOf(ServerSettings("10.0.2.2", 8080)), apiClient.calledWith)
        assertEquals(ConnectionTestState.Success, viewModel.uiState.value.connectionTest)
        assertNull(repository.settings.value)
    }

    @Test
    fun 接続テストに失敗すると理由を表示する() = runTest {
        val apiClient = FakeApiClient(Result.failure(IOException("HTTP 500")))
        val viewModel = SettingsViewModel(FakeServerSettingsRepository(), apiClient)

        viewModel.onHostChange("192.168.1.10")
        viewModel.testConnection()

        assertEquals(ConnectionTestState.Failure("HTTP 500"), viewModel.uiState.value.connectionTest)
    }

    @Test
    fun 入力が誤っていれば接続テストを行わない() = runTest {
        val apiClient = FakeApiClient()
        val viewModel = SettingsViewModel(FakeServerSettingsRepository(), apiClient)

        viewModel.testConnection()

        assertTrue(viewModel.uiState.value.hostError)
        assertTrue(apiClient.calledWith.isEmpty())
        assertEquals(ConnectionTestState.Idle, viewModel.uiState.value.connectionTest)
    }

    @Test
    fun 入力を変えると保存済みと接続テストの結果が消える() = runTest {
        val repository = FakeServerSettingsRepository(ServerSettings("192.168.1.10"))
        val viewModel = SettingsViewModel(repository, FakeApiClient())
        viewModel.testConnection()

        viewModel.onPortChange("9000")

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertEquals(ConnectionTestState.Idle, state.connectionTest)
    }
}
