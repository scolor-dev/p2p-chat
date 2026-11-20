package com.p2pchat.app.ui.addcontact

import com.p2pchat.app.data.network.RemoteDevice

/** ID で連絡先を追加する画面が表示に使う状態。 */
data class AddContactUiState(
    /** 入力中の相手の `deviceId`。 */
    val deviceId: String = "",
    val isSearching: Boolean = false,
    /** 検索で見つかった相手。null なら、まだ見つかっていない。 */
    val found: RemoteDevice? = null,
    val isAdding: Boolean = false,
    val error: AddContactError? = null,
    /** 追加できたら true。画面はこれを見て前の画面へ戻る。 */
    val isAdded: Boolean = false,
)

/** 検索・追加に失敗した理由。画面はこれを文言にする。 */
enum class AddContactError {
    /** ID が空。 */
    Empty,

    /** サーバーの接続先が保存されていない。 */
    ServerNotConfigured,

    /** その ID の端末がサーバーにない。 */
    NotFound,

    /** 自分の ID。 */
    Self,

    /** すでに連絡先にいる。 */
    AlreadyAdded,

    /** サーバーに届かない・応答が読めない・サーバーのエラー。 */
    Network,

    /** 端末への保存に失敗した。 */
    StorageFailed,
}
