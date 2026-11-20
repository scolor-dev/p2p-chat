package com.p2pchat.app.ui.qr

import com.p2pchat.app.data.crypto.PublicKeyFingerprint

/**
 * QR に入れる連絡先の URL（`docs/design.md` の「7. 連絡先の QR コード」）。
 * `p2pchat://contact?id=<deviceId>&fp=<fingerprint>`。読み取り（#24）は、この形を読む。
 */
object ContactQrUrl {
    fun build(deviceId: String, publicKeyDer: ByteArray): String =
        "p2pchat://contact?id=$deviceId&fp=${PublicKeyFingerprint.fromDer(publicKeyDer)}"
}
