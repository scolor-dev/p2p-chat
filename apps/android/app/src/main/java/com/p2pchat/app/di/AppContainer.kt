package com.p2pchat.app.di

import android.content.Context
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.contact.InMemoryContactRepository
import com.p2pchat.app.data.db.AppDatabase
import com.p2pchat.app.data.message.MessageRepository
import com.p2pchat.app.data.message.RoomMessageRepository

/**
 * 手動 DI の入れ物。アプリで使う部品を、ここ 1 か所で作って持つ。
 *
 * 部品を増やすときは、プロパティを足してここで組み立てる。
 * 使う側（ViewModel）は、必要な部品だけをコンストラクターで受け取る。
 */
class AppContainer(context: Context) {
    /** 端末内の DB。 */
    val database: AppDatabase by lazy { AppDatabase.create(context) }

    /** 見本のため、今は固定のデータを返す実装。#22 で Room を使う実装に差し替える。 */
    val contactRepository: ContactRepository = InMemoryContactRepository()

    /** メッセージリポジトリ（Room を用いた実装） */
    val messageRepository: MessageRepository = RoomMessageRepository(database.messageDao())
}