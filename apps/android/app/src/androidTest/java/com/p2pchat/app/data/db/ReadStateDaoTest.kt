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
class ReadStateDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ReadStateDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.readStateDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun 既読位置を保存して上書きできる() = runBlocking {
        assertNull(dao.getReadUpTo("peer1"))

        dao.upsert(ReadStateEntity("peer1", 100L))
        dao.upsert(ReadStateEntity("peer1", 200L))
        dao.upsert(ReadStateEntity("peer2", 50L))

        assertEquals(200L, dao.getReadUpTo("peer1"))
        assertEquals(mapOf("peer1" to 200L, "peer2" to 50L), dao.observeAll().first().associate { it.deviceId to it.readUpTo })
    }
}
