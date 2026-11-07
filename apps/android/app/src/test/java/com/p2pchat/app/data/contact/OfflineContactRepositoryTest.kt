package com.p2pchat.app.data.contact

import com.p2pchat.app.data.db.ContactDao
import com.p2pchat.app.data.db.ContactEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineContactRepository] の単体テスト。
 * [FakeContactDao] を使用して JVM 上で高速・確実に検証する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineContactRepositoryTest {

    private class FakeContactDao : ContactDao {
        private val contacts = MutableStateFlow<Map<String, ContactEntity>>(emptyMap())

        override fun observeAll(): Flow<List<ContactEntity>> {
            return contacts.map { map ->
                map.values.sortedWith(compareBy({ it.name }, { it.deviceId }))
            }
        }

        override suspend fun getByDeviceId(deviceId: String): ContactEntity? {
            return contacts.value[deviceId]
        }

        override suspend fun upsert(contact: ContactEntity) {
            contacts.value = contacts.value + (contact.deviceId to contact)
        }

        override suspend fun updateName(deviceId: String, name: String) {
            val existing = contacts.value[deviceId] ?: return
            contacts.value = contacts.value + (deviceId to existing.copy(name = name))
        }

        override suspend fun updateVerified(deviceId: String, isVerified: Boolean) {
            val existing = contacts.value[deviceId] ?: return
            contacts.value = contacts.value + (deviceId to existing.copy(isVerified = isVerified))
        }

        override suspend fun deleteByDeviceId(deviceId: String) {
            contacts.value = contacts.value - deviceId
        }
    }

    @Test
    fun 連絡先を保存して一覧を監視取得できる() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeContactDao()
        val repository = OfflineContactRepository(dao, testDispatcher)

        val alice = Contact(
            deviceId = "dev-001",
            name = "Alice",
            publicKey = "pubkey123",
            isVerified = true,
            createdAt = 1000L,
        )
        repository.saveContact(alice)

        val contactsList = repository.observeContacts().first()
        assertEquals(1, contactsList.size)
        assertEquals(alice, contactsList[0])
    }

    @Test
    fun getContactで特定の連絡先を取得できる() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeContactDao()
        val repository = OfflineContactRepository(dao, testDispatcher)

        val bob = Contact(
            deviceId = "dev-002",
            name = "Bob",
            publicKey = "pubkey456",
            isVerified = false,
            createdAt = 2000L,
        )
        repository.saveContact(bob)

        val retrieved = repository.getContact("dev-002")
        assertEquals(bob, retrieved)

        val notFound = repository.getContact("unknown")
        assertNull(notFound)
    }

    @Test
    fun 既存の連絡先を更新できる() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeContactDao()
        val repository = OfflineContactRepository(dao, testDispatcher)

        val initial = Contact(
            deviceId = "dev-001",
            name = "Alice",
            publicKey = "pubkey123",
            isVerified = false,
            createdAt = 1000L,
        )
        repository.saveContact(initial)

        val updated = initial.copy(name = "Alice Updated", isVerified = true)
        repository.saveContact(updated)

        val retrieved = repository.getContact("dev-001")
        assertEquals("Alice Updated", retrieved?.name)
        assertEquals(true, retrieved?.isVerified)
    }

    @Test
    fun 名前のみ部分更新できる() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeContactDao()
        val repository = OfflineContactRepository(dao, testDispatcher)

        val initial = Contact(
            deviceId = "dev-001",
            name = "Alice",
            publicKey = "pubkey123",
            isVerified = true,
            createdAt = 1000L,
        )
        repository.saveContact(initial)

        repository.updateName("dev-001", "Alice New Name")

        val retrieved = repository.getContact("dev-001")
        assertEquals("Alice New Name", retrieved?.name)
        assertEquals("pubkey123", retrieved?.publicKey)
        assertEquals(true, retrieved?.isVerified)
    }

    @Test
    fun 連絡先を削除できる() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeContactDao()
        val repository = OfflineContactRepository(dao, testDispatcher)

        val carol = Contact(deviceId = "dev-003", name = "Carol", publicKey = "key", isVerified = false, createdAt = 1000L)
        repository.saveContact(carol)

        repository.deleteContact("dev-003")

        val contactsList = repository.observeContacts().first()
        assertEquals(0, contactsList.size)
        assertNull(repository.getContact("dev-003"))
    }

    @Test
    fun createdAtが未設定の場合は自動で現在時刻が補填される() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = FakeContactDao()
        val repository = OfflineContactRepository(dao, testDispatcher)

        val contactWithoutTimestamp = Contact(
            deviceId = "dev-004",
            name = "Dave",
            publicKey = "key",
            isVerified = false,
            createdAt = 0L,
        )
        repository.saveContact(contactWithoutTimestamp)

        val retrieved = repository.getContact("dev-004")
        assertNotNull(retrieved)
        assertTrue(retrieved!!.createdAt > 0L)
    }
}
