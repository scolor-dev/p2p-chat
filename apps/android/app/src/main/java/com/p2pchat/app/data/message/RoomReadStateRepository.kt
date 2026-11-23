package com.p2pchat.app.data.message

import com.p2pchat.app.data.db.ReadStateDao
import com.p2pchat.app.data.db.ReadStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomReadStateRepository(private val dao: ReadStateDao) : ReadStateRepository {
    private val lock = Mutex()

    override fun observeReadPositions(): Flow<Map<String, Long>> =
        dao.observeAll().map { list -> list.associate { it.deviceId to it.readUpTo } }

    override suspend fun markRead(deviceId: String, readUpTo: Long) = lock.withLock {
        val current = dao.getReadUpTo(deviceId)
        if (current == null || readUpTo > current) dao.upsert(ReadStateEntity(deviceId, readUpTo))
    }
}
