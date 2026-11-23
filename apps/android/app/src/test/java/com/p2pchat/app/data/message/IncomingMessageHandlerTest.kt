package com.p2pchat.app.data.message

import kotlinx.coroutines.launch
import com.p2pchat.app.data.crypto.PeerKey
import com.p2pchat.app.data.peer.FakePeerConnector
import com.p2pchat.app.data.peer.FakePeerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingMessageHandlerTest {
    private val me = TestDevice("me")
    private val peer = TestDevice("peer")
    private val id = "6f1c0e5e-1111-4222-8333-444455556666"

    private val repository = InMemoryMessageRepository()
    private val connector = FakePeerConnector()
    private val session = FakePeerSession("peer")

    private fun TestScope.startHandler(): IncomingMessageHandler {
        connector.put(session)
        return IncomingMessageHandler(
            peerConnector = connector,
            contactRepository = InMemoryContacts(me.contactOf(peer)),
            messageRepository = repository,
            cipher = me.cipher,
            tracker = DefaultOutgoingMessageTracker(repository, backgroundScope, Long.MAX_VALUE),
            scope = backgroundScope as CoroutineScope,
            cryptoDispatcher = UnconfinedTestDispatcher(testScheduler),
            clock = { 99L },
        ).also {
            it.start()
            runCurrent()
        }
    }

    /** peer が me 宛てに作る msg フレーム。 */
    private fun msgFrame(body: String, frameId: String = id): String {
        val encrypted = peer.cipher.encrypt(
            PeerKey("me", me.keyPair.public.encoded), frameId, body.toByteArray(Charsets.UTF_8),
        )
        return ChatFrameCodec.encode(ChatFrame.Msg(frameId, encrypted.nonceBase64(), encrypted.ctBase64()))
    }

    private fun ackFrame(frameId: String = id) = ChatFrameCodec.encode(ChatFrame.Ack(frameId))

    @Test
    fun msgを復号して保存しackを返す() = runTest {
        startHandler()

        session.receive(msgFrame("こんにちは"))
        runCurrent()

        assertEquals(
            Message(id, "peer", MessageDirection.RECEIVED, "こんにちは", MessageStatus.SENT, 99L),
            repository.getMessage(id),
        )
        assertEquals(listOf(ackFrame()), session.sent)
    }

    @Test
    fun 新しく保存した受信だけreceivedMessagesに流れる() = runTest {
        val handler = startHandler()
        val received = mutableListOf<Message>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.receivedMessages.collect { received += it } }
        runCurrent()

        session.receive(msgFrame("one"))
        session.receive(msgFrame("one"))
        runCurrent()

        assertEquals(listOf("one"), received.map { it.body })
    }

    @Test
    fun 同じidが再び届いても1件のままackだけ返す() = runTest {
        startHandler()

        session.receive(msgFrame("one"))
        session.receive(msgFrame("one"))
        runCurrent()

        assertEquals(1, repository.messages.value.size)
        assertEquals(listOf(ackFrame(), ackFrame()), session.sent)
    }

    @Test
    fun 壊れた入力は保存もackもしない() = runTest {
        startHandler()
        val valid = msgFrame("hello")
        val tampered = valid.replace("\"ct\":\"", "\"ct\":\"AAAA")
        val badBase64 = """{"type":"msg","id":"$id","nonce":"***","ct":"***"}"""
        val unknownType = """{"type":"hello","id":"$id"}"""
        val huge = """{"type":"msg","id":"$id","nonce":"AAAA","ct":"${"A".repeat(20_000)}"}"""
        val otherKey = TestDevice("peer").cipher.encrypt(
            PeerKey("me", me.keyPair.public.encoded), id, "x".toByteArray(),
        ).let { ChatFrameCodec.encode(ChatFrame.Msg(id, it.nonceBase64(), it.ctBase64())) }
        val tooLongBody = msgFrame("a".repeat(4097), "6f1c0e5e-1111-4222-8333-444455557777")

        for (text in listOf("not json", tampered, badBase64, unknownType, huge, otherKey, tooLongBody, "")) {
            session.receive(text)
        }
        runCurrent()

        assertTrue(repository.messages.value.isEmpty())
        assertTrue(session.sent.isEmpty())

        // 壊れた入力のあとも、購読は続いている
        session.receive(valid)
        runCurrent()
        assertEquals(1, repository.messages.value.size)
    }

    @Test
    fun 連絡先にいない相手のmsgは捨てる() = runTest {
        val stranger = FakePeerSession("stranger")
        connector.put(stranger)
        startHandler()

        stranger.receive(msgFrame("hi"))
        runCurrent()

        assertTrue(repository.messages.value.isEmpty())
        assertTrue(stranger.sent.isEmpty())
    }

    @Test
    fun 自分の送信分と同じidのmsgで上書きされない() = runTest {
        repository.upsertMessage(Message(id, "peer", MessageDirection.SENT, "mine", MessageStatus.SENDING, 1L))
        startHandler()

        session.receive(msgFrame("evil"))
        runCurrent()

        assertEquals("mine", repository.getMessage(id)!!.body)
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun ackを受けると送信済みになる() = runTest {
        repository.upsertMessage(Message(id, "peer", MessageDirection.SENT, "hi", MessageStatus.SENDING, 1L))
        startHandler()

        session.receive(ackFrame())
        runCurrent()

        assertEquals(MessageStatus.SENT, repository.getMessage(id)!!.status)
    }

    @Test
    fun 他の相手のidや受信分や知らないidのackは無視する() = runTest {
        repository.upsertMessage(Message(id, "other", MessageDirection.SENT, "hi", MessageStatus.SENDING, 1L))
        val recvId = "6f1c0e5e-1111-4222-8333-444455558888"
        repository.upsertMessage(Message(recvId, "peer", MessageDirection.RECEIVED, "x", MessageStatus.SENDING, 1L))
        startHandler()

        session.receive(ackFrame())
        session.receive(ackFrame(recvId))
        session.receive(ackFrame("6f1c0e5e-1111-4222-8333-444455559999"))
        runCurrent()

        assertEquals(MessageStatus.SENDING, repository.getMessage(id)!!.status)
        assertEquals(MessageStatus.SENDING, repository.getMessage(recvId)!!.status)
        assertEquals(2, repository.messages.value.size)
    }

    @Test
    fun あとから増えたsessionも購読する() = runTest {
        startHandler()
        val later = FakePeerSession("peer").also { connector.put(it) } // 新しい session に置き換わる
        runCurrent() // 購読が始まるのを待つ

        later.receive(msgFrame("later"))
        runCurrent()

        assertEquals("later", repository.getMessage(id)!!.body)
        assertEquals(listOf(ackFrame()), later.sent)
    }

    @Test
    fun 実物の暗号で2端末が往復でき送信側がSENTになる() = runTest {
        // 端末 A（me）と端末 B（peer）。それぞれ自分の DB・session を持ち、フレームを相手に受け渡す。
        val aRepo = repository
        val bRepo = InMemoryMessageRepository()
        val aSession = FakePeerSession("peer")
        val bSession = FakePeerSession("me")
        val aConnector = FakePeerConnector().also { it.put(aSession) }
        val bConnector = FakePeerConnector().also { it.put(bSession) }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val aTracker = DefaultOutgoingMessageTracker(aRepo, backgroundScope)
        val bTracker = DefaultOutgoingMessageTracker(bRepo, backgroundScope)

        IncomingMessageHandler(
            aConnector, InMemoryContacts(me.contactOf(peer)), aRepo, me.cipher, aTracker, backgroundScope, dispatcher,
        ).start()
        IncomingMessageHandler(
            bConnector, InMemoryContacts(peer.contactOf(me)), bRepo, peer.cipher, bTracker, backgroundScope, dispatcher,
        ).start()
        runCurrent()

        val sender = P2PChatMessageSender(
            InMemoryContacts(me.contactOf(peer)), aConnector, me.cipher, aTracker, aRepo, dispatcher,
            newId = { id },
        )

        assertTrue(sender.send("peer", "A から B へ 🎉").isSuccess)
        assertEquals(MessageStatus.SENDING, aRepo.getMessage(id)!!.status)

        bSession.receive(aSession.sent.single()) // A → B
        runCurrent()
        assertEquals("A から B へ 🎉", bRepo.getMessage(id)!!.body)
        assertEquals(MessageDirection.RECEIVED, bRepo.getMessage(id)!!.direction)

        aSession.receive(bSession.sent.single()) // B の ack → A
        runCurrent()
        assertEquals(MessageStatus.SENT, aRepo.getMessage(id)!!.status)

        // 逆向きも往復できる
        val bSender = P2PChatMessageSender(
            InMemoryContacts(peer.contactOf(me)), bConnector, peer.cipher, bTracker, bRepo, dispatcher,
            newId = { "6f1c0e5e-1111-4222-8333-44445555aaaa" },
        )
        assertTrue(bSender.send("me", "B から A へ").isSuccess)
        aSession.receive(bSession.sent.last())
        runCurrent()
        assertEquals("B から A へ", aRepo.getMessage("6f1c0e5e-1111-4222-8333-44445555aaaa")!!.body)
    }

    @Test
    fun 復号できないmsgは破棄して警告用に相手を通知しackも保存もしない() = runTest {
        val handler = startHandler()
        val failures = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.decryptFailures.collect { failures += it } }
        runCurrent()
        // 別の鍵で暗号化された（相手の鍵が変わったような）msg
        val stranger = TestDevice("peer")
        val encrypted = stranger.cipher.encrypt(PeerKey("me", me.keyPair.public.encoded), id, "x".toByteArray())

        session.receive(ChatFrameCodec.encode(ChatFrame.Msg(id, encrypted.nonceBase64(), encrypted.ctBase64())))
        runCurrent()

        assertEquals(listOf("peer"), failures)
        assertTrue(repository.messages.value.isEmpty())
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun 復号できたmsgでは警告を通知しない() = runTest {
        val handler = startHandler()
        val failures = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.decryptFailures.collect { failures += it } }
        runCurrent()

        session.receive(msgFrame("ok"))
        runCurrent()

        assertTrue(failures.isEmpty())
    }
}
