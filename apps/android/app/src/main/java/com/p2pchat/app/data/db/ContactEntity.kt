package com.p2pchat.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** DB の連絡先 1 行。最小の形で、確認済みかどうかなどは #19 で足す。 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
)
