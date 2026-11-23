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

    /** 相手ごとの最新の 1 件（sentAt が最大。同じなら id が大きいほう。[observeMessages] の並びの最後と同じ）。 */
    @Query(
        "SELECT * FROM messages m1 WHERE NOT EXISTS (" +
            "SELECT 1 FROM messages m2 WHERE m2.deviceId = m1.deviceId " +
            "AND (m2.sentAt > m1.sentAt OR (m2.sentAt = m1.sentAt AND m2.id > m1.id)))",
    )
    fun observeLatestMessages(): Flow<List<MessageEntity>>

    /** 相手ごとの最新の受信メッセージ 1 件（未読の判定用）。 */
    @Query(
        "SELECT * FROM messages m1 WHERE m1.direction = 'RECEIVED' AND NOT EXISTS (" +
            "SELECT 1 FROM messages m2 WHERE m2.deviceId = m1.deviceId AND m2.direction = 'RECEIVED' " +
            "AND (m2.sentAt > m1.sentAt OR (m2.sentAt = m1.sentAt AND m2.id > m1.id)))",
    )
    fun observeLatestReceivedMessages(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = 'FAILED' WHERE status = 'SENDING' AND direction = 'SENT' AND sentAt < :olderThan")
    suspend fun failSendingBefore(olderThan: Long): Int
}