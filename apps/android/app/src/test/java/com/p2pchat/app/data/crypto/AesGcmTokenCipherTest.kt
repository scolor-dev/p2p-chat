package com.p2pchat.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Keystore の代わりに JVM の AES 鍵で、暗号文の形と往復を確かめる。 */
class AesGcmTokenCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val token = "0123456789abcdef".repeat(4)

    @Test
    fun 暗号化して復号すると元に戻る() {
        val key = newKey()
        val cipher = AesGcmTokenCipher { key }

        assertEquals(token, cipher.decrypt(cipher.encrypt(token)))
    }

    @Test
    fun 暗号文に平文は含まれず毎回違う() {
        val key = newKey()
        val cipher = AesGcmTokenCipher { key }

        val a = cipher.encrypt(token)
        val b = cipher.encrypt(token)

        assertFalse(a.contains(token))
        assertNotEquals(a, b)
    }

    @Test
    fun 別の鍵では復号できない() {
        val encrypted = AesGcmTokenCipher { newKey() }.encrypt(token)

        assertThrows(GeneralSecurityException::class.java) { AesGcmTokenCipher { newKey() }.decrypt(encrypted) }
    }

    @Test
    fun 改ざんされた暗号文は復号できない() {
        val key = newKey()
        val cipher = AesGcmTokenCipher { key }
        val bytes = java.util.Base64.getDecoder().decode(cipher.encrypt(token))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes))
        }
    }
}
