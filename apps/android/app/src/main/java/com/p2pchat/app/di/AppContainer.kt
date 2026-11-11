package com.p2pchat.app.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.contact.OfflineContactRepository
import com.p2pchat.app.data.crypto.AesGcmTokenCipher
import com.p2pchat.app.data.crypto.AesGcmMessageCipher
import com.p2pchat.app.data.crypto.AndroidKeystoreAesKey
import com.p2pchat.app.data.crypto.AndroidKeystoreDeviceKeyStore
import com.p2pchat.app.data.crypto.DeviceKeyStore
import com.p2pchat.app.data.crypto.EcdhSharedSecretProvider
import com.p2pchat.app.data.crypto.MessageCipher
import com.p2pchat.app.data.db.AppDatabase
import com.p2pchat.app.data.device.DataStoreDeviceIdentityStore
import com.p2pchat.app.data.device.DefaultDeviceRepository
import com.p2pchat.app.data.device.DeviceIdentityStore
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.OkHttpApiClient
import com.p2pchat.app.data.network.OkHttpWebSocketClient
import com.p2pchat.app.data.network.WebSocketClient
import com.p2pchat.app.data.settings.DataStoreServerSettingsRepository
import com.p2pchat.app.data.settings.ServerSettingsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    /** 接続先のサーバー設定。DataStore は 1 つのファイルに 1 つだけ作る決まりなので、ここで持つ。 */
    val serverSettingsRepository: ServerSettingsRepository by lazy {
        DataStoreServerSettingsRepository(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("server_settings") },
        )
    }

    /** HTTP と WebSocket で共有する OkHttp。接続の使い回しのため、アプリで 1 つにする。 */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** signaling サーバーの HTTP API。 */
    val apiClient: ApiClient by lazy { OkHttpApiClient(okHttpClient) }

    /** WebSocket の部品。#20 で、この上にシグナリングの接続を作る。 */
    val webSocketClient: WebSocketClient by lazy { OkHttpWebSocketClient(okHttpClient) }

    /** この端末の鍵ペア（Keystore の P-256）。秘密鍵への参照は [messageCipher] が使う。 */
    val deviceKeyStore: DeviceKeyStore by lazy { AndroidKeystoreDeviceKeyStore() }

    /** メッセージ本文の暗号化・復号。相手ごとの共通鍵はメモリ上で使い回す。#26 が使う。 */
    val messageCipher: MessageCipher by lazy {
        AesGcmMessageCipher(EcdhSharedSecretProvider(deviceKeyStore::privateKey))
    }

    /** deviceId と token の保存先。token は Keystore の AES-GCM 鍵で暗号化して置く。 */
    private val deviceIdentityStore: DeviceIdentityStore by lazy {
        val aesKey = AndroidKeystoreAesKey()
        DataStoreDeviceIdentityStore(
            dataStore = PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(DataStoreDeviceIdentityStore.FILE_NAME)
            },
            cipher = AesGcmTokenCipher(aesKey::getOrCreate),
        )
    }

    /** 端末の身元（deviceId）と登録。#20、#23 は、ここから deviceId と token を読む。 */
    val deviceRepository: DeviceRepository by lazy {
        DefaultDeviceRepository(deviceKeyStore, apiClient, deviceIdentityStore)
    }
}
