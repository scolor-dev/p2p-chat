package com.p2pchat.app.data.settings

import kotlinx.coroutines.flow.Flow

/** 接続先のサーバー設定の窓口。ViewModel や通信部品は、この interface だけを知る。 */
interface ServerSettingsRepository {
    /** 保存されている設定。まだ一度も保存していなければ null。 */
    val settings: Flow<ServerSettings?>

    suspend fun save(settings: ServerSettings)
}
