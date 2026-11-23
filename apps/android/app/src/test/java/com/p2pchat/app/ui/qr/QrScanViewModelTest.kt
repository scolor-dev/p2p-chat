package com.p2pchat.app.ui.qr

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.PublicKeyFingerprint
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
import java.util.Base64

class QrScanViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeServerSettingsRepository(initial: ServerSettings? = null) : ServerSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: ServerSettings) {
            this.settings.value = settings
        }
    }

    private class FakeApiClient(var result: Result<RemoteDevice>) : ApiClient {
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
        override suspend fun updateVerified(deviceId: String, isVerified: Boolean) {
            if (failSave) throw IllegalStateException("保存失敗")
            contacts.value = contacts.value.map { if (it.deviceId == deviceId) it.copy(isVerified = isVerified) else it }
        }
        override suspend fun deleteContact(deviceId: String) = Unit
    }

    private val server = ServerSettings("192.168.1.10", 8081)
    private val myId = "me00000000000001"
    private val peerId = "peer000000000001"
    private val publicKey = Base64.getEncoder().encodeToString("peer-public-key".toByteArray())
    private val fp = PublicKeyFingerprint.fromBase64(publicKey)!!
    private val qr = "p2pchat://contact?id=$peerId&fp=$fp"

    private val api = FakeApiClient(Result.success(RemoteDevice(peerId, "Alice", publicKey)))

    private fun viewModel(
        settings: FakeServerSettingsRepository = FakeServerSettingsRepository(server),
        contacts: FakeContactRepository = FakeContactRepository(),
    ) = QrScanViewModel(
        apiClient = api,
        serverSettingsRepository = settings,
        deviceRepository = FakeDeviceRepository(initial = DeviceIdentity(myId, "tok")),
        contactRepository = contacts,
        clock = { 1234L },
    )

    @Test
    fun fpが一致すれば確認済みの連絡先として保存する() = runTest {
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned(qr)

        assertEquals(listOf(Contact(peerId, "Alice", publicKey, isVerified = true, createdAt = 1234L)), contacts.contacts.value)
        assertEquals(QrScanSuccess("Alice", wasAlreadyContact = false), vm.uiState.value.success)
        assertFalse(vm.uiState.value.isProcessing)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun fpが違えば保存せず警告する() = runTest {
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned("p2pchat://contact?id=$peerId&fp=AAAA")

        assertEquals(QrScanError.FingerprintMismatch, vm.uiState.value.error)
        assertTrue(contacts.contacts.value.isEmpty())
        assertNull(vm.uiState.value.success)
    }

    @Test
    fun サーバーの公開鍵が読めない形でも不一致として保存しない() = runTest {
        api.result = Result.success(RemoteDevice(peerId, "Alice", "%%%"))
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned(qr)

        assertEquals(QrScanError.FingerprintMismatch, vm.uiState.value.error)
        assertTrue(contacts.contacts.value.isEmpty())
    }

    @Test
    fun 未確認の連絡先を読むと確認済みに更新する() = runTest {
        val existing = Contact(peerId, "Alice", publicKey, isVerified = false, createdAt = 1L)
        val contacts = FakeContactRepository(listOf(existing))
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned(qr)

        assertEquals(listOf(existing.copy(isVerified = true)), contacts.contacts.value)
        assertEquals(QrScanSuccess("Alice", wasAlreadyContact = true), vm.uiState.value.success)
    }

    @Test
    fun 未確認の連絡先でもfpが違えば確認済みにしない() = runTest {
        val existing = Contact(peerId, "Alice", publicKey, isVerified = false, createdAt = 1L)
        val contacts = FakeContactRepository(listOf(existing))
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned("p2pchat://contact?id=$peerId&fp=AAAA")

        assertEquals(listOf(existing), contacts.contacts.value)
        assertEquals(QrScanError.FingerprintMismatch, vm.uiState.value.error)
    }

    @Test
    fun すでに確認済みの相手を読んでも重複せず成功する() = runTest {
        val existing = Contact(peerId, "Alice", publicKey, isVerified = true, createdAt = 1L)
        val contacts = FakeContactRepository(listOf(existing))
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned(qr)

        assertEquals(listOf(existing), contacts.contacts.value)
        assertEquals(QrScanSuccess("Alice", wasAlreadyContact = true), vm.uiState.value.success)
    }

    @Test
    fun 自分のQRは問い合わせずに断る() = runTest {
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned("p2pchat://contact?id=$myId&fp=$fp")

        assertEquals(QrScanError.Self, vm.uiState.value.error)
        assertTrue(api.getDeviceCalls.isEmpty())
        assertTrue(contacts.contacts.value.isEmpty())
    }

    @Test
    fun 接続先が未設定ならエラー() = runTest {
        val vm = viewModel(settings = FakeServerSettingsRepository(null))

        vm.onQrScanned(qr)

        assertEquals(QrScanError.ServerNotConfigured, vm.uiState.value.error)
        assertTrue(api.getDeviceCalls.isEmpty())
    }

    @Test
    fun 存在しないIDはNotFound() = runTest {
        api.result = Result.failure(ApiException(404, "not_found", null))
        val vm = viewModel()

        vm.onQrScanned(qr)

        assertEquals(QrScanError.NotFound, vm.uiState.value.error)
    }

    @Test
    fun 通信に失敗したらNetwork() = runTest {
        api.result = Result.failure(IOException("到達できない"))
        val contacts = FakeContactRepository()
        val vm = viewModel(contacts = contacts)

        vm.onQrScanned(qr)

        assertEquals(QrScanError.Network, vm.uiState.value.error)
        assertTrue(contacts.contacts.value.isEmpty())
        assertFalse(vm.uiState.value.isProcessing)
    }

    @Test
    fun サーバーのエラーもNetwork() = runTest {
        api.result = Result.failure(ApiException(500, null, null))
        val vm = viewModel()

        vm.onQrScanned(qr)

        assertEquals(QrScanError.Network, vm.uiState.value.error)
    }

    @Test
    fun 保存に失敗したらStorageFailed() = runTest {
        val vm = viewModel(contacts = FakeContactRepository(failSave = true))

        vm.onQrScanned(qr)

        assertEquals(QrScanError.StorageFailed, vm.uiState.value.error)
        assertNull(vm.uiState.value.success)
        assertFalse(vm.uiState.value.isProcessing)
    }

    @Test
    fun 連絡先のQRでなければ問い合わせずにエラー() = runTest {
        val vm = viewModel()

        vm.onQrScanned("https://example.com")
        assertEquals(QrScanError.NotContactQr, vm.uiState.value.error)
        vm.dismissError()
        vm.onQrScanned("p2pchat://contact?id=$peerId")
        assertEquals(QrScanError.BrokenQr, vm.uiState.value.error)
        assertTrue(api.getDeviceCalls.isEmpty())
    }

    @Test
    fun エラーを閉じるまでは新しい読み取りを受け付けず_閉じたらまた読める() = runTest {
        api.result = Result.failure(IOException("到達できない"))
        val vm = viewModel()
        vm.onQrScanned(qr)
        api.result = Result.success(RemoteDevice(peerId, "Alice", publicKey))

        vm.onQrScanned(qr)
        assertEquals(1, api.getDeviceCalls.size)
        assertEquals(QrScanError.Network, vm.uiState.value.error)

        vm.dismissError()
        vm.onQrScanned(qr)
        assertEquals(2, api.getDeviceCalls.size)
        assertEquals(QrScanSuccess("Alice", false), vm.uiState.value.success)
    }

    @Test
    fun 成功したあとの読み取りは無視する() = runTest {
        val vm = viewModel()
        vm.onQrScanned(qr)

        vm.onQrScanned(qr)

        assertEquals(1, api.getDeviceCalls.size)
    }
}
