package com.p2pchat.app.data.message

import com.p2pchat.app.data.db.MessageDao
import com.p2pchat.app.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RoomMessageRepository] と [MessageEntity] の変換を、Room を使わずに確かめる（DAO は Fake）。 */
class RoomMessageRepositoryTest {

    /** 主キー（id）で 1 件にまとめる、Room の `@Upsert` と同じ振る舞いの Fake。 */
    private class FakeMessageDao : MessageDao {
        val rows = MutableStateFlow<Map<String, MessageEntity>>(emptyMap())

        override fun observeMessages(deviceId: String): Flow<List<MessageEntity>> =
            rows.map { map ->
                map.values.filter { it.deviceId == deviceId }.sortedWith(compareBy({ it.sentAt }, { it.id }))
            }

        override suspend fun upsert(message: MessageEntity) {
            rows.value = rows.value + (message.id to message)
        }
    }

    private fun message(
        id: String,
        deviceId: String = "peer1",
        sentAt: Long = 1000L,
        direction: MessageDirection = MessageDirection.SENT,
        status: MessageStatus = MessageStatus.SENDING,
    ) = Message(id, deviceId, direction, "こんにちは", status, sentAt)

    @Test
    fun 保存したメッセージを相手ごとに時系列で取得できる() = runTest {
        val repository = RoomMessageRepository(FakeMessageDao())
        repository.upsertMessage(message("m2", sentAt = 2000L))
        repository.upsertMessage(message("m1", sentAt = 1000L))
        repository.upsertMessage(message("other", deviceId = "peer2"))

        val result = repository.observeMessages("peer1").first()

        assertEquals(listOf("m1", "m2"), result.map { it.id })
    }

    @Test
    fun 同じidを2回保存しても1件になる() = runTest {
        val repository = RoomMessageRepository(FakeMessageDao())

        repository.upsertMessage(message("m1"))
        repository.upsertMessage(message("m1"))

        assertEquals(1, repository.observeMessages("peer1").first().size)
    }

    @Test
    fun 同じidで保存し直すと送信状態が更新される() = runTest {
        val repository = RoomMessageRepository(FakeMessageDao())
        repository.upsertMessage(message("m1", status = MessageStatus.SENDING))

        repository.upsertMessage(message("m1", status = MessageStatus.SENT))

        assertEquals(MessageStatus.SENT, repository.observeMessages("peer1").first().single().status)
    }

    @Test
    fun すべての項目がそのまま往復する() {
        val original = message("m1", direction = MessageDirection.RECEIVED, status = MessageStatus.FAILED)

        assertEquals(original, MessageEntity.fromDomain(original).toDomain())
    }

    @Test
    fun DBに知らない値があっても読み出せる() {
        val entity = MessageEntity("m1", "peer1", direction = "???", body = "x", status = "???", sentAt = 1L)

        val domain = entity.toDomain()

        // 壊れた値で一覧が読めなくならないよう、方向は送信、状態は失敗として扱う
        assertEquals(MessageDirection.SENT, domain.direction)
        assertEquals(MessageStatus.FAILED, domain.status)
        assertTrue(domain.body == "x")
    }
}
