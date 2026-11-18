package com.p2pchat.app.data.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256（RFC 5869）。OS 標準には無いので、HMAC-SHA256 で実装する。
 *
 * 中間値（PRK など）は使い終わったら 0 で埋める。
 */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    const val HASH_LENGTH = 32

    /** RFC 5869 の最大出力は 255 * HashLen。 */
    private const val MAX_LENGTH = 255 * HASH_LENGTH

    /**
     * Extract と Expand をまとめて行う。[salt] が null または空なら、RFC どおり HashLen バイトのゼロを使う。
     */
    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }

    /** PRK = HMAC-Hash(salt, IKM)。 */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(effectiveSalt, HMAC)) }
        return mac.doFinal(ikm)
    }

    /** OKM = T(1) | T(2) | ... の先頭 [length] バイト。 */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_LENGTH) { "出力の長さが範囲外です" }
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
        val okm = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            val block = mac.doFinal()
            val n = minOf(block.size, length - offset)
            block.copyInto(okm, offset, 0, n)
            offset += n
            previous.fill(0)
            previous = block
            counter++
        }
        previous.fill(0)
        return okm
    }
}
