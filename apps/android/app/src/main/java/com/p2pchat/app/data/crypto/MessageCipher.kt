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
 */
interface MessageCipher {
    /** [plaintext] を暗号化する。nonce は毎回ランダム。 */
    @Throws(MessageCryptoException::class)
    fun encrypt(peer: PeerKey, id: String, plaintext: ByteArray): EncryptedMessage

    /** 復号する。認証に失敗したら [MessageCryptoException]（[MessageCryptoException.Reason.DECRYPT_FAILED]）。 */
    @Throws(MessageCryptoException::class)
    fun decrypt(peer: PeerKey, id: String, message: EncryptedMessage): ByteArray
}
