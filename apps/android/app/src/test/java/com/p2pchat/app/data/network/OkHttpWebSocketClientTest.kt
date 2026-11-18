package com.p2pchat.app.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpWebSocketClientTest {
    private val server = MockWebServer()
    private val client = OkHttpWebSocketClient(OkHttpClient())

    /** サーバー役。つながったら "hello" を送り、届いた文字列を "echo:" を付けて返す。 */
    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("hello")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send("echo:$text")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    // OkHttp は実際のスレッドで動くので、仮想時間の runTest ではなく runBlocking を使う
    @Test
    fun 接続して送受信しヘッダーも送られる() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(serverListener).build())
        val url = server.url("/ws").toString().replaceFirst("http://", "ws://")

        val events = withTimeout(5_000) {
            val received = mutableListOf<WebSocketEvent>()
            client.connect(url, mapOf("Authorization" to "Bearer test-token")).collect { event ->
                received += event
                when (event) {
                    is WebSocketEvent.Opened -> event.session.send("ping")
                    is WebSocketEvent.Message ->
                        if (event.text == "echo:ping") (received.first() as WebSocketEvent.Opened).session.close()
                    else -> Unit
                }
            }
            received
        }

        assertTrue(events.first() is WebSocketEvent.Opened)
        assertEquals(
            listOf("hello", "echo:ping"),
            events.filterIsInstance<WebSocketEvent.Message>().map { it.text },
        )
        assertTrue(events.last() is WebSocketEvent.Closed)
        assertEquals("Bearer test-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun つながらなければFailureを流して終わる() = runBlocking {
        val url = server.url("/ws").toString().replaceFirst("http://", "ws://")
        server.close()

        val events = withTimeout(5_000) {
            val received = mutableListOf<WebSocketEvent>()
            client.connect(url).collect { received += it }
            received
        }

        assertEquals(1, events.size)
        assertTrue(events.single() is WebSocketEvent.Failure)
    }
}
