package com.p2pchat.app.data.contact

import kotlinx.coroutines.flow.Flow

/**
 * 連絡先の取得元。ViewModel はこの interface だけを知っていて、
 * 中身がメモリか DB かは気にしない（テストでは差し替えられる）。
 */
interface ContactRepository {
    /** 連絡先の一覧。変更があるたびに新しい一覧が流れてくる。 */
    fun observeContacts(): Flow<List<Contact>>
}
