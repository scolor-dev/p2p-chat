package com.p2pchat.app.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactQrParserTest {
    private val fp = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"

    @Test
    fun ContactQrUrlが作るURLを読める() {
        val url = ContactQrUrl.build("a1b2c3d4e5f60718", "abc".toByteArray())

        assertEquals(ContactQrParseResult.Success("a1b2c3d4e5f60718", fp), ContactQrParser.parse(url))
    }

    @Test
    fun 前後の空白とパラメーターの順序は気にしない() {
        assertEquals(
            ContactQrParseResult.Success("abc", fp),
            ContactQrParser.parse("  p2pchat://contact?fp=$fp&id=abc\n"),
        )
    }

    @Test
    fun 別のスキームは連絡先のQRではない() {
        assertEquals(ContactQrParseResult.NotContactQr, ContactQrParser.parse("https://contact?id=abc&fp=$fp"))
        assertEquals(ContactQrParseResult.NotContactQr, ContactQrParser.parse("p2pchat://other?id=abc&fp=$fp"))
    }

    @Test
    fun URLではない文字列は連絡先のQRではない() {
        assertEquals(ContactQrParseResult.NotContactQr, ContactQrParser.parse("hello world"))
        assertEquals(ContactQrParseResult.NotContactQr, ContactQrParser.parse(""))
    }

    @Test
    fun idやfpが無い_空_重複は壊れたQR() {
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact"))
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?fp=$fp"))
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?id=abc"))
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?id=&fp=$fp"))
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?id=abc&fp="))
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?id=a&id=b&fp=$fp"))
    }

    @Test
    fun 読めない形のURLは壊れたQR() {
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?id=a b&fp=$fp"))
        assertEquals(ContactQrParseResult.Broken, ContactQrParser.parse("p2pchat://contact?id=%ZZ&fp=$fp"))
    }
}
