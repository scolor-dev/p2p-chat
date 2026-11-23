package com.p2pchat.app.data.settings

import kotlinx.coroutines.flow.Flow

/** アプリの動作に関する設定（接続先のサーバー以外）の窓口。 */
interface AppPreferencesRepository {
    /** 背面でも通信を保つか。既定は true。 */
    val backgroundCommsEnabled: Flow<Boolean>

    suspend fun setBackgroundCommsEnabled(enabled: Boolean)

    /** 通知の権限（POST_NOTIFICATIONS）を、チャット画面を開いたときに一度でも要求したか。 */
    val notificationPermissionRequested: Flow<Boolean>

    suspend fun markNotificationPermissionRequested()
}
