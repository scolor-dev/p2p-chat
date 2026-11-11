package com.p2pchat.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** RFC 5869 付録 A の SHA-256 のテストベクター（Test Case 1〜3）。 */
class HkdfTest {
    private fun hex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun testCase1() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())

        val okm = Hkdf.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
        assertEquals(okm.toHex(), Hkdf.derive(ikm, salt, info, 42).toHex())
    }

    @Test
    fun testCase2() {
        val ikm = hex(
            """000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
               202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
               404142434445464748494a4b4c4d4e4f""",
        )
        val salt = hex(
            """606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f
               808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f
               a0a1a2a3a4a5a6a7a8a9aaabacadaeaf""",
        )
        val info = hex(
            """b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf
               d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef
               f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff""",
        )

        val prk = Hkdf.extract(salt, ikm)
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", prk.toHex())

        val okm = Hkdf.derive(ikm, salt, info, 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.toHex(),
        )
    }

    @Test
    fun testCase3_saltとinfoが空() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")

        val prk = Hkdf.extract(null, ikm)
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", prk.toHex())

        val okm = Hkdf.derive(ikm, null, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
        // 空の salt は null と同じ（HashLen バイトのゼロ）
        assertEquals(okm.toHex(), Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42).toHex())
    }

    @Test
    fun 出力の長さが範囲外なら失敗する() {
        assertThrows(IllegalArgumentException::class.java) { Hkdf.derive(ByteArray(1), null, ByteArray(0), 0) }
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive(ByteArray(1), null, ByteArray(0), 255 * 32 + 1)
        }
    }

    @Test
    fun 最大長255x32バイトを出せる() {
        // カウンターは 1 バイトで、1 から 255 まで回る
        val okm = Hkdf.derive(ByteArray(22) { 0x0b }, null, ByteArray(0), 255 * 32)

        assertEquals(255 * 32, okm.size)
        // 先頭の 42 バイトは、長さ 42 のときと同じ（RFC 5869 Test Case 3）
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.copyOf(42).toHex(),
        )
    }
}
