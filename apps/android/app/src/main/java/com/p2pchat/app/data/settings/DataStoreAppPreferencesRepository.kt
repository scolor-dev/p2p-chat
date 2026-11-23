package com.p2pchat.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** DataStore（Preferences）に保存する実装。読めないときは既定値として扱う。 */
class DataStoreAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {

    private val data: Flow<Preferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val backgroundCommsEnabled: Flow<Boolean> =
        data.map { it[BACKGROUND_COMMS] ?: DEFAULT_BACKGROUND_COMMS }.distinctUntilChanged()

    override suspend fun setBackgroundCommsEnabled(enabled: Boolean) {
        dataStore.edit { it[BACKGROUND_COMMS] = enabled }
    }

    override val notificationPermissionRequested: Flow<Boolean> =
        data.map { it[NOTIFICATION_PERMISSION_REQUESTED] ?: false }.distinctUntilChanged()

    override suspend fun markNotificationPermissionRequested() {
        dataStore.edit { it[NOTIFICATION_PERMISSION_REQUESTED] = true }
    }

    companion object {
        const val FILE_NAME = "app_preferences"
        const val DEFAULT_BACKGROUND_COMMS = true
        private val BACKGROUND_COMMS = booleanPreferencesKey("background_comms_enabled")
        private val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")
    }
}
