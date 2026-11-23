package com.p2pchat.app.data.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** DataChannel で送受信する 1 フレーム（JSON テキスト 1 つ）。形式は `docs/design.md` の「DataChannel のメッセージ」。 */
sealed interface ChatFrame {
    /** 暗号化した本文。[nonce] と [ct] は標準 base64。 */
    data class Msg(val id: String, val nonce: String, val ct: String) : ChatFrame

    /** 受信確認。 */
    data class Ack(val id: String) : ChatFrame
}

/** [ChatFrame] と JSON テキストの変換。受け取る側は、不正なものを例外ではなく null で返す。 */
object ChatFrameCodec {
    /** 1 フレームの上限（UTF-8 のバイト数）。超えるものは受信時に捨てる。 */
    const val MAX_FRAME_BYTES = 16 * 1024

    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** 暗号化の AAD に使う `id` は、UUID の形に限る（[com.p2pchat.app.data.crypto.MessageCipher] の注意）。 */
    fun isValidId(id: String): Boolean = uuid.matches(id)

    fun encode(frame: ChatFrame): String = when (frame) {
        is ChatFrame.Msg -> buildJsonObject {
            put("type", "msg")
            put("id", frame.id)
            put("nonce", frame.nonce)
            put("ct", frame.ct)
        }
        is ChatFrame.Ack -> buildJsonObject {
            put("type", "ack")
            put("id", frame.id)
        }
    }.toString()

    /** 16 KiB 超・壊れた JSON・知らない type・必要な項目の欠け・UUID でない id は null。 */
    fun decode(text: String): ChatFrame? {
        // UTF-8 のバイト数は文字数以上なので、文字数で超えていれば数えるまでもなく捨てられる。
        if (text.length > MAX_FRAME_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null

        fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val id = string("id")?.takeIf { isValidId(it) } ?: return null
        return when (string("type")) {
            "msg" -> {
                val nonce = string("nonce") ?: return null
                val ct = string("ct") ?: return null
                ChatFrame.Msg(id, nonce, ct)
            }
            "ack" -> ChatFrame.Ack(id)
            else -> null
        }
    }
}

/** 本文の長さの上限（UTF-8 のバイト数）。設計書「本文の長さ」。 */
const val MAX_BODY_BYTES = 4096

/** 改行（`\n`）とタブ以外の制御文字を取り除く。 */
fun sanitizeBody(body: String): String =
    body.filter { !Character.isISOControl(it) || it == '\n' || it == '\t' }

fun exceedsBodyLimit(body: String): Boolean = body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES
