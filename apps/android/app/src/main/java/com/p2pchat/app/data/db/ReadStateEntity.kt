package com.p2pchat.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 相手ごとの既読位置（読んだ最後のメッセージの sentAt）。 */
@Entity(tableName = "read_states")
data class ReadStateEntity(
    @PrimaryKey val deviceId: String,
    val readUpTo: Long,
)
