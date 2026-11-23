package com.p2pchat.app.ui.contacts

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.PublicKeyFingerprint
import com.p2pchat.app.data.message.InMemoryMessageRepository
import com.p2pchat.app.data.message.Message
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

/**
 * ViewModel のテストの見本。
 * Repository をテスト用の偽物（Fake）に差し替えられるのが、手動 DI の利点。
 */
class ContactsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeContactRepository : ContactRepository {
        val contacts = MutableStateFlow<List<Contact>>(emptyList())
        override fun observeContacts(): Flow<List<Contact>> = contacts
        override suspend fun getContact(deviceId: String): Contact? = contacts.value.find { it.deviceId == deviceId }
        override suspend fun saveContact(contact: Contact) { contacts.value = contacts.value + contact }
        override suspend fun updateName(deviceId: String, name: String) {
            contacts.value = contacts.value.map { if (it.deviceId == deviceId) it.copy(name = name) else it }
        }
        override suspend fun updateVerified(deviceId: String, isVerified: Boolean) {
            contacts.value = contacts.value.map { if (it.deviceId == deviceId) it.copy(isVerified = isVerified) else it }
        }
        override suspend fun deleteContact(deviceId: String) { contacts.value = contacts.value.filterNot { it.deviceId == deviceId } }
    }

    @Test
    fun 連絡先がない間は読み込み完了で空の一覧になる() = runTest {
        val viewModel = ContactsViewModel(FakeContactRepository(), InMemoryMessageRepository())

        val state = viewModel.uiState.first { !it.isLoading }

        assertTrue(state.contacts.isEmpty())
    }

    @Test
    fun Repositoryの連絡先が画面の状態に反映される() = runTest {
        val repository = FakeContactRepository()
        val viewModel = ContactsViewModel(repository, InMemoryMessageRepository())
        val alice = Contact(deviceId = "a1", name = "Alice", publicKey = "", isVerified = false, createdAt = 1000L)

        repository.contacts.value = listOf(alice)
        val state = viewModel.uiState.first { it.contacts.isNotEmpty() }

        assertEquals(1, state.contacts.size)
        val item = state.contacts.single()
        assertEquals("a1", item.deviceId)
        assertEquals("Alice", item.name)
        assertEquals(false, state.isLoading)
    }

    private fun message(id: String, deviceId: String, direction: MessageDirection, status: MessageStatus, at: Long) =
        Message(id, deviceId, direction, "body-$id", status, at)

    @Test
    fun メッセージが無い相手は本文が空で未確認と送信失敗は立たない() = runTest {
        val repository = FakeContactRepository()
        val viewModel = ContactsViewModel(repository, InMemoryMessageRepository())
        repository.contacts.value = listOf(
            Contact(deviceId = "a1", name = "Alice", publicKey = "", isVerified = false, createdAt = 1000L),
        )

        val item = viewModel.uiState.first { it.contacts.isNotEmpty() }.contacts.single()

        assertEquals("", item.latestMessage)
        assertEquals(false, item.isUnread)
        assertEquals(false, item.sendError)
    }

    @Test
    fun 最新メッセージの本文と送信失敗が相手ごとに反映される() = runTest {
        val contacts = FakeContactRepository()
        val messages = InMemoryMessageRepository()
        val viewModel = ContactsViewModel(contacts, messages)
        contacts.contacts.value = listOf(
            Contact("a1", "Alice", "", false, 1L),
            Contact("b2", "Bob", "", false, 2L),
            Contact("c3", "Carol", "", false, 3L),
        )
        messages.messages.value = listOf(
            // Alice: 古い失敗のあとに送信済みがある → エラーなし
            message("1", "a1", MessageDirection.SENT, MessageStatus.FAILED, 1),
            message("2", "a1", MessageDirection.SENT, MessageStatus.SENT, 2),
            // Bob: 最新が送信失敗 → エラー
            message("3", "b2", MessageDirection.RECEIVED, MessageStatus.SENT, 3),
            message("4", "b2", MessageDirection.SENT, MessageStatus.FAILED, 4),
            // Carol: 最新が送信中 → エラーではない
            message("5", "c3", MessageDirection.SENT, MessageStatus.SENDING, 5),
        )

        val items = viewModel.uiState.first { it.contacts.size == 3 }.contacts.associateBy { it.deviceId }

        assertEquals("body-2", items.getValue("a1").latestMessage)
        assertEquals(false, items.getValue("a1").sendError)
        assertEquals("body-4", items.getValue("b2").latestMessage)
        assertEquals(true, items.getValue("b2").sendError)
        assertEquals(false, items.getValue("c3").sendError)
        assertEquals(false, items.getValue("b2").isUnread) // 未確認は今回は仮のまま
    }

    @Test
    fun 送信失敗が再送で送信中に戻ると一覧のエラーが消える() = runTest {
        val contacts = FakeContactRepository()
        val messages = InMemoryMessageRepository()
        val viewModel = ContactsViewModel(contacts, messages)
        contacts.contacts.value = listOf(Contact("a1", "Alice", "", false, 1L))
        messages.messages.value = listOf(message("1", "a1", MessageDirection.SENT, MessageStatus.FAILED, 1))
        assertEquals(true, viewModel.uiState.first { it.contacts.isNotEmpty() && it.contacts.single().sendError }.contacts.single().sendError)

        messages.updateStatus("1", MessageStatus.SENDING)

        assertEquals(false, viewModel.uiState.first { it.contacts.isNotEmpty() && !it.contacts.single().sendError }.contacts.single().sendError)
    }

    @Test
    fun 確認状態と公開鍵のフィンガープリントが一覧に渡る() = runTest {
        val repository = FakeContactRepository()
        val viewModel = ContactsViewModel(repository, InMemoryMessageRepository())
        val der = "abc".toByteArray()
        repository.contacts.value = listOf(
            Contact("a1", "Alice", Base64.getEncoder().encodeToString(der), isVerified = true, createdAt = 1L),
            Contact("b2", "Bob", publicKey = "", isVerified = false, createdAt = 2L),
        )

        val items = viewModel.uiState.first { it.contacts.size == 2 }.contacts.associateBy { it.deviceId }

        assertEquals(true, items.getValue("a1").isVerified)
        // 公開鍵そのものの先頭ではなく、DER の SHA-256（QR の fp と同じ計算）になっている
        assertEquals(PublicKeyFingerprint.fromDer(der), items.getValue("a1").fingerprint)
        assertEquals(false, items.getValue("b2").isVerified)
        assertNull(items.getValue("b2").fingerprint)
    }
}
