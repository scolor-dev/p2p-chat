package com.p2pchat.app.data.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultOutgoingMessageTrackerTest {
    private val repository = InMemoryMessageRepository()
    private val id = "6f1c0e5e-1111-4222-8333-444455556666"

    private fun sending(at: Long = 1L, messageId: String = id) =
        Message(messageId, "peer", MessageDirection.SENT, "hi", MessageStatus.SENDING, at)

    private fun status(messageId: String = id) = repository.messages.value.single { it.id == messageId }.status

    @Test
    fun 九秒ではSENDINGのまま十秒でFAILEDになる() = runTest {
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope)
        tracker.onSendStarted(sending())

        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(MessageStatus.SENDING, status())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(MessageStatus.FAILED, status())
        assertEquals(10_000L, currentTime)
    }

    @Test
    fun ackが届くとタイマーが止まりSENTのまま() = runTest {
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope)
        tracker.onSendStarted(sending())
        advanceTimeBy(5_000)

        tracker.onAckReceived("peer", id)
        assertEquals(MessageStatus.SENT, status())

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(MessageStatus.SENT, status())
    }

    @Test
    fun 他の相手のackでは止まらずタイムアウトする() = runTest {
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope)
        tracker.onSendStarted(sending())

        tracker.onAckReceived("other", id)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(MessageStatus.FAILED, status())
    }

    @Test
    fun タイムアウト値は注入できる() = runTest {
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope, ackTimeoutMillis = 500)
        tracker.onSendStarted(sending())

        advanceTimeBy(499)
        assertEquals(MessageStatus.SENDING, status())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(MessageStatus.FAILED, status())
    }

    @Test
    fun 送信失敗を通知するとタイマーも止まり再送後は新しい10秒で動く() = runTest {
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope)
        tracker.onSendStarted(sending())
        advanceTimeBy(1_000)
        tracker.onSendFailed(id)
        assertEquals(MessageStatus.FAILED, status())

        // 再送して SENDING に戻したあと、最初のタイマー（9 秒後に切れるはずだった）で FAILED にされない
        advanceTimeBy(7_000)
        tracker.onSendStarted(sending())
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(MessageStatus.SENDING, status())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(MessageStatus.FAILED, status())
    }

    @Test
    fun FAILEDのあとに遅れてackが届けばSENTになる() = runTest {
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope)
        tracker.onSendStarted(sending())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(MessageStatus.FAILED, status())

        tracker.onAckReceived("peer", id)
        assertEquals(MessageStatus.SENT, status())
    }

    @Test
    fun 起動時に前回のSENDINGだけがFAILEDになる() = runTest {
        val old = "aaaaaaaa-1111-4222-8333-444455556666"
        val sent = "bbbbbbbb-1111-4222-8333-444455556666"
        val received = "cccccccc-1111-4222-8333-444455556666"
        val fresh = "dddddddd-1111-4222-8333-444455556666"
        repository.upsertMessage(sending(at = 100, messageId = old))
        repository.upsertMessage(sending(at = 100, messageId = sent).copy(status = MessageStatus.SENT))
        repository.upsertMessage(
            sending(at = 100, messageId = received).copy(direction = MessageDirection.RECEIVED, status = MessageStatus.SENDING),
        )
        repository.upsertMessage(sending(at = 5_000, messageId = fresh))
        val tracker = DefaultOutgoingMessageTracker(repository, backgroundScope, clock = { 1_000L })

        tracker.failInterruptedSending()

        assertEquals(MessageStatus.FAILED, status(old))
        assertEquals(MessageStatus.SENT, status(sent))
        assertEquals(MessageStatus.SENDING, status(received)) // 受信メッセージは対象外
        assertEquals(MessageStatus.SENDING, status(fresh)) // この起動以降に作られたものは触らない
    }
}
