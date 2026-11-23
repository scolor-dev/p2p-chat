package com.p2pchat.app.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.message.ChatMessageSender
import com.p2pchat.app.data.message.InMemoryContacts
import com.p2pchat.app.data.message.InMemoryMessageRepository
import com.p2pchat.app.data.peer.FakePeerConnector
import com.p2pchat.app.notification.DefaultActiveChatTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ChatViewModelActiveChatTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sender = object : ChatMessageSender {
        override suspend fun send(deviceId: String, body: String): Result<Unit> = Result.success(Unit)
        override suspend fun resend(deviceId: String, id: String): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun 開いたらアクティブになり閉じたら外れる() {
        val tracker = DefaultActiveChatTracker()
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    "a1", InMemoryContacts(), InMemoryMessageRepository(), sender, FakePeerConnector(),
                    activeChatTracker = tracker,
                )
            }
        }

        ViewModelProvider.create(store, factory)[ChatViewModel::class.java]
        assertEquals("a1", tracker.activeDeviceId.value)

        store.clear()
        assertNull(tracker.activeDeviceId.value)
    }
}
