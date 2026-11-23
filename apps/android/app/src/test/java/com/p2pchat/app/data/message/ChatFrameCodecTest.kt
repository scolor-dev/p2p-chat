package com.p2pchat.app.data.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFrameCodecTest {
    private val id = "6f1c0e5e-1111-4222-8333-444455556666"

    @Test
    fun msgとackを往復できる() {
        val msg = ChatFrame.Msg(id, "bm9uY2U=", "Y3Q+/w==")
        assertEquals(msg, ChatFrameCodec.decode(ChatFrameCodec.encode(msg)))
        assertEquals(ChatFrame.Ack(id), ChatFrameCodec.decode(ChatFrameCodec.encode(ChatFrame.Ack(id))))
    }

    @Test
    fun readを往復できて形は設計書どおり() {
        val other = "7a2d1f00-2222-4333-8444-555566667777"
        val read = ChatFrame.Read(listOf(id, other))
        assertEquals(read, ChatFrameCodec.decode(ChatFrameCodec.encode(read)))
        assertEquals("""{"type":"read","ids":["$id","$other"]}""", ChatFrameCodec.encode(read))
    }

    @Test
    fun 壊れたreadは捨てる() {
        val tooMany = (0..ChatFrameCodec.MAX_READ_IDS).joinToString(",") { "\"$id\"" }
        val bad = listOf(
            """{"type":"read"}""",
            """{"type":"read","ids":[]}""",
            """{"type":"read","ids":"$id"}""",
            """{"type":"read","ids":["$id","not-a-uuid"]}""",
            """{"type":"read","ids":[1]}""",
            """{"type":"read","id":"$id"}""",
            """{"type":"read","ids":[$tooMany]}""",
        )
        for (text in bad) assertNull(text, ChatFrameCodec.decode(text))
    }

    @Test
    fun 設計書の形のJSONを読める() {
        val frame = ChatFrameCodec.decode("""{"type":"msg","id":"$id","nonce":"AAAA","ct":"BBBB"}""")
        assertEquals(ChatFrame.Msg(id, "AAAA", "BBBB"), frame)
    }

    @Test
    fun 壊れた入力は捨てる() {
        val bad = listOf(
            "",
            "not json",
            "[]",
            """{"type":"msg"}""",
            """{"type":"msg","id":"$id","nonce":"AAAA"}""",
            """{"type":"unknown","id":"$id"}""",
            """{"type":"ack"}""",
            """{"type":"ack","id":"not-a-uuid"}""",
            """{"type":"ack","id":123}""",
            """{"id":"$id"}""",
        )
        for (text in bad) assertNull(text, ChatFrameCodec.decode(text))
    }

    @Test
    fun 上限16KiBを超えるフレームは捨てる() {
        val ok = """{"type":"msg","id":"$id","nonce":"AAAA","ct":"${"A".repeat(16 * 1024 - 100)}"}"""
        assertTrue(ok.length <= ChatFrameCodec.MAX_FRAME_BYTES)
        assertTrue(ChatFrameCodec.decode(ok) != null)

        val big = """{"type":"msg","id":"$id","nonce":"AAAA","ct":"${"A".repeat(16 * 1024)}"}"""
        assertNull(ChatFrameCodec.decode(big))
        // 文字数は上限以内でも、UTF-8 のバイト数で超えるもの
        val multibyte = """{"type":"msg","id":"$id","nonce":"AAAA","ct":"${"あ".repeat(6000)}"}"""
        assertTrue(multibyte.length < ChatFrameCodec.MAX_FRAME_BYTES)
        assertNull(ChatFrameCodec.decode(multibyte))
    }

    @Test
    fun 制御文字は改行とタブ以外を取り除く() {
        assertEquals("a\nb\tc", sanitizeBody("a\nb\tc"))
        assertEquals("abc", sanitizeBody("a\u0000b\u0007c"))
        assertEquals("ab", sanitizeBody("a\r\u001Bb"))
        assertEquals("こんにちは😀", sanitizeBody("こんにちは😀"))
    }

    @Test
    fun 本文の上限はUTF8のバイト数で見る() {
        assertFalse(exceedsBodyLimit("a".repeat(4096)))
        assertTrue(exceedsBodyLimit("a".repeat(4097)))
        assertFalse(exceedsBodyLimit("あ".repeat(1365)))
        assertTrue(exceedsBodyLimit("あ".repeat(1366)))
    }
}
