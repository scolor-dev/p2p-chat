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

    /** [id] のメッセージを返す。無ければ null。 */
    suspend fun getMessage(id: String): Message?

    /** [id] のメッセージの送信状態だけを更新する。無ければ何もしない。 */
    suspend fun updateStatus(id: String, status: MessageStatus)
}