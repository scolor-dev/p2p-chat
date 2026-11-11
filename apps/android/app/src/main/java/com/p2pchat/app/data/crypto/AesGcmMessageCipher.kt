package com.p2pchat.app.data.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH（P-256）→ HKDF-SHA256 → AES-256-GCM のメッセージ暗号化（設計書「メッセージの暗号化」）。
 *
 * - 共通鍵 = HKDF-SHA256(ikm = 共有秘密, salt = なし（設計書の指定。RFC どおり HashLen バイトのゼロ）,
 *   info = "p2p-chat/v1/message", 32 バイト)
 * - 共通鍵は相手の deviceId ごとにメモリ上でだけ使い回す（ディスクに書かない）。
 *   同じ deviceId で公開鍵が変わったら、古い鍵は捨てて作り直す。
 * - 共有秘密・共通鍵・平文は、ログにも例外メッセージにも出さない。
 */
class AesGcmMessageCipher(
    private val sharedSecretProvider: SharedSecretProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) : MessageCipher {

    /** キャッシュの 1 件。[publicKey] は作ったときの相手の公開鍵（変化の検出用）。 */
    private class CachedKey(val publicKey: ByteArray, val key: SecretKeySpec)

    private val cache = ConcurrentHashMap<String, CachedKey>()

    override fun encrypt(peer: PeerKey, id: String, plaintext: ByteArray): EncryptedMessage {
        val key = keyFor(peer)
        val nonce = ByteArray(NONCE_LENGTH).also(secureRandom::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            return EncryptedMessage(nonce, cipher.doFinal(plaintext))
        } catch (e: GeneralSecurityException) {
            throw MessageCryptoException(MessageCryptoException.Reason.ENCRYPT_FAILED, "暗号化に失敗しました", e)
        }
    }

    override fun decrypt(peer: PeerKey, id: String, message: EncryptedMessage): ByteArray {
        if (message.nonce.size != NONCE_LENGTH) {
            throw MessageCryptoException(MessageCryptoException.Reason.INVALID_INPUT, "nonce の長さが不正です")
        }
        if (message.ct.size < TAG_BITS / 8) {
            throw MessageCryptoException(MessageCryptoException.Reason.INVALID_INPUT, "ct が短すぎます")
        }
        val key = keyFor(peer)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, message.nonce))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(message.ct)
        } catch (e: GeneralSecurityException) {
            throw MessageCryptoException(MessageCryptoException.Reason.DECRYPT_FAILED, "復号に失敗しました", e)
        }
    }

    /** 相手の共通鍵を返す。キャッシュにあり、公開鍵が同じならそれを使う。 */
    private fun keyFor(peer: PeerKey): SecretKeySpec {
        val cached = cache[peer.deviceId]
        if (cached != null && cached.publicKey.contentEquals(peer.publicKey)) return cached.key
        val key = deriveKey(peer.publicKey)
        // 公開鍵のコピーを持つ（呼び出し側が配列を書き換えても、変化の検出が狂わないように）
        cache[peer.deviceId] = CachedKey(peer.publicKey.copyOf(), key)
        return key
    }

    private fun deriveKey(publicKeyDer: ByteArray): SecretKeySpec {
        val peerPublicKey = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        } catch (e: GeneralSecurityException) {
            throw MessageCryptoException(MessageCryptoException.Reason.INVALID_PEER_KEY, "相手の公開鍵が不正です", e)
        }
        val secret = try {
            sharedSecretProvider.deriveSecret(peerPublicKey)
        } catch (e: GeneralSecurityException) {
            throw MessageCryptoException(MessageCryptoException.Reason.KEY_AGREEMENT_FAILED, "共通鍵を作れませんでした", e)
        }
        try {
            val keyBytes = Hkdf.derive(secret, null, INFO, KEY_LENGTH)
            try {
                return SecretKeySpec(keyBytes, "AES") // SecretKeySpec は内部にコピーを持つ
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            secret.fill(0)
        }
    }

    companion object {
        const val NONCE_LENGTH = 12
        private const val TAG_BITS = 128
        private const val KEY_LENGTH = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val INFO = "p2p-chat/v1/message".toByteArray(Charsets.UTF_8)
    }
}
