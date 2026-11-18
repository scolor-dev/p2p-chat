package com.p2pchat.app.data.crypto

import java.security.PrivateKey
import java.security.PublicKey

/**
 * この端末の鍵ペア（P-256、鍵共有用）の窓口。
 *
 * 実体は Android Keystore の中にあり、ユニットテストでは動かせないので、interface で切ってある。
 * ViewModel や Repository のテストでは Fake に差し替える。
 */
interface DeviceKeyStore {
    /**
     * 公開鍵を返す。鍵ペアがまだ無ければ作る。すでにあれば作り直さない（何度呼んでも同じ鍵）。
     * 失敗したら例外を投げる（メッセージに鍵の内容は含めない）。
     */
    fun getOrCreatePublicKey(): PublicKey

    /**
     * 秘密鍵への参照を返す。Keystore の鍵なので、鍵の中身（バイト列）は取り出せず、
     * [javax.crypto.KeyAgreement] に渡して使うだけ。使うのは #21（共通鍵の作成）。
     * 鍵ペアがまだ無ければ [IllegalStateException]。
     */
    fun privateKey(): PrivateKey
}
