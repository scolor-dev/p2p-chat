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

    override suspend fun getMessage(id: String): Message? = messageDao.getById(id)?.toDomain()

    override suspend fun updateStatus(id: String, status: MessageStatus) {
        messageDao.updateStatus(id, status.name)
    }

    override fun observeLatestMessages(): Flow<List<Message>> =
        messageDao.observeLatestMessages().map { entities -> entities.map { it.toDomain() } }

    override fun observeLatestReceivedMessages(): Flow<List<Message>> =
        messageDao.observeLatestReceivedMessages().map { entities -> entities.map { it.toDomain() } }

    override suspend fun failSendingBefore(olderThan: Long): Int =
        messageDao.failSendingBefore(olderThan)
}