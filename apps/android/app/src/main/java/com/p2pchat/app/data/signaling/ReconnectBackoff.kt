package com.p2pchat.app.data.signaling

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 再接続までの待ち時間。失敗が続くほど長くする（1 秒 → 2 秒 → 4 秒 … 最大 30 秒）。
 *
 * サーバーの再起動などで多くの端末が同時に切れたとき、一斉に再接続しないように、
 * ±[jitter] の割合でばらつかせる。
 */
class ReconnectBackoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
    private val multiplier: Double = 2.0,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** [failures] 回続けて失敗したあとの待ち時間（[failures] は 1 以上）。 */
    fun delayMillis(failures: Int): Long {
        require(failures >= 1) { "failures は 1 以上: $failures" }
        val base = min(maxMillis.toDouble(), initialMillis * multiplier.pow(failures - 1))
        val spread = base * jitter * (random.nextDouble() * 2 - 1)
        return (base + spread).toLong().coerceAtLeast(0)
    }
}
