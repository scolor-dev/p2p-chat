package com.p2pchat.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreAppPreferencesRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun <T> withRepository(file: File, block: suspend (AppPreferencesRepository) -> T): T {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return try {
            block(DataStoreAppPreferencesRepository(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun 背面での通信は既定でオン() = runBlocking {
        val file = File(tempFolder.root, "prefs.preferences_pb")

        assertTrue(withRepository(file) { it.backgroundCommsEnabled.first() })
    }

    @Test
    fun オフにした設定は作り直しても残る() = runBlocking {
        val file = File(tempFolder.root, "prefs.preferences_pb")

        withRepository(file) { it.setBackgroundCommsEnabled(false) }
        assertFalse(withRepository(file) { it.backgroundCommsEnabled.first() })

        withRepository(file) { it.setBackgroundCommsEnabled(true) }
        assertTrue(withRepository(file) { it.backgroundCommsEnabled.first() })
    }

    @Test
    fun 通知権限の要求済みフラグは既定でfalseで保存される() = runBlocking {
        val file = File(tempFolder.root, "prefs.preferences_pb")

        assertFalse(withRepository(file) { it.notificationPermissionRequested.first() })
        withRepository(file) { it.markNotificationPermissionRequested() }
        assertTrue(withRepository(file) { it.notificationPermissionRequested.first() })
    }
}
