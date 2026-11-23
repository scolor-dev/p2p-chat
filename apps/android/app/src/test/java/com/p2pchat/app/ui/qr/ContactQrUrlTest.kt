package com.p2pchat.app.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactQrUrlTest {
    @Test
    fun deviceIdと公開鍵のフィンガープリントからURLを作る() {
        val url = ContactQrUrl.build("a1b2c3d4e5f60718", "abc".toByteArray())

        // SHA-256("abc") をパディングなしの base64url にした値
        assertEquals(
            "p2pchat://contact?id=a1b2c3d4e5f60718&fp=ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0",
            url,
        )
    }
}
