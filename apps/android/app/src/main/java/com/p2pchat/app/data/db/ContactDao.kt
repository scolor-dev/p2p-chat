package com.p2pchat.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name, deviceId")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId")
    suspend fun getByDeviceId(deviceId: String): ContactEntity?

    /** あれば更新、なければ追加する。 */
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET name = :name WHERE deviceId = :deviceId")
    suspend fun updateName(deviceId: String, name: String)

    @Query("UPDATE contacts SET isVerified = :isVerified WHERE deviceId = :deviceId")
    suspend fun updateVerified(deviceId: String, isVerified: Boolean)

    @Query("DELETE FROM contacts WHERE deviceId = :deviceId")
    suspend fun deleteByDeviceId(deviceId: String)
}
