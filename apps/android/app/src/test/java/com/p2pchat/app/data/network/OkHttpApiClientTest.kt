package com.p2pchat.app.data.network

import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 実際の HTTP サーバーの代わりに MockWebServer を立てて確かめる。 */
class OkHttpApiClientTest {
    private val server = MockWebServer()
    private val apiClient = OkHttpApiClient(
        OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build(),
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun serverSettings() = ServerSettings(server.hostName, server.port)

    @Test
    fun healthzが200なら成功() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val result = apiClient.healthz(serverSettings())

        assertTrue(result.isSuccess)
        assertEquals("/healthz", server.takeRequest().url.encodedPath)
    }

    @Test
    fun healthzが200以外なら失敗し状態コードを返す() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())

        val result = apiClient.healthz(serverSettings())

        assertEquals("HTTP 500", result.exceptionOrNull()?.message)
    }

    @Test
    fun サーバーに届かなければ失敗() = runTest {
        val settings = serverSettings()
        server.close()

        val result = apiClient.healthz(settings)

        assertTrue(result.isFailure)
    }
}
