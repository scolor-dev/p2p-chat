package com.p2pchat.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PublicKeyFingerprintTest {
    @Test
    fun SHA256のパディングなしbase64urlになる() {
        // SHA-256("abc") = ba7816bf 8f01cfea 414140de 5dae2223 b00361a3 96177a9c b410ff61 f20015ad
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", PublicKeyFingerprint.fromDer("abc".toByteArray()))
    }

    @Test
    fun 長さは43文字でパディングも標準base64の記号も含まない() {
        val fp = PublicKeyFingerprint.fromDer(ByteArray(91) { it.toByte() })

        assertEquals(43, fp.length)
        assertTrue(fp.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun 連絡先に保存した形のbase64から同じ値が求まる() {
        val der = ByteArray(91) { (it * 3).toByte() }
        val stored = Base64.getEncoder().encodeToString(der)

        assertEquals(PublicKeyFingerprint.fromDer(der), PublicKeyFingerprint.fromBase64(stored))
    }

    @Test
    fun 鍵が違えば値も違う() {
        // P-256 の DER は先頭が全端末で同じ。先頭が同じでも、フィンガープリントは別の値になる
        val a = ByteArray(91) { if (it < 27) 1 else 2 }
        val b = ByteArray(91) { if (it < 27) 1 else 3 }

        assertNotEquals(PublicKeyFingerprint.fromDer(a), PublicKeyFingerprint.fromDer(b))
    }

    @Test
    fun 空やbase64でない文字列はnull() {
        assertNull(PublicKeyFingerprint.fromBase64(""))
        assertNull(PublicKeyFingerprint.fromBase64("   "))
        assertNull(PublicKeyFingerprint.fromBase64("!!!not base64!!!"))
    }

    @Test
    fun 表示用に4文字ずつ区切る() {
        assertEquals("abcd efgh ij", PublicKeyFingerprint.format("abcdefghij"))
    }
}
