package com.p2pchat.app.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.KeyAgreement

/**
 * 本物の Android Keystore で確かめる（実機・エミュレーターが必要）。
 * テスト専用の別名を使い、終わったら削除する（アプリ本体の鍵には触らない）。
 */
@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {
    private val ecAlias = "test_p2pchat_ec"
    private val aesAlias = "test_p2pchat_aes"

    @After
    fun tearDown() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(ecAlias)
        ks.deleteEntry(aesAlias)
    }

    @Test
    fun 公開鍵は91バイトのDERで再取得しても同じ鍵() {
        val store = AndroidKeystoreDeviceKeyStore(ecAlias)

        val first = store.getOrCreatePublicKey()
        val second = AndroidKeystoreDeviceKeyStore(ecAlias).getOrCreatePublicKey()

        assertEquals(91, first.encoded.size)
        assertEquals(PublicKeyEncoder.toBase64(first), PublicKeyEncoder.toBase64(second))
    }

    @Test
    fun 秘密鍵は取り出せずKeyAgreementに使える() {
        val store = AndroidKeystoreDeviceKeyStore(ecAlias)
        val publicKey = store.getOrCreatePublicKey()

        val privateKey = store.privateKey()

        assertNull(privateKey.encoded)
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(publicKey, true)
        }
        assertTrue(agreement.generateSecret().isNotEmpty())
    }

    @Test
    fun tokenを暗号化して復号できる() {
        val aesKey = AndroidKeystoreAesKey(aesAlias)
        val cipher = AesGcmTokenCipher(aesKey::getOrCreate)
        val token = "0123456789abcdef".repeat(4)

        val encrypted = cipher.encrypt(token)

        assertFalse(encrypted.contains(token))
        assertEquals(token, cipher.decrypt(encrypted))
        // 鍵は取り出せない
        assertNull(aesKey.getOrCreate().encoded)
    }
}
