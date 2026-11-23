package com.p2pchat.app.data.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 相手ごとの「どこまで読んだか」（既読位置）の保存先。
 *
 * 既読位置は、読んだ最後のメッセージの `sentAt`（エポックミリ秒）。受信メッセージの sentAt は
 * 受信した端末の時計で付けるので、相手の時計のずれには影響されない。
 */
interface ReadStateRepository {
    /** 相手の端末 ID ごとの既読位置。一度も読んでいない相手は含まれない。 */
    fun observeReadPositions(): Flow<Map<String, Long>>

    /** [deviceId] の既読位置を [readUpTo] まで進める。いまの位置以前を渡しても戻さない。 */
    suspend fun markRead(deviceId: String, readUpTo: Long)
}

/**
 * 未読のある相手の端末 ID を返す。
 *
 * 相手から受信したメッセージ（[MessageDirection.RECEIVED]）のうち、既読位置より新しいものがある相手が未読。
 * 自分が送っただけの相手には付かない。
 *
 * @param latestReceived 相手ごとの、最新の受信メッセージ
 * @param readPositions 相手ごとの既読位置（無ければ、まだ何も読んでいない）
 */
fun unreadDeviceIds(latestReceived: List<Message>, readPositions: Map<String, Long>): Set<String> =
    latestReceived
        .filter { it.direction == MessageDirection.RECEIVED && it.sentAt > (readPositions[it.deviceId] ?: Long.MIN_VALUE) }
        .mapTo(mutableSetOf()) { it.deviceId }

/** 既読を保存しない実装。ChatViewModel の引数の既定値（既読化が要らないテスト用）。 */
object NoOpReadStateRepository : ReadStateRepository {
    override fun observeReadPositions(): Flow<Map<String, Long>> = flowOf(emptyMap())
    override suspend fun markRead(deviceId: String, readUpTo: Long) = Unit
}
