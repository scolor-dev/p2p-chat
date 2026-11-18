package com.p2pchat.app.ui.addcontact

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.FakeDeviceRepository
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.network.RegisteredDevice
import com.p2pchat.app.data.network.RemoteDevice
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class AddContactViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeServerSettingsRepository(initial: ServerSettings? = null) : ServerSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: ServerSettings) {
            this.settings.value = settings
        }
    }

    private class FakeApiClient(
        var result: Result<RemoteDevice> = Result.success(RemoteDevice("peer000000000001", "Alice", "MFkw")),
    ) : ApiClient {
        val getDeviceCalls = mutableListOf<String>()
        override suspend fun healthz(server: ServerSettings): Result<Unit> = error("呼ばない")
        override suspend fun registerDevice(server: ServerSettings, name: String, publicKey: String):
            Result<RegisteredDevice> = error("呼ばない")
        override suspend fun getDevice(server: ServerSettings, deviceId: String): Result<RemoteDevice> {
            getDeviceCalls += deviceId
            return result
        }
    }

    private class FakeContactRepository(
        initial: List<Contact> = emptyList(),
        var failSave: Boolean = false,
    ) : ContactRepository {
        val contacts = MutableStateFlow(initial)
        override fun observeContacts(): Flow<List<Contact>> = contacts
        override suspend fun getContact(deviceId: String): Contact? = contacts.value.find { it.deviceId == deviceId }
        override suspend fun saveContact(contact: Contact) {
            if (failSave) throw IllegalStateException("保存失敗")
            contacts.value = contacts.value + contact
        }
        override suspend fun updateName(deviceId: String, name: String) = Unit
        override suspend fun updateVerified(deviceId: String, isVerified: Boolean) = Unit
        override suspend fun deleteContact(deviceId: String) = Unit
    }

    private val server = ServerSettings("192.168.1.10", 8081)
    private val myId = "me00000000000001"

    private fun viewModel(
        api: FakeApiClient = FakeApiClient(),
        settings: FakeServerSettingsRepository = FakeServerSettingsRepository(server),
        contacts: FakeContactRepository = FakeContactRepository(),
    ) = AddContactViewModel(
        apiClient = api,
        serverSettingsRepository = settings,
        deviceRepository = FakeDeviceRepository(initial = DeviceIdentity(myId, "tok")),
        contactRepository = contacts,
        clock = { 1234L },
    )

    @Test
    fun 検索すると見つかった相手の名前が出る() = runTest {
        val api = FakeApiClient()
        val vm = viewModel(api)

        vm.onDeviceIdChange("  peer000000000001 ")
        vm.search()

        val state = vm.uiState.value
        assertEquals("Alice", state.found?.name)
        assertFalse(state.isSearching)
        assertNull(state.error)
        assertEquals(listOf("peer000000000001"), api.getDeviceCalls) // 前後の空白は除く
    }

    @Test
    fun 追加すると未確認の連絡先として保存される() = runTest {
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)
        vm.onDeviceIdChange("peer000000000001")
        vm.search()

        vm.add()

        assertEquals(
            listOf(Contact("peer000000000001", "Alice", "MFkw", isVerified = false, createdAt = 1234L)),
            contacts.contacts.value,
        )
        assertTrue(vm.uiState.value.isAdded)
    }

    @Test
    fun 未入力なら問い合わせずにエラー() = runTest {
        val api = FakeApiClient()
        val vm = viewModel(api)

        vm.onDeviceIdChange("   ")
        vm.search()

        assertEquals(AddContactError.Empty, vm.uiState.value.error)
        assertTrue(api.getDeviceCalls.isEmpty())
    }

    @Test
    fun 接続先が未設定ならエラー() = runTest {
        val vm = viewModel(settings = FakeServerSettingsRepository(null))
        vm.onDeviceIdChange("peer000000000001")

        vm.search()

        assertEquals(AddContactError.ServerNotConfigured, vm.uiState.value.error)
    }

    @Test
    fun 自分のIDは問い合わせずに断る() = runTest {
        val api = FakeApiClient()
        val vm = viewModel(api)
        vm.onDeviceIdChange(myId)

        vm.search()

        assertEquals(AddContactError.Self, vm.uiState.value.error)
        assertTrue(api.getDeviceCalls.isEmpty())
    }

    @Test
    fun 追加済みのIDは問い合わせずに断る() = runTest {
        val api = FakeApiClient()
        val existing = Contact("peer000000000001", "Alice", "MFkw", isVerified = true, createdAt = 1L)
        val vm = viewModel(api, contacts = FakeContactRepository(listOf(existing)))
        vm.onDeviceIdChange("peer000000000001")

        vm.search()

        assertEquals(AddContactError.AlreadyAdded, vm.uiState.value.error)
        assertTrue(api.getDeviceCalls.isEmpty())
    }

    @Test
    fun サーバーが404ならNotFound() = runTest {
        val vm = viewModel(FakeApiClient(Result.failure(ApiException(404, "not-found", "device not found"))))
        vm.onDeviceIdChange("nope")

        vm.search()

        assertEquals(AddContactError.NotFound, vm.uiState.value.error)
        assertNull(vm.uiState.value.found)
    }

    @Test
    fun 通信に失敗したらNetwork() = runTest {
        val vm = viewModel(FakeApiClient(Result.failure(IOException("x"))))
        vm.onDeviceIdChange("peer000000000001")

        vm.search()

        assertEquals(AddContactError.Network, vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun 入力を変えると見つかった相手とエラーが消える() = runTest {
        val vm = viewModel()
        vm.onDeviceIdChange("peer000000000001")
        vm.search()

        vm.onDeviceIdChange("other")

        assertNull(vm.uiState.value.found)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun 見つかっていなければ追加しても何も保存しない() = runTest {
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)

        vm.add()

        assertTrue(contacts.contacts.value.isEmpty())
        assertFalse(vm.uiState.value.isAdded)
    }

    @Test
    fun 保存に失敗したらStorageFailedで追加済みにならない() = runTest {
        val vm = viewModel(contacts = FakeContactRepository(failSave = true))
        vm.onDeviceIdChange("peer000000000001")
        vm.search()

        vm.add()

        assertEquals(AddContactError.StorageFailed, vm.uiState.value.error)
        assertFalse(vm.uiState.value.isAdded)
        assertFalse(vm.uiState.value.isAdding)
    }
}
