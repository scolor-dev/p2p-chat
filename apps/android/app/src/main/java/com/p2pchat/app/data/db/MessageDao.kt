package com.p2pchat.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** 指定したデバイスIDのメッセージ一覧を時系列で取得・監視する */
    @Query("SELECT * FROM messages WHERE deviceId = :deviceId ORDER BY sentAt ASC, id ASC")
    fun observeMessages(deviceId: String): Flow<List<MessageEntity>>

    /** メッセージを追加または更新する。同じ id なら 1 件にまとまる（二重保存防止） */
    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}