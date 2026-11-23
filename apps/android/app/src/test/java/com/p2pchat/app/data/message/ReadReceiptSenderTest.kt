package com.p2pchat.app.data.message

import com.p2pchat.app.data.peer.FakePeerConnector
import com.p2pchat.app.data.peer.FakePeerSession
import com.p2pchat.app.data.peer.PeerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadReceiptSenderTest {
    private val repo = InMemoryMessageRepository()
    private val reads = InMemoryReadStateRepository()
    private val connector = FakePeerConnector()

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun recv(n: Int, at: Long, device: String = "peer") =
        Message(uuid(n), device, MessageDirection.RECEIVED, "b$n", MessageStatus.SENT, at)

    private fun sentIds(session: FakePeerSession) =
        session.sent.map { (ChatFrameCodec.decode(it) as ChatFrame.Read).ids }

    @Test
    fun 既読位置が進んだ分の受信メッセージのidだけを送る() = runTest {
        val session = FakePeerSession("peer").also { connector.put(it) }
        repo.upsertMessage(recv(1, 10))
        repo.upsertMessage(recv(2, 20))
        repo.upsertMessage(recv(3, 30)) // まだ読んでいない
        repo.upsertMessage(Message(uuid(9), "peer", MessageDirection.SENT, "mine", MessageStatus.SENT, 15))
        repo.upsertMessage(recv(8, 15, device = "other"))
        ReadReceiptSender(connector, repo, reads, backgroundScope).start()
        runCurrent()

        reads.markRead("peer", 20)
        runCurrent()

        assertEquals(listOf(listOf(uuid(1), uuid(2))), sentIds(session))
        assertEquals(20L, reads.receiptSent["peer"])

        reads.markRead("peer", 30)
        runCurrent()
        assertEquals(listOf(listOf(uuid(1), uuid(2)), listOf(uuid(3))), sentIds(session))
    }

    @Test
    fun 接続が開いていない間は送らず_開いたときに未送信分を送る() = runTest {
        val session = FakePeerSession("peer", PeerState.Disconnected).also { connector.put(it) }
        repo.upsertMessage(recv(1, 10))
        ReadReceiptSender(connector, repo, reads, backgroundScope).start()
        reads.markRead("peer", 10)
        runCurrent()
        assertTrue(session.sent.isEmpty())
        assertTrue(reads.receiptSent.isEmpty())

        val reopened = FakePeerSession("peer").also { connector.put(it) }
        runCurrent()
        assertEquals(listOf(listOf(uuid(1))), sentIds(reopened))
    }

    @Test
    fun 送信に失敗したら記録せず_次の機会にやり直す() = runTest {
        val session = FakePeerSession("peer").also { it.sendResult = false; connector.put(it) }
        repo.upsertMessage(recv(1, 10))
        ReadReceiptSender(connector, repo, reads, backgroundScope).start()
        reads.markRead("peer", 10)
        runCurrent()
        assertTrue(reads.receiptSent.isEmpty())

        session.sendResult = true
        repo.upsertMessage(recv(2, 20))
        reads.markRead("peer", 20)
        runCurrent()
        assertEquals(listOf(listOf(uuid(1), uuid(2))), sentIds(session))
    }

    @Test
    fun idが多いときは分けて送る() = runTest {
        val session = FakePeerSession("peer").also { connector.put(it) }
        val n = ChatFrameCodec.MAX_READ_IDS + 5
        for (i in 1..n) repo.upsertMessage(recv(i, i.toLong()))
        ReadReceiptSender(connector, repo, reads, backgroundScope).start()
        reads.markRead("peer", n.toLong())
        runCurrent()
        assertEquals(listOf(ChatFrameCodec.MAX_READ_IDS, 5), sentIds(session).map { it.size })
    }
}
