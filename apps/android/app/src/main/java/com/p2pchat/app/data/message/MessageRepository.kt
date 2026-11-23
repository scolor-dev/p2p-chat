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

    /** 相手ごとの最新のメッセージ（送受信どちらも。並びは [observeMessages] と同じ sentAt, id の順で最後のもの）を監視する。 */
    fun observeLatestMessages(): Flow<List<Message>>

    /** 相手ごとの最新の「受信」メッセージ（未読の判定用。自分の送信は含めない）を監視する。 */
    fun observeLatestReceivedMessages(): Flow<List<Message>>

    /**
     * 送信状態が SENDING のまま、[olderThan] より前（sentAt が未満）の自分の送信メッセージを FAILED にして、件数を返す。
     * アプリの再起動をまたいだ SENDING（ack を待つ処理はもう無い）の整理に使う。
     */
    suspend fun failSendingBefore(olderThan: Long): Int
}