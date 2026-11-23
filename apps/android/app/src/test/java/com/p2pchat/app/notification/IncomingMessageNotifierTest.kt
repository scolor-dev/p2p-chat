package com.p2pchat.app.notification

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.message.InMemoryContacts
import com.p2pchat.app.data.message.Message
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeMessageNotifier : MessageNotifier {
    data class Shown(val deviceId: String, val name: String, val body: String)

    val shown = mutableListOf<Shown>()
    val cancelled = mutableListOf<String>()
    override fun show(deviceId: String, senderName: String, body: String, timestamp: Long) {
        shown += Shown(deviceId, senderName, body)
    }

    override fun cancel(deviceId: String) {
        cancelled += deviceId
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingMessageNotifierTest {
    private val received = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    private val foreground = AppForegroundState()
    private val tracker = DefaultActiveChatTracker()
    private val notifier = FakeMessageNotifier()
    private val contacts = InMemoryContacts(Contact("a1", "Alice", "", false, 1L))

    private fun TestScope.start() = IncomingMessageNotifier(
        received, contacts, foreground, tracker, notifier, TestScope(UnconfinedTestDispatcher(testScheduler)),
    ).start()

    private fun incoming(deviceId: String = "a1", body: String = "hi", direction: MessageDirection = MessageDirection.RECEIVED) =
        Message("m-$body", deviceId, direction, body, MessageStatus.SENT, 5L)

    @Test
    fun 背面で届いたら連絡先の名前と本文で通知する() = runTest {
        start()

        received.emit(incoming(body = "こんにちは"))

        assertEquals(listOf(FakeMessageNotifier.Shown("a1", "Alice", "こんにちは")), notifier.shown)
    }

    @Test
    fun 前面で同じ相手のチャットを開いていれば通知しない() = runTest {
        start()
        foreground.set(true)
        tracker.opened("a1")

        received.emit(incoming())

        assertEquals(emptyList<FakeMessageNotifier.Shown>(), notifier.shown)
    }

    @Test
    fun 前面でも一覧など別の画面なら通知する() = runTest {
        start()
        foreground.set(true)

        received.emit(incoming())

        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun 未知の相手と自分の送信分は通知しない() = runTest {
        start()

        received.emit(incoming(deviceId = "zzz"))
        received.emit(incoming(direction = MessageDirection.SENT))

        assertEquals(emptyList<FakeMessageNotifier.Shown>(), notifier.shown)
    }

    @Test
    fun 前面でその相手のチャットを開いたらその相手の通知を消す() = runTest {
        start()
        foreground.set(true)

        tracker.opened("a1")

        assertEquals(listOf("a1"), notifier.cancelled)
    }

    @Test
    fun 背面ではチャットを開いていても通知を消さない() = runTest {
        start()
        tracker.opened("a1")

        assertEquals(emptyList<String>(), notifier.cancelled)
    }

    @Test
    fun ActiveChatTrackerは閉じた相手が違えば何もしない() {
        val t = DefaultActiveChatTracker()
        t.opened("a1")
        t.closed("b2")
        assertEquals("a1", t.activeDeviceId.value)
        t.closed("a1")
        assertNull(t.activeDeviceId.value)
    }
}
