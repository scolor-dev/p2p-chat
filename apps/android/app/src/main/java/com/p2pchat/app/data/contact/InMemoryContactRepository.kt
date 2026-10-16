package com.p2pchat.app.data.contact

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
}
