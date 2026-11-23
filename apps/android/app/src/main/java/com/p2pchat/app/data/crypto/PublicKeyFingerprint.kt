package com.p2pchat.app.data.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * 公開鍵のフィンガープリント（`docs/design.md` の「7. 連絡先の QR コード」の `fp`）。
 *
 * 公開鍵（X.509 DER のバイト列）の SHA-256 を、パディングなしの base64url にしたもの。
 * QR に入れる値（#23）と、読み取った側の照合（#24）、連絡先の詳細での表示（#22）が、同じ計算を使う。
 */
object PublicKeyFingerprint {
    /** X.509 DER のバイト列から求める。 */
    fun fromDer(der: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(der))

    /**
     * 連絡先に保存している公開鍵（DER を標準 base64 にした文字列。[PublicKeyEncoder.toBase64] の形）から求める。
     * 空、または base64 として読めないときは null。
     */
    fun fromBase64(publicKey: String): String? {
        if (publicKey.isBlank()) return null
        val der = try {
            PublicKeyEncoder.fromBase64(publicKey)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return fromDer(der)
    }

    /** 目で比べやすいように、4 文字ずつ空白で区切る。 */
    fun format(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")
}
