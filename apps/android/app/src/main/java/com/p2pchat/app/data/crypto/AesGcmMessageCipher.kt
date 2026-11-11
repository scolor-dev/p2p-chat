package com.p2pchat.app.data.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

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
        if (message.ct.size > MessageCipher.MAX_CT_BYTES) {
            throw MessageCryptoException(MessageCryptoException.Reason.INVALID_INPUT, "ct が大きすぎます")
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

    override fun clear(deviceId: String) {
        cache.remove(deviceId)
    }

    override fun clearAll() {
        cache.clear()
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
        val peerPublicKey = parsePeerPublicKey(publicKeyDer)
        val secret = try {
            sharedSecretProvider.deriveSecret(peerPublicKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw MessageCryptoException(MessageCryptoException.Reason.KEY_AGREEMENT_FAILED, "共通鍵を作れませんでした", e)
        } catch (e: RuntimeException) {
            // privateKey() の IllegalStateException など。生の実行時例外を呼び出し側に漏らさない
            throw MessageCryptoException(MessageCryptoException.Reason.KEY_AGREEMENT_FAILED, "共通鍵を作れませんでした", e)
        }
        try {
            if (secret.size != SHARED_SECRET_LENGTH) {
                throw MessageCryptoException(
                    MessageCryptoException.Reason.KEY_AGREEMENT_FAILED,
                    "共有秘密の長さが不正です",
                )
            }
            val keyBytes = Hkdf.derive(secret, null, INFO, KEY_LENGTH)
            try {
                return SecretKeySpec(keyBytes, "AES") // SecretKeySpec は内部にコピーを持つ
            } finally {
                // SecretKeySpec 側のコピーは消せないので、これはベストエフォート
                keyBytes.fill(0)
            }
        } finally {
            secret.fill(0)
        }
    }

    /**
     * 相手の公開鍵（X.509 DER）を復元し、P-256 の曲線上の正しい点であることを確かめる。
     * 満たさなければ [MessageCryptoException.Reason.INVALID_PEER_KEY]。
     */
    private fun parsePeerPublicKey(publicKeyDer: ByteArray): PublicKey {
        val invalid = { cause: Throwable? ->
            MessageCryptoException(MessageCryptoException.Reason.INVALID_PEER_KEY, "相手の公開鍵が不正です", cause)
        }
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        } catch (e: GeneralSecurityException) {
            throw invalid(e)
        } catch (e: RuntimeException) {
            throw invalid(e)
        }
        if (key !is ECPublicKey) throw invalid(null)
        val params = key.params
        // 曲線（体・a・b）、基準点、位数がすべて P-256 と同じであること
        if (params.curve != P256.curve || params.generator != P256.generator || params.order != P256.order) {
            throw invalid(null)
        }
        val w = key.w
        if (w == ECPoint.POINT_INFINITY) throw invalid(null)
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        val x = w.affineX
        val y = w.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) throw invalid(null)
        // y^2 = x^3 + ax + b (mod p)
        val left = y.multiply(y).mod(p)
        val right = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (left != right) throw invalid(null)
        return key
    }

    companion object {
        const val NONCE_LENGTH = 12
        private const val SHARED_SECRET_LENGTH = 32
        private const val TAG_BITS = 128
        private const val KEY_LENGTH = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val INFO = "p2p-chat/v1/message".toByteArray(Charsets.UTF_8)

        /** P-256 の基準パラメーター（JDK / Android の secp256r1。曲線・基準点・位数の比較に使う）。 */
        private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }
}
