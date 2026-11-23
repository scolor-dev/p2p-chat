package com.p2pchat.app.integration

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.message.ChatFrame
import com.p2pchat.app.data.message.ChatFrameCodec
import com.p2pchat.app.data.message.ChatSendException
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageStatus
import com.p2pchat.app.data.message.TestDevice
import com.p2pchat.app.data.peer.PeerFailure
import com.p2pchat.app.data.peer.PeerState
import com.p2pchat.app.data.signaling.OutgoingSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * MVP の結合テスト（#30 のうち、実機なしでできる部分）。2 台ぶんのアプリの部品を、偽のシグナリングサーバーと
 * 偽の WebRTC でつなぎ、`docs/apps/android.md` の「完了の確認」のうちロジックで確かめられるものを通しで動かす。
 * 時間は仮想時間（[runTest]）。何を確かめて何を確かめていないかは `docs/test/mvp-check.md`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MvpIntegrationTest {
    private val aId = "aaaaaaaaaaaaaaaa" // 辞書順で小さい
    private val bId = "bbbbbbbbbbbbbbbb"

    private class World(scope: TestScope, aKnowsB: Boolean = true, bKnowsA: Boolean = true) {
        val aId = "aaaaaaaaaaaaaaaa"
        val bId = "bbbbbbbbbbbbbbbb"
        val devA = TestDevice(aId)
        val devB = TestDevice(bId)
        val server = FakeSignalingServer()
        val net = FakeWebRtcNetwork()
        private val directory = FakeDirectoryApi(
            mapOf(aId to remoteDeviceOf(devA, "Alice"), bId to remoteDeviceOf(devB, "Bob")),
        )
        val a = Phone(
            scope, devA, server, net, directory,
            if (aKnowsB) MemoryContacts(devA.contactOf(devB).copy(isVerified = true)) else MemoryContacts(),
        )
        val b = Phone(
            scope, devB, server, net, directory,
            if (bKnowsA) MemoryContacts(devB.contactOf(devA).copy(isVerified = true)) else MemoryContacts(),
        )

        init {
            a.start()
            b.start()
        }
    }

    private fun TestScope.world(aKnowsB: Boolean = true, bKnowsA: Boolean = true) =
        World(this, aKnowsB, bKnowsA).also { runCurrent() }

    // (a) 連絡先にいる相手との接続 → A→B の送信 → B が復号・保存・ack → A が送信済み
    @Test
    fun `連絡先にいる相手に送ると B に保存され ack で A が送信済みになる`() = runTest {
        val w = world()

        val result = w.a.sender.send(bId, "こんにちは B")
        runCurrent()

        assertTrue(result.isSuccess)
        assertEquals(PeerState.Open, w.a.connector.sessions.value[bId]?.state?.value)
        assertEquals(PeerState.Open, w.b.connector.sessions.value[aId]?.state?.value)
        assertEquals(1, w.net.channelsOpened)

        val onB = w.b.messages.messages.value.single()
        assertEquals("こんにちは B", onB.body)
        assertEquals(MessageDirection.RECEIVED, onB.direction)
        assertEquals(aId, onB.deviceId)

        val onA = w.a.messages.messages.value.single()
        assertEquals(onB.id, onA.id)
        assertEquals(MessageDirection.SENT, onA.direction)
        assertEquals(MessageStatus.SENT, onA.status)

        // 通信路に流れたのは msg と ack だけで、本文は平文では流れない
        val frames = w.net.frames.map { ChatFrameCodec.decode(it.third) }
        assertTrue(frames[0] is ChatFrame.Msg)
        assertEquals(ChatFrame.Ack(onA.id), frames[1])
        assertFalse(w.net.frames.any { "こんにちは" in it.third })
        // ack タイマーが残っていても、送信済みのまま変わらない
        advanceTimeBy(60_000)
        assertEquals(MessageStatus.SENT, w.a.messages.messages.value.single().status)
    }

    // (b) 逆向き
    @Test
    fun `B から A へも送れる`() = runTest {
        val w = world()

        assertTrue(w.b.sender.send(aId, "from B").isSuccess)
        runCurrent()
        assertEquals("from B", w.a.messages.messages.value.single().body)
        assertEquals(MessageStatus.SENT, w.b.messages.messages.value.single().status)

        // 続けて A からも返せる（同じ接続を使う）
        assertTrue(w.a.sender.send(bId, "reply").isSuccess)
        runCurrent()
        assertEquals(1, w.net.channelsOpened)
        assertEquals(listOf("from B", "reply"), w.b.messages.messages.value.sortedBy { it.sentAt }.map { it.body })
        assertTrue(w.a.messages.messages.value.all { it.status == MessageStatus.SENT })
        assertTrue(w.b.messages.messages.value.all { it.status == MessageStatus.SENT })
    }

    // (c) 同じ id の重複
    @Test
    fun `同じ id の msg が重複して届いても B には 1 件で ack は返る`() = runTest {
        val w = world()
        assertTrue(w.a.sender.send(bId, "once").isSuccess)
        runCurrent()
        val (_, _, msgFrame) = w.net.frames.first { ChatFrameCodec.decode(it.third) is ChatFrame.Msg }
        val acksBefore = w.net.frames.count { ChatFrameCodec.decode(it.third) is ChatFrame.Ack }

        // 同じフレームを 2 回、B の接続に流し込む（再送・二重配送の再現）
        val sessionOnB = w.b.connector.sessions.value.getValue(aId)
        repeat(2) { w.net.deliver(aId, bId, msgFrame) }
        runCurrent()

        assertEquals(1, w.b.messages.messages.value.size)
        assertEquals("once", w.b.messages.messages.value.single().body)
        assertEquals(acksBefore + 2, w.net.frames.count { ChatFrameCodec.decode(it.third) is ChatFrame.Ack })
        assertEquals(PeerState.Open, sessionOnB.state.value)
    }

    // (d) 連絡先にいない相手からの申請
    @Test
    fun `知らない相手の申請を拒否すると A は Rejected で、B の連絡先は増えない`() = runTest {
        val w = world(bKnowsA = false)

        val result = async { w.a.sender.send(bId, "はじめまして") }
        runCurrent()
        val request = w.b.requests.current.value
        assertNotNull(request)
        assertEquals(aId, request!!.deviceId)
        assertEquals("Alice", request.name)
        assertEquals(1, w.net.created) // A の PeerConnection だけ。B は許可するまで作らない

        w.b.requests.deny()
        runCurrent()

        val failure = result.await().exceptionOrNull() as ChatSendException
        assertEquals(ChatSendException.Reason.PEER, failure.reason)
        assertEquals(PeerFailure.Rejected, failure.peerFailure)
        assertNull(w.b.contacts.getContact(aId))
        assertNull(w.b.requests.current.value)
        assertEquals(1, w.net.created)
        // 失敗した送信は「送信失敗」として A の履歴に残る（再送できる）
        assertEquals(MessageStatus.FAILED, w.a.messages.messages.value.single().status)
        assertTrue(w.b.messages.messages.value.isEmpty())
    }

    @Test
    fun `知らない相手の申請を許可すると 未確認の連絡先に追加されチャットできる`() = runTest {
        val w = world(bKnowsA = false)
        // 拒否されたあと、もう一度話しかける（5-4 / 完了の確認 3 → 4）
        val first = async { w.a.sender.send(bId, "1 回目") }
        runCurrent()
        w.b.requests.deny()
        runCurrent()
        assertTrue(first.await().isFailure)

        val second = async { w.a.sender.send(bId, "2 回目") }
        runCurrent()
        assertEquals(aId, w.b.requests.current.value?.deviceId)
        w.b.requests.allow()
        runCurrent()
        assertTrue(second.await().isSuccess)
        runCurrent()

        val saved = w.b.contacts.getContact(aId)
        assertNotNull(saved)
        assertFalse(saved!!.isVerified)
        assertEquals("Alice", saved.name)
        assertEquals(w.devA.publicKeyBase64, saved.publicKey)
        assertEquals(listOf("2 回目"), w.b.messages.messages.value.map { it.body })
        // 拒否された 1 回目は「送信失敗」のまま残り、2 回目は送信済み
        assertEquals(
            mapOf("1 回目" to MessageStatus.FAILED, "2 回目" to MessageStatus.SENT),
            w.a.messages.messages.value.associate { it.body to it.status },
        )

        // B からも返せる（双方向）
        assertTrue(w.b.sender.send(aId, "返信").isSuccess)
        runCurrent()
        assertEquals(setOf("返信"), w.a.messages.messages.value.filter { it.direction == MessageDirection.RECEIVED }.map { it.body }.toSet())
        assertEquals(MessageStatus.SENT, w.b.messages.messages.value.single { it.direction == MessageDirection.SENT }.status)
    }

    // (e) 同時に offer を送り合う
    @Test
    fun `同時に offer を送り合っても 1 本の接続になり 辞書順で小さい側の offer が採用される`() = runTest {
        val w = world()

        w.a.connector.connect(bId)
        w.b.connector.connect(aId)
        runCurrent()

        assertEquals(PeerState.Open, w.a.connector.sessions.value.getValue(bId).state.value)
        assertEquals(PeerState.Open, w.b.connector.sessions.value.getValue(aId).state.value)
        assertEquals(1, w.net.channelsOpened)
        assertEquals(1, w.server.offersSentBy(aId))
        assertEquals(1, w.server.offersSentBy(bId))
        // answer を返したのは大きい側（B）だけ
        assertEquals(1, w.server.relayed.count { it.first == bId && it.second is OutgoingSignal.Answer })
        assertEquals(0, w.server.relayed.count { it.first == aId && it.second is OutgoingSignal.Answer })

        // 使い物になる接続か（両方向）
        assertTrue(w.a.sender.send(bId, "a to b").isSuccess)
        assertTrue(w.b.sender.send(aId, "b to a").isSuccess)
        runCurrent()
        assertEquals(1, w.net.channelsOpened)
        assertEquals("a to b", w.b.messages.messages.value.single { it.direction == MessageDirection.RECEIVED }.body)
        assertEquals("b to a", w.a.messages.messages.value.single { it.direction == MessageDirection.RECEIVED }.body)
    }

    // (f) 相手がオンラインでない
    @Test
    fun `相手が WebSocket につながっていないと PeerOffline になり メッセージは送信失敗で残る`() = runTest {
        val w = world()
        w.b.signaling.setOnline(false)

        val session = w.a.connector.connect(bId)
        runCurrent()
        assertEquals(PeerState.Failed(PeerFailure.PeerOffline), session.state.value)

        val result = w.a.sender.send(bId, "届かない")
        runCurrent()
        val failure = result.exceptionOrNull() as ChatSendException
        assertEquals(ChatSendException.Reason.PEER, failure.reason)
        assertEquals(PeerFailure.PeerOffline, failure.peerFailure)
        assertEquals(MessageStatus.FAILED, w.a.messages.messages.value.single().status)
        assertTrue(w.b.messages.messages.value.isEmpty())
    }

    // (g) 切れたあとの自動再接続
    @Test
    fun `接続が切れると自動で再接続し その後も送れる`() = runTest {
        val w = world()
        backgroundScope.launch { w.a.reconnector.keepConnected(bId) }
        runCurrent()
        assertEquals(PeerState.Open, w.a.connector.sessions.value.getValue(bId).state.value)
        assertTrue(w.a.sender.send(bId, "切れる前").isSuccess)
        runCurrent()

        // A の Wi-Fi が切れて、少しして戻る（WebSocket も DataChannel も切れる）
        w.a.signaling.setOnline(false)
        w.net.cut(aId, bId)
        runCurrent()
        assertTrue(w.a.connector.sessions.value.getValue(bId).state.value is PeerState.Disconnected)
        assertEquals(setOf(bId), w.a.reconnector.reconnecting.value)
        advanceTimeBy(3_000)
        assertEquals(1, w.net.channelsOpened) // 戻るまでは張り直せない

        w.a.signaling.setOnline(true)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(PeerState.Open, w.a.connector.sessions.value.getValue(bId).state.value)
        assertEquals(PeerState.Open, w.b.connector.sessions.value.getValue(aId).state.value)
        assertEquals(2, w.net.channelsOpened)
        assertTrue(w.a.reconnector.reconnecting.value.isEmpty())

        assertTrue(w.a.sender.send(bId, "切れた後").isSuccess)
        assertTrue(w.b.sender.send(aId, "B からも").isSuccess)
        runCurrent()
        assertEquals(setOf("切れる前", "切れた後"), w.b.messages.messages.value.filter { it.direction == MessageDirection.RECEIVED }.map { it.body }.toSet())
        assertEquals(listOf("B からも"), w.a.messages.messages.value.filter { it.direction == MessageDirection.RECEIVED }.map { it.body })
        assertTrue(w.a.messages.messages.value.all { it.status == MessageStatus.SENT })
        assertTrue(w.b.messages.messages.value.all { it.status == MessageStatus.SENT })
    }

    // (h) ack が来ない → 10 秒で送信失敗 → 手動再送で送信済み（B に重複しない）
    @Test
    fun `ack が来ないと 10 秒で送信失敗になり 手動再送で送信済みになる 相手には 1 件のまま`() = runTest {
        val w = world()
        // B → A のフレーム（ack）を落とす
        w.net.interceptor = { from, _, text -> if (from == bId) null else text }

        assertTrue(w.a.sender.send(bId, "ack なし").isSuccess)
        runCurrent()
        val id = w.a.messages.messages.value.single().id
        assertEquals(MessageStatus.SENDING, w.a.messages.messages.value.single().status)
        assertEquals(1, w.b.messages.messages.value.size) // 本文は B に届いている

        advanceTimeBy(9_900)
        assertEquals(MessageStatus.SENDING, w.a.messages.messages.value.single().status)
        advanceTimeBy(200)
        assertEquals(MessageStatus.FAILED, w.a.messages.messages.value.single().status)

        // ネットワークが戻ったあとの、手動の再送
        w.net.interceptor = { _, _, text -> text }
        assertTrue(w.a.sender.resend(bId, id).isSuccess)
        runCurrent()

        assertEquals(MessageStatus.SENT, w.a.messages.messages.value.single().status)
        assertEquals(1, w.a.messages.messages.value.size)
        assertEquals(1, w.b.messages.messages.value.size)
        assertEquals("ack なし", w.b.messages.messages.value.single().body)
        advanceTimeBy(60_000)
        assertEquals(MessageStatus.SENT, w.a.messages.messages.value.single().status)
    }

    // (i) 改ざん
    @Test
    fun `暗号文が改ざんされた msg は捨てられ 警告が出て 保存も ack もされない`() = runTest {
        val w = world()
        w.net.interceptor = { from, _, text ->
            val frame = ChatFrameCodec.decode(text)
            if (from == aId && frame is ChatFrame.Msg) {
                val ct = Base64.getDecoder().decode(frame.ct)
                ct[0] = (ct[0].toInt() xor 1).toByte()
                ChatFrameCodec.encode(frame.copy(ct = Base64.getEncoder().encodeToString(ct)))
            } else text
        }

        assertTrue(w.a.sender.send(bId, "改ざんされる").isSuccess)
        runCurrent()

        assertTrue(w.b.messages.messages.value.isEmpty())
        assertEquals(listOf(aId), w.b.decryptFailures)
        assertTrue(w.net.frames.none { ChatFrameCodec.decode(it.third) is ChatFrame.Ack })
        // 接続は生きていて、A は ack が来ないので 10 秒で失敗になる
        assertEquals(PeerState.Open, w.b.connector.sessions.value.getValue(aId).state.value)
        advanceTimeBy(10_100)
        assertEquals(MessageStatus.FAILED, w.a.messages.messages.value.single().status)

        // 改ざんが止めば、同じ接続でふつうに届く
        w.net.interceptor = { _, _, text -> text }
        assertTrue(w.a.sender.send(bId, "正しいもの").isSuccess)
        runCurrent()
        assertEquals(listOf("正しいもの"), w.b.messages.messages.value.map { it.body })
    }

    @Test
    fun `別の鍵で暗号化された msg も捨てられ 警告が出る`() = runTest {
        val w = world()
        // B の連絡先にある A の公開鍵が、実際の A の鍵と違う（サーバーによる鍵の差し替えなど）
        val stranger = TestDevice(aId)
        w.b.contacts.saveContact(Contact(aId, "Alice", stranger.publicKeyBase64, true, 1L))

        assertTrue(w.a.sender.send(bId, "復号できない").isSuccess)
        runCurrent()

        assertTrue(w.b.messages.messages.value.isEmpty())
        assertEquals(listOf(aId), w.b.decryptFailures)
    }

    // (d') 申請に 60 秒応答しない
    @Test
    fun `知らない相手の申請に 60 秒応答しないと A は Timeout になり B は何も送らない`() = runTest {
        val w = world(bKnowsA = false)
        val session = w.a.connector.connect(bId)
        runCurrent()
        assertEquals(aId, w.b.requests.current.value?.deviceId)

        advanceTimeBy(59_000)
        assertEquals(PeerState.Connecting, session.state.value)
        advanceTimeBy(2_000)

        assertEquals(PeerState.Failed(PeerFailure.Timeout), session.state.value)
        assertNull(w.b.requests.current.value)
        assertNull(w.b.contacts.getContact(aId))
        assertEquals(0, w.server.relayed.count { it.first == bId })
    }

    // 完了の確認 5: 「B の Wi-Fi を切った状態で送ると『送信失敗』になり、つながった後に再送すると『送信済み』になる」
    // 設計書の送信状態: [*] --> 送信失敗: P2P が未接続
    @Test
    fun `相手がオフラインで送ると 送信失敗のメッセージが残り つながった後に再送すると送信済みになる`() = runTest {
        val w = world()
        w.b.signaling.setOnline(false)

        val result = w.a.sender.send(bId, "オフライン中")
        runCurrent()
        assertTrue(result.isFailure)
        // 期待: 送信失敗として履歴に残る（タップで再送できる）
        val failed = w.a.messages.messages.value.singleOrNull()
        assertNotNull(failed)
        assertEquals(MessageStatus.FAILED, failed!!.status)

        w.b.signaling.setOnline(true)
        assertTrue(w.a.sender.resend(bId, failed.id).isSuccess)
        runCurrent()
        assertEquals(MessageStatus.SENT, w.a.messages.messages.value.single().status)
        assertEquals(1, w.b.messages.messages.value.size)
    }
}
