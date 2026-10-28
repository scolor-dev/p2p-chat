package com.p2pchat.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** DataStore（Preferences）に保存する実装。アプリを再起動しても設定が残る。 */
class DataStoreServerSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : ServerSettingsRepository {

    override val settings: Flow<ServerSettings?> = dataStore.data
        // ファイルが読めないときは、未設定として扱う
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val host = prefs[HOST] ?: return@map null
            ServerSettings(host = host, port = prefs[PORT] ?: ServerSettings.DEFAULT_PORT)
        }

    override suspend fun save(settings: ServerSettings) {
        dataStore.edit { prefs ->
            prefs[HOST] = settings.host
            prefs[PORT] = settings.port
        }
    }

    private companion object {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
    }
}
