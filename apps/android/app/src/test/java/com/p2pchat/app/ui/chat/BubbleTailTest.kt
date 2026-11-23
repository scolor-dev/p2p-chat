package com.p2pchat.app.ui.chat

import com.p2pchat.app.data.message.MessageDirection.RECEIVED
import com.p2pchat.app.data.message.MessageDirection.SENT
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTailTest {
    @Test
    fun 空ならしっぽはない() {
        assertEquals(emptyList<Boolean>(), tailFlags(emptyList()))
    }

    @Test
    fun 一件だけならしっぽが付く() {
        assertEquals(listOf(true), tailFlags(listOf(RECEIVED)))
    }

    @Test
    fun 同じ方向が続く間は最後だけしっぽが付く() {
        assertEquals(
            listOf(false, false, true),
            tailFlags(listOf(RECEIVED, RECEIVED, RECEIVED)),
        )
    }

    @Test
    fun 方向が変わるたびにその前のメッセージにしっぽが付く() {
        assertEquals(
            listOf(true, true, false, true, false, true),
            tailFlags(listOf(RECEIVED, SENT, RECEIVED, RECEIVED, SENT, SENT)),
        )
    }
}
