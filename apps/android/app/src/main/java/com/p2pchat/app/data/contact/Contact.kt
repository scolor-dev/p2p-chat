package com.p2pchat.app.data.contact

/** 画面で使う連絡先。DB の形（[com.p2pchat.app.data.db.ContactEntity]）とは別にしておく。 */
data class Contact(
    val deviceId: String,
    val name: String,
)
