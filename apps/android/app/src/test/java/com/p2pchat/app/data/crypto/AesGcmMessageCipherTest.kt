package com.p2pchat.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

class AesGcmMessageCipherTest {
    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** ECDH の呼び出し回数を数える。 */
    private class CountingProvider(private val inner: SharedSecretProvider) : SharedSecretProvider {
        var calls = 0
        override fun deriveSecret(peerPublicKey: PublicKey): ByteArray {
            calls++
            return inner.deriveSecret(peerPublicKey)
        }
    }

    private class Side(val keyPair: KeyPair) {
        val provider = CountingProvider(EcdhSharedSecretProvider { keyPair.private })
        val cipher = AesGcmMessageCipher(provider)
        val publicKey: ByteArray get() = keyPair.public.encoded
    }

    private val a = Side(newKeyPair())
    private val b = Side(newKeyPair())

    /** A から見た B、B から見た A。 */
    private val bFromA = PeerKey("device-b", b.publicKey)
    private val aFromB = PeerKey("device-a", a.publicKey)

    private val id = "6f1c0e5e-1111-4222-8333-444455556666"
    private val plain = "こんにちは".toByteArray()

    private fun assertDecryptFails(reason: MessageCryptoException.Reason, block: () -> Unit) {
        val e = assertThrows(MessageCryptoException::class.java) { block() }
        assertEquals(reason, e.reason)
    }

    @Test
    fun Aが暗号化してBが復号できる() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        assertArrayEquals(plain, b.cipher.decrypt(aFromB, id, encrypted))
    }

    @Test
    fun 双方向で往復できる() {
        val encrypted = b.cipher.encrypt(aFromB, id, plain)

        assertArrayEquals(plain, a.cipher.decrypt(bFromA, id, encrypted))
    }

    @Test
    fun nonceは12バイトで暗号文は平文にタグ16バイトを足した長さ() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        assertEquals(12, encrypted.nonce.size)
        assertEquals(plain.size + 16, encrypted.ct.size)
    }

    @Test
    fun 同じ平文を2回暗号化するとnonceもctも違う() {
        val first = a.cipher.encrypt(bFromA, id, plain)
        val second = a.cipher.encrypt(bFromA, id, plain)

        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ct.contentEquals(second.ct))
    }

    @Test
    fun 空_日本語と絵文字_4096バイトの本文が往復できる() {
        val bodies = listOf(
            ByteArray(0),
            "日本語と絵文字 😀👍 🎉".toByteArray(),
            ByteArray(4096) { it.toByte() },
        )
        for (body in bodies) {
            val encrypted = a.cipher.encrypt(bFromA, id, body)
            assertArrayEquals(body, b.cipher.decrypt(aFromB, id, encrypted))
        }
    }

    @Test
    fun ctを1バイト変えると復号に失敗する() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)
        // 暗号文の先頭・末尾（タグ）のどちらを変えても失敗する
        for (index in listOf(0, encrypted.ct.size - 1)) {
            val tampered = encrypted.ct.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

            assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
                b.cipher.decrypt(aFromB, id, EncryptedMessage(encrypted.nonce, tampered))
            }
        }
    }

    @Test
    fun idを1文字変えると復号に失敗する() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
            b.cipher.decrypt(aFromB, id.dropLast(1) + "7", encrypted)
        }
    }

    @Test
    fun nonceを変えると復号に失敗する() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)
        val nonce = encrypted.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
            b.cipher.decrypt(aFromB, id, EncryptedMessage(nonce, encrypted.ct))
        }
    }

    @Test
    fun 別の相手の鍵では復号に失敗する() {
        val c = Side(newKeyPair())
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        // C が、A から来たものとして復号する（C の秘密鍵では A×B の共通鍵にならない）
        assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
            c.cipher.decrypt(PeerKey("device-a", a.publicKey), id, encrypted)
        }
        // B が、A ではなく C の公開鍵だと思って復号する
        assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
            b.cipher.decrypt(PeerKey("device-a", c.publicKey), id, encrypted)
        }
    }

    @Test
    fun nonceの長さが違うと失敗として返る() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        for (size in listOf(0, 11, 13)) {
            assertDecryptFails(MessageCryptoException.Reason.INVALID_INPUT) {
                b.cipher.decrypt(aFromB, id, EncryptedMessage(ByteArray(size), encrypted.ct))
            }
        }
    }

    @Test
    fun ctが短すぎてタグも無いと失敗として返る() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        for (size in listOf(0, 15)) {
            assertDecryptFails(MessageCryptoException.Reason.INVALID_INPUT) {
                b.cipher.decrypt(aFromB, id, EncryptedMessage(encrypted.nonce, ByteArray(size)))
            }
        }
    }

    @Test
    fun base64でない入力は失敗として返る() {
        assertDecryptFails(MessageCryptoException.Reason.INVALID_INPUT) {
            EncryptedMessage.fromBase64("これはbase64ではない!", "AAAA")
        }
        assertDecryptFails(MessageCryptoException.Reason.INVALID_INPUT) {
            EncryptedMessage.fromBase64("AAAAAAAAAAAAAAAA", "***")
        }
    }

    @Test
    fun base64で書き出して読み戻せる() {
        val encrypted = a.cipher.encrypt(bFromA, id, plain)

        val restored = EncryptedMessage.fromBase64(encrypted.nonceBase64(), encrypted.ctBase64())

        assertFalse(encrypted.nonceBase64().contains("\n"))
        assertArrayEquals(plain, b.cipher.decrypt(aFromB, id, restored))
    }

    @Test
    fun 不正な公開鍵は失敗として返る() {
        val bad = PeerKey("device-x", ByteArray(10))

        assertDecryptFails(MessageCryptoException.Reason.INVALID_PEER_KEY) {
            a.cipher.encrypt(bad, id, plain)
        }
    }

    @Test
    fun 同じ相手には2回目のECDHをやり直さない() {
        val first = a.cipher.encrypt(bFromA, id, plain)
        a.cipher.encrypt(bFromA, "other-id", plain)
        a.cipher.decrypt(bFromA, id, b.cipher.encrypt(aFromB, id, plain))

        assertEquals(1, a.provider.calls)
        assertEquals(12, first.nonce.size)
    }

    @Test
    fun 相手ごとに別の鍵を作る() {
        val c = Side(newKeyPair())

        a.cipher.encrypt(bFromA, id, plain)
        a.cipher.encrypt(PeerKey("device-c", c.publicKey), id, plain)

        assertEquals(2, a.provider.calls)
    }

    @Test
    fun 同じdeviceIdで公開鍵が変わったら鍵を作り直す() {
        val newB = Side(newKeyPair())
        val oldEncrypted = a.cipher.encrypt(bFromA, id, plain)

        // 同じ deviceId で別の公開鍵
        val rotated = PeerKey("device-b", newB.publicKey)
        val newEncrypted = a.cipher.encrypt(rotated, id, plain)

        assertEquals(2, a.provider.calls)
        // 新しい鍵で暗号化したものは、新しい B だけが復号できる
        assertArrayEquals(plain, newB.cipher.decrypt(aFromB, id, newEncrypted))
        assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
            b.cipher.decrypt(aFromB, id, newEncrypted)
        }
        // 古い鍵のものは、新しい鍵では復号できない（古い鍵を使い続けない）
        assertDecryptFails(MessageCryptoException.Reason.DECRYPT_FAILED) {
            a.cipher.decrypt(rotated, id, oldEncrypted)
        }
        assertNotEquals(0, oldEncrypted.ct.size)
    }
}
