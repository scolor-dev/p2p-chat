package com.p2pchat.app.data.message

import com.p2pchat.app.data.peer.PeerConnector
import com.p2pchat.app.data.peer.PeerSession
import com.p2pchat.app.data.peer.PeerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 既読を相手に伝える常駐の部品。アプリの起動時に [start] する。
 *
 * 既読位置（[ReadStateRepository.observeReadPositions]、#60）が、「read を伝え終えた位置」より進んでいる相手について、
 * その間に受信したメッセージの id を `read { ids }` で送る。相手との接続が open でなければ送らず、
 * 次に open になったとき（チャットを開いて接続した・再接続した）に、未送信の分をまとめて送る。
 * 送り終えたら位置を [ReadStateRepository.markReceiptSent] で記録する（送れなかった分は記録しないので、やり直しになる）。
 * `read` は `ack` と同じく id のみで、暗号化しない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadReceiptSender(
    private val peerConnector: PeerConnector,
    private val messageRepository: MessageRepository,
    private val readStateRepository: ReadStateRepository,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** 1 回だけ有効。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(readStateRepository.observeReadPositions(), openSessions()) { positions, open -> positions to open }
                .collect { (positions, open) ->
                    for ((deviceId, session) in open) {
                        val readUpTo = positions[deviceId] ?: continue
                        try {
                            flush(session, deviceId, readUpTo)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 1 人分の失敗で止めない。次の変化（既読位置・接続）でやり直す。
                        }
                    }
                }
        }
    }

    /** いま open の session（相手の端末 ID 付き）。 */
    private fun openSessions(): Flow<Map<String, PeerSession>> =
        peerConnector.sessions.flatMapLatest { sessions ->
            if (sessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(sessions.map { (id, session) -> session.state.map { state -> id to (session to state) } }) { entries ->
                    entries.filter { (_, v) -> v.second == PeerState.Open }.associate { (id, v) -> id to v.first }
                }
            }
        }.distinctUntilChanged()

    private suspend fun flush(session: PeerSession, deviceId: String, readUpTo: Long) {
        val sentUpTo = readStateRepository.getReceiptSentUpTo(deviceId) ?: Long.MIN_VALUE
        if (readUpTo <= sentUpTo) return
        val ids = messageRepository.observeMessages(deviceId).first()
            .filter { it.direction == MessageDirection.RECEIVED && it.sentAt > sentUpTo && it.sentAt <= readUpTo }
            .map { it.id }
        for (chunk in ids.chunked(ChatFrameCodec.MAX_READ_IDS)) {
            if (!session.send(ChatFrameCodec.encode(ChatFrame.Read(chunk)))) return
        }
        readStateRepository.markReceiptSent(deviceId, readUpTo)
    }
}
