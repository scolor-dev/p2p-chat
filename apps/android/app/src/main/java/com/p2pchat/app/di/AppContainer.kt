package com.p2pchat.app.di

import android.content.Context
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.contact.OfflineContactRepository
import com.p2pchat.app.data.db.AppDatabase

/**
 * 手動 DI の入れ物。アプリで使う部品を、ここ 1 か所で作って持つ。
 *
 * 部品を増やすときは、プロパティを足してここで組み立てる。
 * 使う側（ViewModel）は、必要な部品だけをコンストラクターで受け取る。
 */
class AppContainer(context: Context) {
    /** 端末内の DB。 */
    val database: AppDatabase by lazy { AppDatabase.create(context) }

    /** Room DB を使用する連絡先リポジトリ。 */
    val contactRepository: ContactRepository by lazy {
        OfflineContactRepository(database.contactDao())
    }
}
