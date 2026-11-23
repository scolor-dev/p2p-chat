package com.p2pchat.app.data.message

import com.p2pchat.app.data.db.ReadStateDao
import com.p2pchat.app.data.db.ReadStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadStateTest {
    private class FakeReadStateDao : ReadStateDao {
        val rows = MutableStateFlow<Map<String, Long>>(emptyMap())
        override fun observeAll(): Flow<List<ReadStateEntity>> = rows.map { m -> m.map { ReadStateEntity(it.key, it.value) } }
        override suspend fun getReadUpTo(deviceId: String): Long? = rows.value[deviceId]
        override suspend fun upsert(state: ReadStateEntity) { rows.value = rows.value + (state.deviceId to state.readUpTo) }
    }

    private fun msg(id: String, deviceId: String, direction: MessageDirection, at: Long) =
        Message(id, deviceId, direction, "b", MessageStatus.SENT, at)

    @Test
    fun 既読位置を保存して取得できる() = runTest {
        val repo = RoomReadStateRepository(FakeReadStateDao())

        repo.markRead("a1", 100)
        repo.markRead("b2", 200)

        assertEquals(mapOf("a1" to 100L, "b2" to 200L), repo.observeReadPositions().first())
    }

    @Test
    fun 既読位置は戻らない() = runTest {
        val repo = RoomReadStateRepository(FakeReadStateDao())

        repo.markRead("a1", 100)
        repo.markRead("a1", 50)

        assertEquals(100L, repo.observeReadPositions().first().getValue("a1"))
    }

    @Test
    fun 受信のみの相手は未読() {
        assertEquals(setOf("a1"), unreadDeviceIds(listOf(msg("1", "a1", MessageDirection.RECEIVED, 10)), emptyMap()))
    }

    @Test
    fun 送信のみの相手は未読にならない() {
        assertEquals(emptySet<String>(), unreadDeviceIds(listOf(msg("1", "a1", MessageDirection.SENT, 10)), emptyMap()))
    }

    @Test
    fun 既読位置と同じか古い受信は未読にならず新着は未読() {
        val latest = listOf(msg("1", "a1", MessageDirection.RECEIVED, 10))
        assertEquals(emptySet<String>(), unreadDeviceIds(latest, mapOf("a1" to 10L)))
        assertEquals(emptySet<String>(), unreadDeviceIds(latest, mapOf("a1" to 20L)))
        assertEquals(setOf("a1"), unreadDeviceIds(latest, mapOf("a1" to 9L)))
    }

    @Test
    fun 複数の相手は別々に判定される() {
        val latest = listOf(
            msg("1", "a1", MessageDirection.RECEIVED, 10),
            msg("2", "b2", MessageDirection.RECEIVED, 20),
            msg("3", "c3", MessageDirection.RECEIVED, 30),
        )
        assertEquals(setOf("b2"), unreadDeviceIds(latest, mapOf("a1" to 10L, "c3" to 30L)))
    }
}
