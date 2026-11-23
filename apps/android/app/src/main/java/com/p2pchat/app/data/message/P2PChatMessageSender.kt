package com.p2pchat.app.data.message

import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.MessageCipher
import com.p2pchat.app.data.crypto.MessageCryptoException
import com.p2pchat.app.data.crypto.PeerKey
import com.p2pchat.app.data.crypto.PublicKeyEncoder
import com.p2pchat.app.data.peer.PeerConnector
import com.p2pchat.app.data.peer.PeerFailure
import com.p2pchat.app.data.peer.PeerSession
import com.p2pchat.app.data.peer.PeerState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/** 送れなかった理由。画面はこれを見て文言を出し分ける（例外のメッセージは画面に出さない）。 */
class ChatSendException(
    val reason: Reason,
    /** [reason] が [Reason.PEER] のときの、接続に失敗した理由。 */
    val peerFailure: PeerFailure? = null,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        /** 制御文字を除くと空になった。 */
        EMPTY_BODY,

        /** 制御文字を除いたあとも UTF-8 で 4096 バイトを超えている。 */
        TOO_LONG,

        /** 連絡先がない、または公開鍵が無い・読めない。 */
        NO_PUBLIC_KEY,

        /** P2P 接続を作れなかった。[peerFailure] に理由。 */
        PEER,

        /** 接続の待ち中に、または送る前に切れた。 */
        DISCONNECTED,

        /** 暗号化できなかった。 */
        ENCRYPT_FAILED,

        /** DataChannel に載せられなかった。 */
        SEND_FAILED,
    }
}

/**
 * [ChatMessageSender] の本実装。整形 → 相手の公開鍵 → PeerSession（無ければ接続して open まで待つ）→
 * SENDING で保存 → 暗号化 → `msg` を DataChannel に送る。送れなければ保存したメッセージを FAILED にする。
 * 状態の更新は [OutgoingMessageTracker] 経由（#28 の差し込み口）。
 */
class P2PChatMessageSender(
    private val contactRepository: ContactRepository,
    private val peerConnector: PeerConnector,
    private val cipher: MessageCipher,
    private val tracker: OutgoingMessageTracker,
    private val messageRepository: MessageRepository,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatMessageSender {

    override suspend fun send(deviceId: String, body: String): Result<Unit> = try {
        sendOrThrow(deviceId, body)
        Result.success(Unit)
    } catch (e: ChatSendException) {
        Result.failure(e)
    }

    override suspend fun resend(deviceId: String, id: String): Result<Unit> = try {
        resendOrThrow(deviceId, id)
        Result.success(Unit)
    } catch (e: ChatSendException) {
        Result.failure(e)
    }

    private suspend fun sendOrThrow(deviceId: String, body: String) {
        val text = sanitizeBody(body)
        if (text.isBlank()) throw ChatSendException(ChatSendException.Reason.EMPTY_BODY)
        if (exceedsBodyLimit(text)) throw ChatSendException(ChatSendException.Reason.TOO_LONG)

        val peerKey = peerKeyOf(deviceId)
        val session = openSession(deviceId)
        transmit(session, peerKey, Message(newId(), deviceId, MessageDirection.SENT, text, MessageStatus.SENDING, clock()))
    }

    private suspend fun resendOrThrow(deviceId: String, id: String) {
        val message = messageRepository.getMessage(id) ?: return
        if (message.deviceId != deviceId || message.direction != MessageDirection.SENT) return
        if (message.status != MessageStatus.FAILED) return

        val peerKey = peerKeyOf(deviceId)
        val session = openSession(deviceId)
        // 同じ id・同じ本文・同じ sentAt（並び順を変えない）で SENDING に戻す。暗号化は transmit の中でやり直す。
        transmit(session, peerKey, message.copy(status = MessageStatus.SENDING))
    }

    /** 相手に接続し（open 済みならそのまま）、open になるまで待つ。open にならなければ例外。 */
    private suspend fun openSession(deviceId: String): PeerSession {
        val session = peerConnector.connect(deviceId)
        when (val state = session.state.first { it != PeerState.Connecting }) {
            PeerState.Open -> Unit
            is PeerState.Failed -> throw ChatSendException(ChatSendException.Reason.PEER, state.reason)
            else -> throw ChatSendException(ChatSendException.Reason.DISCONNECTED)
        }
        return session
    }

    /**
     * [message]（status = SENDING）を保存し、暗号化して `msg` を送る。送信と手動再送で共通。
     * 暗号化は毎回ここで行うので、再送のたびに nonce が新しくなる。送れなければ FAILED にして例外を投げる。
     */
    private suspend fun transmit(session: PeerSession, peerKey: PeerKey, message: Message) {
        // 先に保存して、画面に出す。以降に失敗したら FAILED にする。
        tracker.onSendStarted(message)
        try {
            val encrypted = try {
                withContext(cryptoDispatcher) {
                    cipher.encrypt(peerKey, message.id, message.body.toByteArray(Charsets.UTF_8))
                }
            } catch (e: MessageCryptoException) {
                throw ChatSendException(ChatSendException.Reason.ENCRYPT_FAILED, cause = e)
            }
            val frame = ChatFrameCodec.encode(ChatFrame.Msg(message.id, encrypted.nonceBase64(), encrypted.ctBase64()))
            if (!session.send(frame)) throw ChatSendException(ChatSendException.Reason.SEND_FAILED)
        } catch (e: Throwable) {
            withContext(NonCancellable) { tracker.onSendFailed(message.id) }
            throw e
        }
    }

    private suspend fun peerKeyOf(deviceId: String): PeerKey {
        val publicKey = contactRepository.getContact(deviceId)?.publicKey?.takeIf { it.isNotBlank() }
            ?: throw ChatSendException(ChatSendException.Reason.NO_PUBLIC_KEY)
        val der = try {
            PublicKeyEncoder.fromBase64(publicKey)
        } catch (e: IllegalArgumentException) {
            throw ChatSendException(ChatSendException.Reason.NO_PUBLIC_KEY, cause = e)
        }
        return PeerKey(deviceId, der)
    }
}
