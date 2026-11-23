package com.p2pchat.app.data.message

import com.p2pchat.app.data.db.MessageDao
import com.p2pchat.app.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMessageRepository(
    private val messageDao: MessageDao,
) : MessageRepository {
    override fun observeMessages(deviceId: String): Flow<List<Message>> {
        return messageDao.observeMessages(deviceId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun upsertMessage(message: Message) {
        messageDao.upsert(MessageEntity.fromDomain(message))
    }
}