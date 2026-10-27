package com.p2pchat.app.data.contact

import com.p2pchat.app.data.db.ContactDao
import com.p2pchat.app.data.db.toDomain
import com.p2pchat.app.data.db.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Room DB を使用する [ContactRepository] の実装。
 */
class OfflineContactRepository(
    private val contactDao: ContactDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ContactRepository {

    override fun observeContacts(): Flow<List<Contact>> {
        return contactDao.observeAll()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)
    }

    override suspend fun getContact(deviceId: String): Contact? {
        return contactDao.getByDeviceId(deviceId)?.toDomain()
    }

    override suspend fun saveContact(contact: Contact) {
        val updatedContact = if (contact.createdAt <= 0L) {
            contact.copy(createdAt = System.currentTimeMillis())
        } else {
            contact
        }
        contactDao.upsert(updatedContact.toEntity())
    }

    override suspend fun deleteContact(deviceId: String) {
        contactDao.deleteByDeviceId(deviceId)
    }
}
