package com.p2pchat.app.data.contact

import kotlinx.coroutines.flow.Flow

/**
 * 連絡先の取得元。ViewModel はこの interface だけを知っていて、
 * 中身がメモリか DB かは気にしない（テストでは差し替えられる）。
 */
interface ContactRepository {
    /** 連絡先の一覧。変更があるたびに新しい一覧が流れてくる。 */
    fun observeContacts(): Flow<List<Contact>>

    /** 単一の連絡先を取得する。存在しない場合は null を返す。 */
    suspend fun getContact(deviceId: String): Contact?

    /** 連絡先を保存（追加または更新）する。 */
    suspend fun saveContact(contact: Contact)

    /** 指定された deviceId の連絡先を削除する。 */
    suspend fun deleteContact(deviceId: String)
}
