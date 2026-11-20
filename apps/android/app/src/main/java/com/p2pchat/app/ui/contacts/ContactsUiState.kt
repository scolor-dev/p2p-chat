package com.p2pchat.app.ui.contacts

/** 連絡先一覧の画面が表示に使う状態。 */
data class ContactsUiState(
    val contacts: List<ContactListItemUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

/** 連絡先一覧の1行を表示するための情報。 */
data class ContactListItemUiModel(
    val deviceId: String,
    val name: String,
    /** 公開鍵のフィンガープリント（QR の `fp` と同じ計算）。公開鍵が未登録・不正なら null。 */
    val fingerprint: String?,
    val isVerified: Boolean,
    val latestMessage: String,
    val isUnread: Boolean,
    val sendError: Boolean,
)