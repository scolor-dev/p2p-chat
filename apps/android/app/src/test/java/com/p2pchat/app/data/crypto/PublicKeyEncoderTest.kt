package com.p2pchat.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PublicKeyEncoderTest {
    /** JVM で作った P-256 の公開鍵（Keystore ではない。書き出しの形だけを確かめる）。 */
    private fun p256PublicKey(): PublicKey =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair().public
        }

    @Test
    fun P256の公開鍵は91バイトのDERになる() {
        val encoded = Base64.getDecoder().decode(PublicKeyEncoder.toBase64(p256PublicKey()))

        assertEquals(91, encoded.size)
        // SubjectPublicKeyInfo は SEQUENCE（0x30）で始まり、中身の長さは 0x59（89 バイト）
        assertEquals(0x30, encoded[0].toInt())
        assertEquals(0x59, encoded[1].toInt())
    }

    @Test
    fun base64はPublicKeyのencodedと一致し改行を含まない() {
        val key = p256PublicKey()

        val text = PublicKeyEncoder.toBase64(key)

        assertArrayEquals(key.encoded, Base64.getDecoder().decode(text))
        assertFalse(text.contains("\n"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(key.encoded), text)
    }
}
