package com.p2pchat.app.data.message

import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.EncryptedMessage
import com.p2pchat.app.data.crypto.MessageCipher
import com.p2pchat.app.data.crypto.MessageCryptoException
import com.p2pchat.app.data.crypto.PeerKey
import com.p2pchat.app.data.crypto.PublicKeyEncoder
import com.p2pchat.app.data.peer.PeerConnector
import com.p2pchat.app.data.peer.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全ての [PeerSession]（自分から接続したもの、相手の offer を受け入れたもの。どちらも
 * [PeerConnector.sessions] に載る）の `incoming` を購読し続ける常駐の部品。アプリの起動時に [start] する。
 *
 * - `msg`: 復号 → [MessageRepository.upsertMessage]（RECEIVED / SENT）→ `ack`。同じ `id` が再び届いたら保存せず `ack` だけ返す。
 * - `ack`: [OutgoingMessageTracker.onAckReceived]。
 * - 復号できない `msg`（鍵の不一致・改ざん・壊れた nonce / ct）は、捨てたうえで [decryptFailures] に相手の端末 ID を流す
 *   （画面の警告用。内容は流さない）。
 * - 復号できない `msg`・壊れた JSON・知らない type・16 KiB 超のフレーム・連絡先にいない相手・4096 バイト超の本文は、
 *   保存も `ack` もせず捨てる（クラッシュしない）。
 */
class IncomingMessageHandler(
    private val peerConnector: PeerConnector,
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    private val cipher: MessageCipher,
    private val tracker: OutgoingMessageTracker,
    private val scope: CoroutineScope,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val started = AtomicBoolean(false)

    private val _decryptFailures = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 復号に失敗して捨てた `msg` の送り主（相手の端末 ID）。購読していない間のものは捨てる。 */
    val decryptFailures: SharedFlow<String> = _decryptFailures.asSharedFlow()

    /** 1 回だけ有効。2 回目以降は何もしない。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val watching = HashSet<PeerSession>() // 同じ session を二重に購読しない
            peerConnector.sessions.collect { sessions ->
                watching.retainAll(sessions.values.toSet())
                for (session in sessions.values) {
                    if (watching.add(session)) launch { watch(session) }
                }
            }
        }
    }

    private suspend fun watch(session: PeerSession) {
        // PeerSession は購読前のフレームを捨てるので、すぐ購読する。処理中に届く分は buffer で受ける。
        session.incoming.buffer(INCOMING_BUFFER).collect { text ->
            try {
                handle(session, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 1 フレームの失敗で購読を止めない。内容（平文・鍵）はどこにも出さない。
            }
        }
    }

    private suspend fun handle(session: PeerSession, text: String) {
        when (val frame = ChatFrameCodec.decode(text) ?: return) {
            is ChatFrame.Ack -> tracker.onAckReceived(session.peerDeviceId, frame.id)
            is ChatFrame.Msg -> handleMsg(session, frame)
        }
    }

    private suspend fun handleMsg(session: PeerSession, frame: ChatFrame.Msg) {
        val deviceId = session.peerDeviceId
        val existing = messageRepository.getMessage(frame.id)
        if (existing != null) {
            // 再送で届いた重複。保存せず ack だけ返す。他の相手のものや自分の送信分と id がぶつかるものは捨てる。
            if (existing.deviceId == deviceId && existing.direction == MessageDirection.RECEIVED) {
                sendAck(session, frame.id)
            }
            return
        }

        val publicKey = contactRepository.getContact(deviceId)?.publicKey?.takeIf { it.isNotBlank() } ?: return
        val body = try {
            val peerKey = PeerKey(deviceId, PublicKeyEncoder.fromBase64(publicKey))
            val plain = withContext(cryptoDispatcher) {
                cipher.decrypt(peerKey, frame.id, EncryptedMessage.fromBase64(frame.nonce, frame.ct))
            }
            if (plain.size > MAX_BODY_BYTES) return
            sanitizeBody(String(plain, Charsets.UTF_8))
        } catch (e: MessageCryptoException) {
            _decryptFailures.tryEmit(deviceId)
            return
        } catch (e: IllegalArgumentException) {
            _decryptFailures.tryEmit(deviceId)
            return
        }
        if (body.isBlank()) return

        messageRepository.upsertMessage(
            Message(frame.id, deviceId, MessageDirection.RECEIVED, body, MessageStatus.SENT, clock()),
        )
        sendAck(session, frame.id)
    }

    private fun sendAck(session: PeerSession, id: String) {
        session.send(ChatFrameCodec.encode(ChatFrame.Ack(id)))
    }

    private companion object {
        const val INCOMING_BUFFER = 256
    }
}
