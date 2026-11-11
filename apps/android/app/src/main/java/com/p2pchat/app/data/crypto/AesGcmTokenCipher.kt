package com.p2pchat.app.data.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM で暗号化する実装。鍵は [keyProvider] から取る。
 *
 * 暗号文は `iv(12 バイト) + 暗号文 + 認証タグ` を base64（改行なし）にしたもの。
 * IV は暗号化のたびに [Cipher] が作る（Keystore の鍵は、IV を自分で指定できない設定にしてある）。
 */
class AesGcmTokenCipher(
    private val keyProvider: () -> SecretKey,
) : TokenCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + body)
    }

    override fun decrypt(encrypted: String): String {
        val bytes = Base64.getDecoder().decode(encrypted)
        require(bytes.size > IV_SIZE) { "暗号文が短すぎます" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
