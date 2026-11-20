package com.p2pchat.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 暗号の「形」（info・salt・AAD・nonce・タグ）を固定するテスト。
 * 他の端末や実装と互換でなくなる変更（info を変える、salt を足す、など）を検出する。
 */
class AesGcmMessageCipherFormatTest {
    /** ECDH を使わず、固定の共有秘密（0x42 を 32 個）を返す。 */
    private val fixedSecret = ByteArray(32) { 0x42 }
    private val provider = object : SharedSecretProvider {
        override fun deriveSecret(peerPublicKey: PublicKey): ByteArray = fixedSecret.copyOf()
    }

    /** nonce を 00 01 02 ... 0b に固定する（既知値テスト専用）。 */
    private val fixedRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = i.toByte()
        }
    }

    private val peer = PeerKey(
        "device-b",
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded,
    )
    private val id = "6f1c0e5e-1111-4222-8333-444455556666"
    private val plain = "こんにちは".toByteArray(Charsets.UTF_8)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun 出力は_info固定_saltなし_のHKDF鍵とAADがidのAES_GCMで復号できる() {
        val encrypted = AesGcmMessageCipher(provider).encrypt(peer, id, plain)

        // 部品を使わず、設計書のとおりにテスト側で鍵を作って復号する
        val key = Hkdf.derive(fixedSecret, null, "p2p-chat/v1/message".toByteArray(Charsets.UTF_8), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.nonce))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))

        assertArrayEquals(plain, cipher.doFinal(encrypted.ct))
    }

    /**
     * 別実装（Node.js の `crypto.hkdfSync("sha256", secret, "", "p2p-chat/v1/message", 32)` と
     * `aes-256-gcm`、鍵は OpenSSL の `kdf HKDF` とも一致を確認）が作った期待値。
     */
    @Test
    fun 固定の秘密と固定のnonceで別実装と同じ暗号文になる() {
        val encrypted = AesGcmMessageCipher(provider, fixedRandom).encrypt(peer, id, plain)

        assertEquals("000102030405060708090a0b", hex(encrypted.nonce))
        assertEquals("9f4af33db109e4c367cecefa4f231288f1e133e4778369e0da8bddab7b9d1b", hex(encrypted.ct))
    }

    @Test
    fun HKDFの鍵が別実装と同じになる() {
        val key = Hkdf.derive(fixedSecret, null, "p2p-chat/v1/message".toByteArray(Charsets.UTF_8), 32)

        assertEquals("2174703a1e934af92e520205287e186f163cc52ddf32d425652339bebfa03143", hex(key))
    }
}
