package com.p2pchat.app.data.message

import com.p2pchat.app.data.db.ReadStateDao
import com.p2pchat.app.data.db.ReadStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「read を伝え終えた位置」は、既読位置と同じ表に `receipt:<deviceId>` のキーで置く。
 * テーブルを足す・列を足すと DB の version を上げることになり、いまは破壊的マイグレーション（全データ消去）になってしまうため。
 * 端末 ID は UUID なので、キーがぶつかることは無い。
 */
class RoomReadStateRepository(private val dao: ReadStateDao) : ReadStateRepository {
    private val lock = Mutex()

    override fun observeReadPositions(): Flow<Map<String, Long>> =
        dao.observeAll().map { list ->
            list.filterNot { it.deviceId.startsWith(RECEIPT_PREFIX) }.associate { it.deviceId to it.readUpTo }
        }

    override suspend fun markRead(deviceId: String, readUpTo: Long) = advance(deviceId, readUpTo)

    override suspend fun getReceiptSentUpTo(deviceId: String): Long? = dao.getReadUpTo(RECEIPT_PREFIX + deviceId)

    override suspend fun markReceiptSent(deviceId: String, sentUpTo: Long) = advance(RECEIPT_PREFIX + deviceId, sentUpTo)

    private suspend fun advance(key: String, position: Long) = lock.withLock {
        val current = dao.getReadUpTo(key)
        if (current == null || position > current) dao.upsert(ReadStateEntity(key, position))
    }

    private companion object {
        const val RECEIPT_PREFIX = "receipt:"
    }
}
