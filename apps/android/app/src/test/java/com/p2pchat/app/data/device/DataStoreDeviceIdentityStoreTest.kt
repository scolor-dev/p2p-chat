package com.p2pchat.app.data.device

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.p2pchat.app.data.crypto.AesGcmTokenCipher
import com.p2pchat.app.data.crypto.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class DataStoreDeviceIdentityStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmTokenCipher { key }
    private val token = "feedface".repeat(8)

    private suspend fun <T> withStore(
        file: File,
        tokenCipher: TokenCipher = cipher,
        block: suspend (DeviceIdentityStore) -> T,
    ): T {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return try {
            block(DataStoreDeviceIdentityStore(dataStore, tokenCipher))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun 未保存ならnull() = runBlocking {
        val file = File(tempFolder.root, "id.preferences_pb")

        assertNull(withStore(file) { it.identity.first() })
    }

    @Test
    fun 保存した身元は作り直しても読める() = runBlocking {
        val file = File(tempFolder.root, "id.preferences_pb")

        withStore(file) { it.save(DeviceIdentity("a1b2c3d4e5f60718", token)) }
        val identity = withStore(file) { it.identity.first() }

        assertEquals(DeviceIdentity("a1b2c3d4e5f60718", token), identity)
    }

    @Test
    fun ファイルにtokenが平文で残らない() = runBlocking {
        val file = File(tempFolder.root, "id.preferences_pb")

        withStore(file) { it.save(DeviceIdentity("a1b2c3d4e5f60718", token)) }

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(token))
        // deviceId は平文でよい
        assertEquals(true, raw.contains("a1b2c3d4e5f60718"))
    }

    @Test
    fun 鍵が失われて復号できないときは未登録として扱う() = runBlocking {
        val file = File(tempFolder.root, "id.preferences_pb")
        withStore(file) { it.save(DeviceIdentity("a1b2c3d4e5f60718", token)) }

        val lostKeyCipher = AesGcmTokenCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val identity = withStore(file, lostKeyCipher) { it.identity.first() }

        assertNull(identity)
    }

    @Test
    fun 暗号化に失敗したら例外になり何も保存されない() = runBlocking {
        val file = File(tempFolder.root, "id.preferences_pb")
        val failing = object : TokenCipher {
            override fun encrypt(plain: String): String = throw IllegalStateException("暗号化できない")
            override fun decrypt(encrypted: String): String = error("呼ばれない")
        }

        withStore(file, failing) { store ->
            assertThrows(IllegalStateException::class.java) { runBlocking { store.save(DeviceIdentity("id", token)) } }
            assertNull(store.identity.first())
        }
    }

    @Test
    fun 身元のtoStringにtokenを出さない() {
        assertFalse(DeviceIdentity("id", token).toString().contains(token))
    }
}
