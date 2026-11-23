package com.p2pchat.app.data.message

import com.p2pchat.app.data.crypto.EncryptedMessage
import com.p2pchat.app.data.crypto.PeerKey
import com.p2pchat.app.data.peer.FakePeerConnector
import com.p2pchat.app.data.peer.FakePeerSession
import com.p2pchat.app.data.peer.PeerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 送信側（me）と受信側（peer）を、実物の暗号化で結んで、手動再送の一連の流れを確かめる。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResendTest {
    private val me = TestDevice("me")
    private val peer = TestDevice("peer")
    private val id = "6f1c0e5e-1111-4222-8333-444455556666"

    // me 側
    private val myRepository = InMemoryMessageRepository()
    private val myConnector = FakePeerConnector()
    private val mySession = FakePeerSession("peer", PeerState.Open)

    // peer 側
    private val peerRepository = InMemoryMessageRepository()
    private val peerConnector = FakePeerConnector()
    private val peerSession = FakePeerSession("me", PeerState.Open)

    private fun TestScope.setup(): P2PChatMessageSender {
        myConnector.put(mySession)
        val tracker = DefaultOutgoingMessageTracker(myRepository, backgroundScope)
        val sender = P2PChatMessageSender(
            contactRepository = InMemoryContacts(me.contactOf(peer)),
            peerConnector = myConnector,
            cipher = me.cipher,
            tracker = tracker,
            messageRepository = myRepository,
            cryptoDispatcher = UnconfinedTestDispatcher(testScheduler),
            newId = { id },
            clock = { 10L },
        )
        // me 側の受信処理（ack を受ける）
        IncomingMessageHandler(
            myConnector, InMemoryContacts(me.contactOf(peer)), myRepository, me.cipher, tracker, backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        ).start()
        // peer 側の受信処理
        peerConnector.put(peerSession)
        IncomingMessageHandler(
            peerConnector, InMemoryContacts(peer.contactOf(me)), peerRepository, peer.cipher,
            DefaultOutgoingMessageTracker(peerRepository, backgroundScope), backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        ).start()
        runCurrent()
        return sender
    }

    private fun status() = myRepository.messages.value.single { it.id == id }.status

    private fun nonceOf(frame: String) = (ChatFrameCodec.decode(frame) as ChatFrame.Msg).nonce

    @Test
    fun 失敗から再送しSENDINGに戻りackでSENTになり受信側は1件のまま() = runTest {
        val sender = setup()

        // 1 回目: 送ったが ack が来ず（相手が受け取れなかった）10 秒で FAILED
        assertTrue(sender.send("peer", "こんにちは").isSuccess)
        assertEquals(MessageStatus.SENDING, status())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(MessageStatus.FAILED, status())
        assertTrue(peerRepository.messages.value.isEmpty())

        // 再送: SENDING に戻る
        assertTrue(sender.resend("peer", id).isSuccess)
        assertEquals(MessageStatus.SENDING, status())
        assertEquals(2, mySession.sent.size)
        assertNotEquals("nonce は再利用しない", nonceOf(mySession.sent[0]), nonceOf(mySession.sent[1]))

        // 相手に届く → 保存して ack → SENT
        peerSession.receive(mySession.sent.last())
        runCurrent()
        mySession.receive(peerSession.sent.single())
        runCurrent()
        assertEquals(MessageStatus.SENT, status())
        val received = peerRepository.messages.value.single()
        assertEquals("こんにちは", received.body)
        assertEquals(MessageDirection.RECEIVED, received.direction)
    }

    @Test
    fun 両方のフレームが相手に届いても受信側は1件でackは両方に返る() = runTest {
        val sender = setup()
        sender.send("peer", "hello")
        advanceTimeBy(10_000)
        runCurrent()
        sender.resend("peer", id)

        peerSession.receive(mySession.sent[0])
        peerSession.receive(mySession.sent[1])
        runCurrent()

        assertEquals(1, peerRepository.messages.value.size)
        assertEquals(2, peerSession.sent.size) // 重複にも ack を返す
        // 再送のフレームも、1 回目とは別の nonce で正しく復号できる
        val frame = ChatFrameCodec.decode(mySession.sent[1]) as ChatFrame.Msg
        val plain = peer.cipher.decrypt(
            PeerKey("me", me.keyPair.public.encoded), frame.id, EncryptedMessage.fromBase64(frame.nonce, frame.ct),
        )
        assertEquals("hello", String(plain, Charsets.UTF_8))
    }

    @Test
    fun DataChannelに載せられず失敗したものを再送してSENTになる() = runTest {
        val sender = setup()
        mySession.sendResult = false
        assertTrue(sender.send("peer", "hi").isFailure)
        assertEquals(MessageStatus.FAILED, status())

        mySession.sendResult = true
        assertTrue(sender.resend("peer", id).isSuccess)
        assertEquals(MessageStatus.SENDING, status())
        peerSession.receive(mySession.sent.single())
        runCurrent()
        mySession.receive(peerSession.sent.single())
        runCurrent()

        assertEquals(MessageStatus.SENT, status())
        assertEquals(1, peerRepository.messages.value.size)
    }

    @Test
    fun 再送の接続に失敗したらFAILEDのまま() = runTest {
        val sender = setup()
        mySession.sendResult = false
        sender.send("peer", "hi")

        mySession.state.value = PeerState.Disconnected
        myConnector.initialState = PeerState.Disconnected
        val result = sender.resend("peer", id)

        assertEquals(ChatSendException.Reason.DISCONNECTED, (result.exceptionOrNull() as ChatSendException).reason)
        assertEquals(MessageStatus.FAILED, status())
    }

    @Test
    fun FAILED以外_他の相手の_存在しないidは再送しない() = runTest {
        val sender = setup()
        sender.send("peer", "hi") // SENDING
        assertTrue(sender.resend("peer", id).isSuccess)
        assertEquals(1, mySession.sent.size) // SENDING のものは再送しない

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(sender.resend("other", id).isSuccess)
        assertEquals(1, mySession.sent.size) // 相手が違えば送らない
        assertTrue(sender.resend("peer", "00000000-0000-4000-8000-000000000000").isSuccess)
        assertEquals(1, mySession.sent.size) // 存在しない id
    }
}
