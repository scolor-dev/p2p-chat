package com.p2pchat.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.p2pchat.app.data.message.Message
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageStatus

/** DB のメッセージ 1 行（テーブル定義）。 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val direction: String,
    val body: String,
    val status: String,
    val sentAt: Long,
) {
    /** DBの保存形式から、画面で使うドメインモデル（Message）へ変換する */
    fun toDomain(): Message = Message(
        id = id,
        deviceId = deviceId,
        direction = runCatching { MessageDirection.valueOf(direction) }.getOrDefault(MessageDirection.SENT),
        body = body,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.SENDING),
        sentAt = sentAt,
    )

    companion object {
        /** ドメインモデル（Message）から、DB保存用のEntityへ変換する */
        fun fromDomain(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            deviceId = message.deviceId,
            direction = message.direction.name,
            body = message.body,
            status = message.status.name,
            sentAt = message.sentAt,
        )
    }
}