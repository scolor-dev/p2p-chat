package com.p2pchat.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * token の暗号化に使う AES-256 の鍵を、Android Keystore に作って取り出す。
 * 鍵はすでにあれば作り直さない。鍵の中身は Keystore の外に出ない（[SecretKey] は参照だけ）。
 */
class AndroidKeystoreAesKey(
    private val alias: String = DEFAULT_ALIAS,
) {
    @Synchronized
    fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "p2pchat_token_aes_gcm"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
