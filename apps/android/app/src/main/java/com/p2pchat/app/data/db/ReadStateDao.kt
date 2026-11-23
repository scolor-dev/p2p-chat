package com.p2pchat.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadStateDao {
    @Query("SELECT * FROM read_states")
    fun observeAll(): Flow<List<ReadStateEntity>>

    @Query("SELECT readUpTo FROM read_states WHERE deviceId = :deviceId")
    suspend fun getReadUpTo(deviceId: String): Long?

    @Upsert
    suspend fun upsert(state: ReadStateEntity)
}
