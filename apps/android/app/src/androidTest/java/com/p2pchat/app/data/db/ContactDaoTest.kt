package com.p2pchat.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.contactDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetContact() = runBlocking {
        val entity = ContactEntity(
            deviceId = "dev-101",
            name = "Alice",
            publicKey = "key123",
            isVerified = true,
            createdAt = 1000L,
        )
        dao.upsert(entity)

        val retrieved = dao.getByDeviceId("dev-101")
        assertEquals(entity, retrieved)
    }

    @Test
    fun updateNameOnly() = runBlocking {
        val entity = ContactEntity(
            deviceId = "dev-103",
            name = "Alice",
            publicKey = "key123",
            isVerified = true,
            createdAt = 1000L,
        )
        dao.upsert(entity)
        dao.updateName("dev-103", "Alice Renamed")

        val retrieved = dao.getByDeviceId("dev-103")
        assertEquals("Alice Renamed", retrieved?.name)
        assertEquals("key123", retrieved?.publicKey)
        assertEquals(true, retrieved?.isVerified)
    }

    @Test
    fun deleteContact() = runBlocking {
        val entity = ContactEntity(
            deviceId = "dev-102",
            name = "Bob",
            publicKey = "key456",
            isVerified = false,
            createdAt = 2000L,
        )
        dao.upsert(entity)
        dao.deleteByDeviceId("dev-102")

        val retrieved = dao.getByDeviceId("dev-102")
        assertNull(retrieved)
    }

    @Test
    fun observeAllSortedByNameAndDeviceId() = runBlocking {
        val bob = ContactEntity("dev-202", "Bob", "k2", false, 100L)
        val alice2 = ContactEntity("dev-201b", "Alice", "k1b", false, 100L)
        val alice1 = ContactEntity("dev-201a", "Alice", "k1a", true, 100L)

        dao.upsert(bob)
        dao.upsert(alice2)
        dao.upsert(alice1)

        val list = dao.observeAll().first()
        assertEquals(3, list.size)
        assertEquals("dev-201a", list[0].deviceId)
        assertEquals("dev-201b", list[1].deviceId)
        assertEquals("dev-202", list[2].deviceId)
    }
}
