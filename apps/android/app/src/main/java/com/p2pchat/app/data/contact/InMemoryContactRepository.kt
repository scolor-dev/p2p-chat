package com.p2pchat.app.data.contact

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 固定のデータを返す見本の実装。 */
class InMemoryContactRepository : ContactRepository {
    private val contacts = MutableStateFlow(
        listOf(
            Contact(deviceId = "a1b2c3d4e5f60718", name = "Alice"),
            Contact(deviceId = "0f1e2d3c4b5a6978", name = "Bob"),
            Contact(deviceId = "1122334455667788", name = "Carol"),
        )
    )

    override fun observeContacts(): Flow<List<Contact>> = contacts

    override suspend fun getContact(deviceId: String): Contact? {
        return contacts.value.find { it.deviceId == deviceId }
    }

    override suspend fun saveContact(contact: Contact) {
        contacts.update { list ->
            val index = list.indexOfFirst { it.deviceId == contact.deviceId }
            if (index >= 0) {
                list.toMutableList().apply { set(index, contact) }
            } else {
                list + contact
            }
        }
    }

    override suspend fun deleteContact(deviceId: String) {
        contacts.update { list -> list.filterNot { it.deviceId == deviceId } }
    }
}
