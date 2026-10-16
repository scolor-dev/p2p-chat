package com.p2pchat.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name")
    fun observeAll(): Flow<List<ContactEntity>>

    /** あれば更新、なければ追加する。 */
    @Upsert
    suspend fun upsert(contact: ContactEntity)
}
