package com.p2pchat.app.data.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.p2pchat.app.data.crypto.TokenCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * DataStore（Preferences）に保存する実装。
 *
 * - `deviceId` は平文
 * - `token` は [cipher]（Keystore の AES-GCM 鍵）で暗号化した暗号文だけを置く。平文は保存しない
 *
 * このファイルは、バックアップ・端末間コピーの対象から外してある（`backup_rules.xml` と
 * `data_extraction_rules.xml`）。暗号文だけが復元されて、復号する Keystore の鍵が無い、
 * という壊れた状態を作らないため。
 */
class DataStoreDeviceIdentityStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : DeviceIdentityStore {

    override val identity: Flow<DeviceIdentity?> = dataStore.data
        // ファイルが読めないときは、未登録として扱う
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val deviceId = prefs[DEVICE_ID] ?: return@map null
            val encrypted = prefs[ENCRYPTED_TOKEN] ?: return@map null
            // Keystore の鍵が失われた・暗号文が壊れているときは、token を取り戻せない。
            // 仕様どおり、token を失くしたときは再登録（新しい deviceId）になるので、未登録として扱う。
            val token = try {
                cipher.decrypt(encrypted)
            } catch (e: Exception) {
                return@map null
            }
            DeviceIdentity(deviceId, token)
        }

    override suspend fun save(identity: DeviceIdentity) {
        // 暗号化に失敗したら、ここで例外になり、何も書かれない
        val encrypted = cipher.encrypt(identity.token)
        dataStore.edit { prefs ->
            prefs[DEVICE_ID] = identity.deviceId
            prefs[ENCRYPTED_TOKEN] = encrypted
        }
    }

    companion object {
        /** DataStore のファイル名（バックアップの除外設定と同じ名前にする）。 */
        const val FILE_NAME = "device_identity"

        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val ENCRYPTED_TOKEN = stringPreferencesKey("encrypted_token")
    }
}
