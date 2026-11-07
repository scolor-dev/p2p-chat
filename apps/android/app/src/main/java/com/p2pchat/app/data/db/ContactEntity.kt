package com.p2pchat.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.p2pchat.app.data.contact.Contact

/** DB の連絡先 1 行。 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val publicKey: String,
    val isVerified: Boolean,
    val createdAt: Long,
)

/** Entity から Domain Model への変換 */
fun ContactEntity.toDomain(): Contact = Contact(
    deviceId = deviceId,
    name = name,
    publicKey = publicKey,
    isVerified = isVerified,
    createdAt = createdAt,
)

/** Domain Model から Entity への変換 */
fun Contact.toEntity(): ContactEntity = ContactEntity(
    deviceId = deviceId,
    name = name,
    publicKey = publicKey,
    isVerified = isVerified,
    createdAt = createdAt,
)
