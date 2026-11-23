package com.p2pchat.app.data.message

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.peer.FakePeerConnector
import com.p2pchat.app.data.peer.FakePeerSession
import com.p2pchat.app.data.peer.PeerFailure
import com.p2pchat.app.data.peer.PeerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2PChatMessageSenderTest {
    private val me = TestDevice("me")
    private val peer = TestDevice("peer")
    private val id = "6f1c0e5e-1111-4222-8333-444455556666"

    private val repository = InMemoryMessageRepository()
    private val connector = FakePeerConnector()
    private val events = mutableListOf<String>()

    /** 呼び出しの順序を記録する Tracker（中身は既定実装）。 */
    private val tracker = object : OutgoingMessageTracker {
        private val inner = DefaultOutgoingMessageTracker(repository, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()), Long.MAX_VALUE)
        override suspend fun onSendStarted(message: Message) {
            events += "saved:${message.status}"
            inner.onSendStarted(message)
        }

        override suspend fun onAckReceived(deviceId: String, id: String) = inner.onAckReceived(deviceId, id)
        override suspend fun onSendFailed(id: String) {
            events += "failed"
            inner.onSendFailed(id)
        }
    }

    private fun TestScopeSender(
        contacts: InMemoryContacts = InMemoryContacts(me.contactOf(peer)),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = P2PChatMessageSender(
        contactRepository = contacts,
        peerConnector = connector,
        cipher = me.cipher,
        tracker = tracker,
        messageRepository = repository,
        cryptoDispatcher = dispatcher,
        newId = { id },
        clock = { 1234L },
    )

    @Test
    fun 保存し暗号化してから送りpeer側で復号できる() = runTest {
        val session = FakePeerSession("peer").also { connector.put(it) }
        session.onSend = { events += "sent" }
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = sender.send("peer", "こんにちは\u0000\n世界")

        assertTrue(result.isSuccess)
        assertEquals(listOf("saved:SENDING", "sent"), events)
        val saved = repository.getMessage(id)!!
        assertEquals(Message(id, "peer", MessageDirection.SENT, "こんにちは\n世界", MessageStatus.SENDING, 1234L), saved)

        val frame = ChatFrameCodec.decode(session.sent.single()) as ChatFrame.Msg
        assertEquals(id, frame.id)
        val plain = peer.cipher.decrypt(
            com.p2pchat.app.data.crypto.PeerKey("me", me.keyPair.public.encoded),
            frame.id,
            com.p2pchat.app.data.crypto.EncryptedMessage.fromBase64(frame.nonce, frame.ct),
        )
        assertEquals("こんにちは\n世界", String(plain, Charsets.UTF_8))
        // 平文は DataChannel のフレームに含まれない
        assertFalse(session.sent.single().contains("世界"))
    }

    @Test
    fun 制御文字を除いて空になれば送らず保存もしない() = runTest {
        val session = FakePeerSession("peer").also { connector.put(it) }
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = sender.send("peer", "\u0000\u0007")

        assertEquals(ChatSendException.Reason.EMPTY_BODY, (result.exceptionOrNull() as ChatSendException).reason)
        assertTrue(session.sent.isEmpty())
        assertTrue(repository.messages.value.isEmpty())
    }

    @Test
    fun 連絡先がない_公開鍵が空_不正なbase64ならNO_PUBLIC_KEYで保存しない() = runTest {
        connector.put(FakePeerSession("peer"))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val contacts = listOf(
            InMemoryContacts(),
            InMemoryContacts(Contact("peer", "p", "", false, 1L)),
            InMemoryContacts(Contact("peer", "p", "!!!not base64!!!", false, 1L)),
        )
        for (c in contacts) {
            val result = TestScopeSender(c, dispatcher).send("peer", "hi")
            assertEquals(ChatSendException.Reason.NO_PUBLIC_KEY, (result.exceptionOrNull() as ChatSendException).reason)
        }
        assertTrue(repository.messages.value.isEmpty())
    }

    @Test
    fun 公開鍵として読めない場合は保存したメッセージをFAILEDにする() = runTest {
        val session = FakePeerSession("peer").also { connector.put(it) }
        val bad = Contact("peer", "p", java.util.Base64.getEncoder().encodeToString(ByteArray(10)), false, 1L)
        val sender = TestScopeSender(InMemoryContacts(bad), UnconfinedTestDispatcher(testScheduler))

        val result = sender.send("peer", "hi")

        assertEquals(ChatSendException.Reason.ENCRYPT_FAILED, (result.exceptionOrNull() as ChatSendException).reason)
        assertEquals(MessageStatus.FAILED, repository.getMessage(id)!!.status)
        assertTrue(session.sent.isEmpty())
        assertEquals(listOf("saved:SENDING", "failed"), events)
    }

    @Test
    fun sendがfalseならFAILEDになる() = runTest {
        val session = FakePeerSession("peer").also { it.sendResult = false; connector.put(it) }
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = sender.send("peer", "hi")

        assertEquals(ChatSendException.Reason.SEND_FAILED, (result.exceptionOrNull() as ChatSendException).reason)
        assertEquals(MessageStatus.FAILED, repository.getMessage(id)!!.status)
    }

    @Test
    fun 接続の失敗はPeerFailureごとに区別でき_メッセージはFAILEDで残る() = runTest {
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))
        for (failure in PeerFailure.entries) {
            connector.put(FakePeerSession("peer", PeerState.Failed(failure)))
            connector.initialState = PeerState.Failed(failure)

            val e = sender.send("peer", "hi").exceptionOrNull() as ChatSendException

            assertEquals(ChatSendException.Reason.PEER, e.reason)
            assertEquals(failure, e.peerFailure)
            assertEquals(MessageStatus.FAILED, repository.getMessage(id)!!.status)
            assertEquals(listOf("saved:SENDING", "failed"), events)
            events.clear()
        }
        assertEquals(1, repository.messages.value.size)
    }

    @Test
    fun 接続を待つ間はSENDINGで保存され_Openになれば送って成功する() = runTest {
        connector.initialState = PeerState.Connecting
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))
        backgroundScope.launch { sender.send("peer", "hi") }
        runCurrent()

        assertEquals(MessageStatus.SENDING, repository.getMessage(id)!!.status)
        assertEquals(listOf("saved:SENDING"), events)
    }

    @Test
    fun 待っている間に切れたらFAILEDで残る() = runTest {
        connector.initialState = PeerState.Connecting
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))
        backgroundScope.launch { sender.send("peer", "hi") }
        runCurrent()
        (connector.sessions.value.getValue("peer") as FakePeerSession).state.value = PeerState.Disconnected
        runCurrent()

        assertEquals(MessageStatus.FAILED, repository.getMessage(id)!!.status)
    }

    @Test
    fun 接続に失敗した送信は_つながった後の手動再送で同じidのまま送れる() = runTest {
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))
        connector.initialState = PeerState.Failed(PeerFailure.PeerOffline)
        assertTrue(sender.send("peer", "hi").isFailure)
        assertEquals(MessageStatus.FAILED, repository.getMessage(id)!!.status)

        connector.initialState = PeerState.Open
        assertTrue(sender.resend("peer", id).isSuccess)
        val session = connector.sessions.value.getValue("peer") as FakePeerSession

        assertEquals(1, repository.messages.value.size)
        assertEquals(MessageStatus.SENDING, repository.getMessage(id)!!.status)
        assertEquals(id, (ChatFrameCodec.decode(session.sent.single()) as ChatFrame.Msg).id)
    }

    @Test
    fun session無しなら接続しopenになるまで待ってから送る() = runTest {
        connector.initialState = PeerState.Connecting
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))
        var result: Result<Unit>? = null
        backgroundScope.launch { result = sender.send("peer", "hi") }
        runCurrent()

        assertEquals(listOf("peer"), connector.connectCalls)
        assertEquals(null, result)

        val session = connector.sessions.value.getValue("peer") as FakePeerSession
        session.state.value = PeerState.Open
        runCurrent()

        assertTrue(result!!.isSuccess)
        assertEquals(1, session.sent.size)
        assertNotNull(repository.getMessage(id))
    }

    @Test
    fun 待っている間に切れたらDISCONNECTED() = runTest {
        connector.initialState = PeerState.Connecting
        val sender = TestScopeSender(dispatcher = UnconfinedTestDispatcher(testScheduler))
        var result: Result<Unit>? = null
        backgroundScope.launch { result = sender.send("peer", "hi") }
        runCurrent()

        (connector.sessions.value.getValue("peer") as FakePeerSession).state.value = PeerState.Disconnected
        runCurrent()

        assertEquals(ChatSendException.Reason.DISCONNECTED, (result!!.exceptionOrNull() as ChatSendException).reason)
    }
}
