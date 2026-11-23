package com.p2pchat.app.ui.qr

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** 連絡先の QR を読んだ結果（[ContactQrParser.parse]）。 */
sealed interface ContactQrParseResult {
    data class Success(val deviceId: String, val fingerprint: String) : ContactQrParseResult

    /** `p2pchat://contact` 以外（別のスキーム、別のホスト、URL ですらない文字列）。 */
    data object NotContactQr : ContactQrParseResult

    /** `p2pchat://contact` だが、壊れている（URL として読めない、id / fp が無い・空・重複）。 */
    data object Broken : ContactQrParseResult
}

/**
 * `p2pchat://contact?id=<deviceId>&fp=<fingerprint>`（[ContactQrUrl] が作る形）を読む。
 * Android に依存しない純粋な関数。
 */
object ContactQrParser {
    fun parse(text: String): ContactQrParseResult {
        val trimmed = text.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            // p2pchat://contact で始まるのに読めないなら「壊れた QR」、そうでなければ別物
            return if (trimmed.startsWith("p2pchat://contact", ignoreCase = true)) {
                ContactQrParseResult.Broken
            } else {
                ContactQrParseResult.NotContactQr
            }
        }
        if (!"p2pchat".equals(uri.scheme, ignoreCase = true) || !"contact".equals(uri.host, ignoreCase = true)) {
            return ContactQrParseResult.NotContactQr
        }
        val params = mutableMapOf<String, String>()
        for (pair in uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            val key = decode(pair.substringBefore('=')) ?: return ContactQrParseResult.Broken
            val value = decode(pair.substringAfter('=', "")) ?: return ContactQrParseResult.Broken
            if (params.put(key, value) != null) return ContactQrParseResult.Broken
        }
        val id = params["id"]?.trim().orEmpty()
        val fp = params["fp"]?.trim().orEmpty()
        if (id.isEmpty() || fp.isEmpty()) return ContactQrParseResult.Broken
        return ContactQrParseResult.Success(deviceId = id, fingerprint = fp)
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
