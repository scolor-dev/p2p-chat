package com.p2pchat.app.data.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectBackoffTest {

    @Test
    fun 失敗が続くほど倍に延び30秒で止まる() {
        val backoff = ReconnectBackoff(jitter = 0.0)

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map(backoff::delayMillis),
        )
    }

    @Test
    fun 何度失敗しても上限を超えない() {
        val backoff = ReconnectBackoff(jitter = 0.0)
        assertEquals(30_000L, backoff.delayMillis(1_000))
    }

    @Test
    fun ばらつきは上下20パーセントの範囲に収まる() {
        val backoff = ReconnectBackoff(random = Random(42))

        repeat(1_000) {
            val delay = backoff.delayMillis(3) // 基準は 4 秒
            assertTrue("$delay", delay in 3_200L..4_800L)
        }
    }
}
