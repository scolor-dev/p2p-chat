package com.p2pchat.app.ui.contacts

import com.p2pchat.app.data.contact.Contact

/** 連絡先一覧の画面が表示に使う状態。画面の見た目は、この値だけで決まる。 */
data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val isLoading: Boolean = true,
)
