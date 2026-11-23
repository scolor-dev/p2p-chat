package com.p2pchat.app.data.message

import kotlinx.coroutines.flow.Flow

/**
 * メッセージの取得・保存を行うリポジトリ。
 */
interface MessageRepository {
    /** 指定したデバイス ID のメッセージ一覧を監視する。 */
    fun observeMessages(deviceId: String): Flow<List<Message>>

    /** メッセージを追加または更新する。 */
    suspend fun upsertMessage(message: Message)
}