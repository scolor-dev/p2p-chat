package com.p2pchat.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore に P-256 の鍵ペアを作って持つ実装。
 *
 * - 用途は鍵共有（ECDH）だけ。署名などには使えない
 * - 秘密鍵は Keystore の外に出せない（`getEncoded()` は null）。ファイルにもログにも出ない
 * - [alias] の鍵がすでにあれば作り直さない
 */
class AndroidKeystoreDeviceKeyStore(
    private val alias: String = DEFAULT_ALIAS,
) : DeviceKeyStore {

    @Synchronized
    override fun getOrCreatePublicKey(): PublicKey {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(alias)) generate()
        return keyStore.getCertificate(alias).publicKey
    }

    override fun privateKey(): PrivateKey {
        val key = loadKeyStore().getKey(alias, null)
        return key as? PrivateKey ?: throw IllegalStateException("端末の鍵がまだ作られていません")
    }

    private fun generate() {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        const val DEFAULT_ALIAS = "p2pchat_device_ec_p256"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
