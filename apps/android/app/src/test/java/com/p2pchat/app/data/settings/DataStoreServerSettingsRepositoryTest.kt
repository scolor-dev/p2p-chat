package com.p2pchat.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreServerSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 同じファイルを使う DataStore を作る。[block] が終わったら閉じる（アプリの終了に当たる）。 */
    private suspend fun <T> withRepository(file: File, block: suspend (ServerSettingsRepository) -> T): T {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return try {
            block(DataStoreServerSettingsRepository(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun 未保存ならnull() = runBlocking {
        val file = File(tempFolder.root, "settings.preferences_pb")

        val settings = withRepository(file) { it.settings.first() }

        assertNull(settings)
    }

    @Test
    fun 保存した設定は作り直しても読める() = runBlocking {
        val file = File(tempFolder.root, "settings.preferences_pb")

        withRepository(file) { it.save(ServerSettings("192.168.1.10", 9000)) }
        val settings = withRepository(file) { it.settings.first() }

        assertEquals(ServerSettings("192.168.1.10", 9000), settings)
    }
}
