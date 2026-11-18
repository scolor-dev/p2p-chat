package com.p2pchat.app.data.crypto

/**
 * token を保存用に暗号化・復号する部品。
 *
 * 暗号文は文字列（base64）で受け渡す。実体は Android Keystore の鍵を使うので、
 * ユニットテストでは Fake（または JVM の鍵を使う [AesGcmTokenCipher]）に差し替える。
 */
interface TokenCipher {
    /** [plain] を暗号化して、保存できる文字列にして返す。失敗したら例外を投げる。 */
    fun encrypt(plain: String): String

    /** [encrypted]（[encrypt] の出力）を復号する。鍵が失われた・壊れているときは例外を投げる。 */
    fun decrypt(encrypted: String): String
}
