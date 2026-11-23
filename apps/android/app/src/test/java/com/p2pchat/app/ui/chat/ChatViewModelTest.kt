package com.p2pchat.app.ui.chat

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.message.ChatMessageSender
import com.p2pchat.app.data.message.Message
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageRepository
import com.p2pchat.app.data.message.MessageStatus
import com.p2pchat.app.data.message.ChatSendException
import com.p2pchat.app.data.peer.FakePeerConnector
import com.p2pchat.app.data.peer.FakePeerSession
import com.p2pchat.app.data.peer.PeerFailure
import com.p2pchat.app.data.peer.PeerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
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
        override suspend fun updateVerified(deviceId: String, isVerified: Boolean) = Unit
        override suspend fun deleteContact(deviceId: String) = Unit
    }

    private class FakeMessageRepository : MessageRepository {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        override fun observeMessages(deviceId: String): Flow<List<Message>> =
            messages.map { list -> list.filter { it.deviceId == deviceId } }
        override suspend fun upsertMessage(message: Message) { messages.value = messages.value + message }
        override suspend fun getMessage(id: String): Message? = messages.value.find { it.id == id }
        override suspend fun updateStatus(id: String, status: MessageStatus) {
            messages.value = messages.value.map { if (it.id == id) it.copy(status = status) else it }
        }
        override fun observeLatestMessages(): Flow<List<Message>> = messages
        override suspend fun failSendingBefore(olderThan: Long): Int = 0
    }

    private class FakeSender : ChatMessageSender {
        val sent = mutableListOf<Pair<String, String>>()
        var result: Result<Unit> = Result.success(Unit)
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun send(deviceId: String, body: String): Result<Unit> {
            sent += deviceId to body
            gate?.await()
            return result
        }

        val resent = mutableListOf<Pair<String, String>>()
        var resendResult: Result<Unit> = Result.success(Unit)
        var resendGate: CompletableDeferred<Unit>? = null
        override suspend fun resend(deviceId: String, id: String): Result<Unit> {
            resent += deviceId to id
            resendGate?.await()
            return resendResult
        }
    }

    private val contacts = FakeContactRepository()
    private val messages = FakeMessageRepository()
    private val sender = FakeSender()
    private val connector = FakePeerConnector()
    private val decryptFailures = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val viewModel by lazy { ChatViewModel("a1", contacts, messages, sender, connector, decryptFailures) }

    private fun alice() = Contact("a1", "Alice", "", false, 1L)

    private fun msg(id: String, direction: MessageDirection, at: Long, deviceId: String = "a1") =
        Message(id, deviceId, direction, "body-$id", MessageStatus.SENT, at)

    private fun kotlinx.coroutines.test.TestScope.observe() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun 連絡先がなければ名前はnullで空の状態() = runTest {
        observe()
        val state = viewModel.uiState.value
        assertNull(state.contactName)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.canSend)
    }

    @Test
    fun 連絡先の名前が反映され変更にも追従する() = runTest {
        observe()
        contacts.contacts.value = listOf(Contact("other", "Bob", "", false, 1L), alice())
        assertEquals("Alice", viewModel.uiState.value.contactName)

        contacts.updateName("a1", "Alice2")
        assertEquals("Alice2", viewModel.uiState.value.contactName)
    }

    @Test
    fun メッセージが並び順のまま方向としっぽ付きで反映される() = runTest {
        observe()
        messages.messages.value = listOf(
            msg("1", MessageDirection.RECEIVED, 1),
            msg("2", MessageDirection.SENT, 2),
            msg("3", MessageDirection.RECEIVED, 3),
            msg("4", MessageDirection.RECEIVED, 4),
            msg("x", MessageDirection.SENT, 5, deviceId = "other"),
        )

        val list = viewModel.uiState.value.messages
        assertEquals(listOf("1", "2", "3", "4"), list.map { it.id })
        assertEquals(listOf(false, true, false, false), list.map { it.isSent })
        assertEquals(listOf(true, true, false, true), list.map { it.showTail })
        assertEquals("body-1", list[0].body)
    }

    @Test
    fun 入力すると状態に入り送信できるようになる() = runTest {
        observe()
        viewModel.onInputChange("こんにちは")
        val state = viewModel.uiState.value
        assertEquals("こんにちは", state.input)
        assertTrue(state.canSend)
    }

    @Test
    fun 送信するとSenderに本文が渡り成功で入力が空になる() = runTest {
        observe()
        viewModel.onInputChange("hello\nworld")
        viewModel.onSendClick()

        assertEquals(listOf("a1" to "hello\nworld"), sender.sent)
        val state = viewModel.uiState.value
        assertEquals("", state.input)
        assertFalse(state.sendFailed)
        assertFalse(state.isSending)
    }

    @Test
    fun 空文字と空白だけは送らない() = runTest {
        observe()
        viewModel.onSendClick()
        viewModel.onInputChange("  \n\t ")
        assertFalse(viewModel.uiState.value.canSend)
        viewModel.onSendClick()

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun 本文は4096バイトまで送れて超えると送れない() = runTest {
        observe()
        viewModel.onInputChange("a".repeat(4096))
        assertTrue(viewModel.uiState.value.canSend)
        assertFalse(viewModel.uiState.value.isInputTooLong)

        viewModel.onInputChange("a".repeat(4097))
        assertTrue(viewModel.uiState.value.isInputTooLong)
        assertFalse(viewModel.uiState.value.canSend)
        viewModel.onSendClick()
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun 長さは文字数ではなくUTF8のバイト数で数える() = runTest {
        observe()
        // 「あ」は 3 バイト。1365 文字 = 4095 バイト、1366 文字 = 4098 バイト。
        viewModel.onInputChange("あ".repeat(1365))
        assertFalse(viewModel.uiState.value.isInputTooLong)
        viewModel.onInputChange("あ".repeat(1366))
        assertTrue(viewModel.uiState.value.isInputTooLong)
    }

    @Test
    fun 送信に失敗すると入力を残してエラーを出し入力を変えると消える() = runTest {
        observe()
        sender.result = Result.failure(IllegalStateException("not connected"))
        viewModel.onInputChange("hello")
        viewModel.onSendClick()

        var state = viewModel.uiState.value
        assertEquals("hello", state.input)
        assertTrue(state.sendFailed)
        assertFalse(state.isSending)
        assertTrue(state.canSend)

        viewModel.onInputChange("hello!")
        assertFalse(viewModel.uiState.value.sendFailed)
    }

    @Test
    fun 送信中は二重に送れない() = runTest {
        observe()
        sender.gate = CompletableDeferred()
        viewModel.onInputChange("hello")
        viewModel.onSendClick()

        assertTrue(viewModel.uiState.value.isSending)
        assertFalse(viewModel.uiState.value.canSend)
        viewModel.onSendClick()
        assertEquals(1, sender.sent.size)

        sender.gate!!.complete(Unit)
        assertFalse(viewModel.uiState.value.isSending)
        assertEquals("", viewModel.uiState.value.input)
    }

    @Test
    fun 画面を開くと相手に接続し接続済みと表示する() = runTest {
        observe()
        assertEquals(listOf("a1"), connector.connectCalls)
        assertEquals(ChatConnection.Connected, viewModel.uiState.value.connection)
    }

    @Test
    fun 接続状態の変化が帯の状態に反映される() = runTest {
        connector.initialState = PeerState.Connecting
        observe()
        assertEquals(ChatConnection.Connecting, viewModel.uiState.value.connection)

        val session = connector.sessions.value.getValue("a1") as FakePeerSession
        session.state.value = PeerState.Failed(PeerFailure.PeerOffline)
        assertEquals(ChatConnection.PeerOffline, viewModel.uiState.value.connection)
        session.state.value = PeerState.Failed(PeerFailure.Rejected)
        assertEquals(ChatConnection.Rejected, viewModel.uiState.value.connection)
        session.state.value = PeerState.Failed(PeerFailure.Timeout)
        assertEquals(ChatConnection.NoResponse, viewModel.uiState.value.connection)
        session.state.value = PeerState.Failed(PeerFailure.SignalingUnavailable)
        assertEquals(ChatConnection.SignalingUnavailable, viewModel.uiState.value.connection)
        session.state.value = PeerState.Disconnected
        assertEquals(ChatConnection.Disconnected, viewModel.uiState.value.connection)
    }

    @Test
    fun 送信失敗の理由ごとに出し分ける() = runTest {
        observe()
        val cases = listOf(
            ChatSendException(ChatSendException.Reason.PEER, PeerFailure.PeerOffline) to ChatSendError.PeerOffline,
            ChatSendException(ChatSendException.Reason.PEER, PeerFailure.Rejected) to ChatSendError.Rejected,
            ChatSendException(ChatSendException.Reason.PEER, PeerFailure.Timeout) to ChatSendError.NoResponse,
            ChatSendException(ChatSendException.Reason.NO_PUBLIC_KEY) to ChatSendError.NoPublicKey,
            ChatSendException(ChatSendException.Reason.SEND_FAILED) to ChatSendError.Generic,
        )
        for ((failure, expected) in cases) {
            sender.result = Result.failure(failure)
            viewModel.onInputChange("hello")
            viewModel.onSendClick()
            assertEquals(expected, viewModel.uiState.value.sendError)
        }
    }

    private fun sentMsg(id: String, status: MessageStatus, at: Long) =
        Message(id, "a1", MessageDirection.SENT, "body-$id", status, at)

    @Test
    fun 送信状態が吹き出しの状態に反映される() = runTest {
        observe()
        messages.messages.value = listOf(
            sentMsg("1", MessageStatus.SENT, 1),
            sentMsg("2", MessageStatus.SENDING, 2),
            sentMsg("3", MessageStatus.FAILED, 3),
        )

        val list = viewModel.uiState.value.messages
        assertEquals(listOf(ChatSendStatus.Sent, ChatSendStatus.Sending, ChatSendStatus.Failed), list.map { it.sendStatus })
    }

    @Test
    fun 送信失敗のメッセージをタップすると同じidで再送し再送中は送信中に見える() = runTest {
        observe()
        messages.messages.value = listOf(sentMsg("3", MessageStatus.FAILED, 3))
        val gate = CompletableDeferred<Unit>()
        sender.resendGate = gate

        viewModel.onResendClick("3")

        assertEquals(listOf("a1" to "3"), sender.resent)
        assertEquals(ChatSendStatus.Sending, viewModel.uiState.value.messages.single().sendStatus)
        viewModel.onResendClick("3") // 再送中は二重に送らない
        assertEquals(1, sender.resent.size)

        gate.complete(Unit)
        // DB はまだ FAILED のまま（Fake の sender は状態を動かさない）なので、完了すると失敗の表示に戻る
        assertEquals(ChatSendStatus.Failed, viewModel.uiState.value.messages.single().sendStatus)
        assertFalse(viewModel.uiState.value.sendFailed)
    }

    @Test
    fun 再送に失敗すると理由を出す() = runTest {
        observe()
        messages.messages.value = listOf(sentMsg("3", MessageStatus.FAILED, 3))
        sender.resendResult = Result.failure(ChatSendException(ChatSendException.Reason.PEER, PeerFailure.PeerOffline))

        viewModel.onResendClick("3")

        val state = viewModel.uiState.value
        assertTrue(state.sendFailed)
        assertEquals(ChatSendError.PeerOffline, state.sendError)
    }

    @Test
    fun 送信中_送信済み_受信メッセージはタップしても再送しない() = runTest {
        observe()
        messages.messages.value = listOf(
            sentMsg("1", MessageStatus.SENT, 1),
            sentMsg("2", MessageStatus.SENDING, 2),
            msg("3", MessageDirection.RECEIVED, 3),
        )

        listOf("1", "2", "3", "unknown").forEach { viewModel.onResendClick(it) }

        assertTrue(sender.resent.isEmpty())
    }

    @Test
    fun 復号失敗の警告は自分の相手の分だけ出て数秒で消える() = runTest {
        observe()
        assertFalse(viewModel.uiState.value.decryptWarning)

        decryptFailures.emit("other")
        assertFalse(viewModel.uiState.value.decryptWarning)

        decryptFailures.emit("a1")
        assertTrue(viewModel.uiState.value.decryptWarning)

        advanceTimeBy(ChatViewModel.DECRYPT_WARNING_MILLIS - 1)
        assertTrue(viewModel.uiState.value.decryptWarning)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(viewModel.uiState.value.decryptWarning)
    }
}
