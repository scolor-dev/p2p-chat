package com.p2pchat.app.notification

import com.p2pchat.app.data.message.Message
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationPolicyTest {
    private fun message(direction: MessageDirection = MessageDirection.RECEIVED, deviceId: String = "a1") =
        Message("m1", deviceId, direction, "こんにちは", MessageStatus.SENT, 1L)

    private fun should(
        message: Message = message(),
        known: Boolean = true,
        foreground: Boolean = false,
        active: String? = null,
    ) = IncomingNotificationPolicy.shouldNotify(message, known, foreground, active)

    @Test
    fun 背面なら通知する() = assertTrue(should(foreground = false))

    @Test
    fun 背面ならその相手のチャットを開いたままでも通知する() = assertTrue(should(foreground = false, active = "a1"))

    @Test
    fun 前面で別の画面なら通知する() = assertTrue(should(foreground = true, active = null))

    @Test
    fun 前面で別の相手のチャットを開いていれば通知する() = assertTrue(should(foreground = true, active = "b2"))

    @Test
    fun 前面で同じ相手のチャットを開いていれば通知しない() = assertFalse(should(foreground = true, active = "a1"))

    @Test
    fun 自分が送った分は通知しない() = assertFalse(should(message = message(MessageDirection.SENT)))

    @Test
    fun 連絡先にいない相手は通知しない() = assertFalse(should(known = false))
}
