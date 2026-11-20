package com.p2pchat.app.data.network

import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
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

    @Test
    fun registerDeviceが201ならdeviceIdとtokenを返し正しいJSONを送る() = runTest {
        server.enqueue(
            MockResponse.Builder().code(201)
                .body("""{"deviceId":"a1b2c3d4e5f60718","token":"${"ab".repeat(32)}"}""").build(),
        )

        val result = apiClient.registerDevice(serverSettings(), "Pixel 8", "MFkwEwYH")

        assertEquals(RegisteredDevice("a1b2c3d4e5f60718", "ab".repeat(32)), result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/devices", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("name", "publicKey"), sent.keys)
        assertEquals("Pixel 8", sent["name"]!!.jsonPrimitive.content)
        assertEquals("MFkwEwYH", sent["publicKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun registerDeviceが400ならApiExceptionでcodeとmessageを返す() = runTest {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"code":"bad-request","message":"name must be 1-64 characters"}""").build(),
        )

        val e = apiClient.registerDevice(serverSettings(), "", "MFkwEwYH").exceptionOrNull() as ApiException

        assertEquals(400, e.status)
        assertEquals("bad-request", e.code)
        assertEquals("name must be 1-64 characters", e.serverMessage)
    }

    @Test
    fun registerDeviceで本文がJSONでないエラーでもApiExceptionになる() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())

        val e = apiClient.registerDevice(serverSettings(), "a", "k").exceptionOrNull() as ApiException

        assertEquals(500, e.status)
        assertEquals(null, e.code)
    }

    @Test
    fun registerDeviceで応答が壊れていると失敗し応答の中身を例外に含めない() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("""{"token":"secrettoken"""").build())

        val e = apiClient.registerDevice(serverSettings(), "a", "k").exceptionOrNull()!!

        assertTrue(e is IOException)
        assertTrue(!e.toString().contains("secrettoken"))
    }

    @Test
    fun registerDeviceでサーバーに届かなければ失敗() = runTest {
        val settings = serverSettings()
        server.close()

        val result = apiClient.registerDevice(settings, "a", "k")

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun getDeviceが200なら名前と公開鍵を返しGETで正しいパスを呼ぶ() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"deviceId":"a1b2c3d4e5f60718","name":"Alice","publicKey":"MFkwEwYH","createdAt":"2026-11-01T00:00:00Z"}""")
                .build(),
        )

        val result = apiClient.getDevice(serverSettings(), "a1b2c3d4e5f60718")

        assertEquals(RemoteDevice("a1b2c3d4e5f60718", "Alice", "MFkwEwYH"), result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/devices/a1b2c3d4e5f60718", request.url.encodedPath)
    }

    @Test
    fun getDeviceが404ならApiExceptionでstatusを返す() = runTest {
        server.enqueue(
            MockResponse.Builder().code(404).body("""{"code":"not-found","message":"device not found"}""").build(),
        )

        val e = apiClient.getDevice(serverSettings(), "nope").exceptionOrNull() as ApiException

        assertEquals(404, e.status)
        assertEquals("not-found", e.code)
    }

    @Test
    fun getDeviceはIDにスラッシュがあっても1区間のパスとして送る() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        apiClient.getDevice(serverSettings(), "a/../healthz")

        assertEquals("/devices/a%2F..%2Fhealthz", server.takeRequest().url.encodedPath)
    }

    @Test
    fun getDeviceで応答が壊れていると失敗する() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"name":"x"}""").build())

        val result = apiClient.getDevice(serverSettings(), "a1")

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun getDeviceでサーバーに届かなければ失敗() = runTest {
        val settings = serverSettings()
        server.close()

        val result = apiClient.getDevice(settings, "a1")

        assertTrue(result.exceptionOrNull() is IOException)
    }
}
