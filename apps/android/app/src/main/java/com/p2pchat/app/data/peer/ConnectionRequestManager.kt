package com.p2pchat.app.data.peer

import kotlinx.coroutines.flow.StateFlow

/** 確認ダイアログに出す、接続の申請。[expiresAtMillis] は [ConnectionRequest.nowMillis] と同じ時計での締め切り。 */
data class ConnectionRequest(
    val deviceId: String,
    val name: String,
    val expiresAtMillis: Long,
) {
    companion object {
        /** 締め切りとの比較に使う単調な時計（ミリ秒）。 */
        fun nowMillis(): Long = System.nanoTime() / 1_000_000
    }
}

/**
 * 連絡先にいない相手からの offer を保留し、ユーザーの許可・拒否を待つ（設計書 5-4）。
 * [UnknownOfferHandler] として [DefaultPeerConnector] に差し込む。
 *
 * - 許可するまで PeerConnection は作らない（許可後に [PeerConnector.acceptOffer] へ渡す）。
 * - 同時に複数の相手から来たら 1 件ずつ [current] に出す（先に届いた順）。
 */
interface ConnectionRequestManager : UnknownOfferHandler {
    /** いま確認を出すべき申請。無ければ null。 */
    val current: StateFlow<ConnectionRequest?>

    /** [current] を許可する。 */
    fun allow()

    /** [current] を拒否する（相手へ reject を送る）。 */
    fun deny()
}
