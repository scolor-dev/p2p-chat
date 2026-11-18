package com.p2pchat.app.data.crypto

import java.util.Base64

/**
 * 暗号化した本文。DataChannel の `msg` の `nonce` と `ct` にあたる（JSON への組み立ては #26）。
 *
 * [ct] は暗号文＋認証タグ（16 バイト）。base64 は標準（`+` `/`、パディングあり）で改行なし。
 */
class EncryptedMessage(val nonce: ByteArray, val ct: ByteArray) {
    fun nonceBase64(): String = Base64.getEncoder().encodeToString(nonce)

    fun ctBase64(): String = Base64.getEncoder().encodeToString(ct)

    companion object {
        /** base64 の [nonce] と [ct] から作る。base64 として不正なら [MessageCryptoException]。 */
        fun fromBase64(nonce: String, ct: String): EncryptedMessage = try {
            EncryptedMessage(Base64.getDecoder().decode(nonce), Base64.getDecoder().decode(ct))
        } catch (e: IllegalArgumentException) {
            throw MessageCryptoException(
                MessageCryptoException.Reason.INVALID_INPUT,
                "nonce か ct が base64 ではありません",
                e,
            )
        }
    }
}

/** 暗号化の相手。[publicKey] は X.509（SubjectPublicKeyInfo）DER のバイト列。 */
class PeerKey(val deviceId: String, val publicKey: ByteArray)

/**
 * 暗号化・復号の失敗。メッセージに鍵・平文は含めない。
 * 改ざん・id 違い・別の鍵による復号失敗は [Reason.DECRYPT_FAILED]、入力の形の誤りは [Reason.INVALID_INPUT] など。
 */
class MessageCryptoException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        /** nonce の長さ・base64・ct の短さなど、入力の形が不正。 */
        INVALID_INPUT,

        /** 相手の公開鍵が読めない（X.509 の EC 公開鍵ではない）。 */
        INVALID_PEER_KEY,

        /** 共通鍵を作れなかった（ECDH の失敗）。 */
        KEY_AGREEMENT_FAILED,

        /** 暗号化自体の失敗（通常は起きない）。 */
        ENCRYPT_FAILED,

        /** 認証に失敗した（暗号文や id の改ざん、別の鍵）。 */
        DECRYPT_FAILED,
    }
}

/**
 * メッセージ本文を、相手ごとの共通鍵で暗号化・復号する部品（AES-256-GCM、AAD は id）。
 * 失敗は [MessageCryptoException] で返す（平文や空文字を返さない）。
 *
 * 使うときの注意:
 * - 同じ相手への最初の呼び出しでは、Keystore の ECDH が走るので重い。メインスレッドから呼ばず、
 *   `Dispatchers.Default` などから呼ぶこと（suspend にはしていない）。2 回目以降は共通鍵を使い回すので軽い。
 * - `id` は、呼び出し側（#26）が正規の形（UUID）に限ること。Kotlin の `toByteArray(UTF_8)` は孤立サロゲートを
 *   `?` に置き換えるため、任意の文字列を渡すと、別の `id` が同じ AAD になりうる。
 * - [TokenCipher]（#18、端末内に token を保存するための暗号化）とは別物。こちらは端末間のメッセージ用。
 */
interface MessageCipher {
    /** [plaintext] を暗号化する。nonce は毎回ランダム。 */
    @Throws(MessageCryptoException::class)
    fun encrypt(peer: PeerKey, id: String, plaintext: ByteArray): EncryptedMessage

    /**
     * 復号する。認証に失敗したら [MessageCryptoException]（[MessageCryptoException.Reason.DECRYPT_FAILED]）。
     *
     * `ct` が [MAX_CT_BYTES]（16 KiB）を超えるものは、鍵を作る前に [MessageCryptoException.Reason.INVALID_INPUT] にする。
     * メッセージ全体のサイズ上限を適用するのは #26 で、ここは暗号部品側の防御（巨大な入力で時間とメモリを使わない）。
     */
    @Throws(MessageCryptoException::class)
    fun decrypt(peer: PeerKey, id: String, message: EncryptedMessage): ByteArray

    /** [deviceId] の相手の共通鍵を、メモリから破棄する。連絡先の削除・再登録で使う。次に使うときは ECDH からやり直す。 */
    fun clear(deviceId: String)

    /** すべての相手の共通鍵を、メモリから破棄する。 */
    fun clearAll()

    companion object {
        /** 復号する `ct`（暗号文＋タグ）の最大バイト数。設計書の「16 KiB 超は破棄」に合わせる。 */
        const val MAX_CT_BYTES = 16 * 1024
    }
}
