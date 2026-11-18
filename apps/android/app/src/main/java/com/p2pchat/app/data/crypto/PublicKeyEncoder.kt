package com.p2pchat.app.data.crypto

import java.security.PublicKey
import java.util.Base64

/**
 * 公開鍵を、サーバーに送る形（X.509 SubjectPublicKeyInfo の DER を base64 にしたもの）に書き出す。
 *
 * P-256 なら DER は 91 バイトになる。base64 は改行なし（Android の `Base64.NO_WRAP` と同じ形）。
 * ユニットテストでも動くように、`android.util.Base64` ではなく `java.util.Base64` を使う。
 */
object PublicKeyEncoder {
    fun toBase64(publicKey: PublicKey): String {
        require(publicKey.format == "X.509") { "公開鍵の形式が X.509 ではありません" }
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    /**
     * [toBase64] の逆。base64 の文字列を、X.509 DER のバイト列に戻す（`PeerKey.publicKey` にそのまま渡せる）。
     * 連絡先の `publicKey`（base64 文字列）から `PeerKey` を作る #26 が使う。
     *
     * base64 として不正（文字や長さが違う）なら [IllegalArgumentException]。鍵として正しいかどうかは、
     * ここでは見ない（暗号化のときに `INVALID_PEER_KEY` になる）。
     */
    fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
