package com.p2pchat.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.messageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun entity(id: String, deviceId: String = "peer1", sentAt: Long = 1000L, status: String = "SENDING") =
        MessageEntity(id, deviceId, direction = "SENT", body = "こんにちは", status = status, sentAt = sentAt)

    @Test
    fun 追加したメッセージを相手ごとに時系列で取得できる() = runBlocking {
        dao.upsert(entity("m2", sentAt = 2000L))
        dao.upsert(entity("m1", sentAt = 1000L))
        dao.upsert(entity("other", deviceId = "peer2"))

        val result = dao.observeMessages("peer1").first()

        assertEquals(listOf("m1", "m2"), result.map { it.id })
    }

    @Test
    fun 同じidを2回保存しても1件になる() = runBlocking {
        dao.upsert(entity("m1"))
        dao.upsert(entity("m1"))

        assertEquals(1, dao.observeMessages("peer1").first().size)
    }

    @Test
    fun 同じidで保存し直すと内容が更新される() = runBlocking {
        dao.upsert(entity("m1", status = "SENDING"))
        dao.upsert(entity("m1", status = "SENT"))

        assertEquals("SENT", dao.observeMessages("peer1").first().single().status)
    }
}
