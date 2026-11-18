package com.p2pchat.app.data.crypto

import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.KeyAgreement

/**
 * 自分の秘密鍵と相手の公開鍵から、ECDH の共有秘密を作る部品。
 *
 * 本番は Keystore の秘密鍵（[EcdhSharedSecretProvider]）、ユニットテストでは JVM の鍵や Fake に差し替える。
 * 戻り値の共有秘密は、呼び出し側が使い終わったら 0 で埋める。
 */
interface SharedSecretProvider {
    /** 共有秘密（P-256 なら 32 バイト）を返す。失敗したら [java.security.GeneralSecurityException] を投げる。 */
    fun deriveSecret(peerPublicKey: PublicKey): ByteArray
}

/**
 * `KeyAgreement "ECDH"` で共有秘密を作る。Keystore の秘密鍵は取り出せないので、参照を渡すだけ。
 * [privateKey] は呼ぶたびに取得する（`deviceKeyStore::privateKey` をそのまま渡せる）。
 */
class EcdhSharedSecretProvider(private val privateKey: () -> PrivateKey) : SharedSecretProvider {
    override fun deriveSecret(peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey())
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }
}
