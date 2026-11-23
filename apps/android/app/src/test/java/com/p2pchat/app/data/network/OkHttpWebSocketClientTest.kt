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
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

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
    fun 相手がcodeなしで閉じてもClosedで終わる() = runBlocking {
        // OkHttp のサーバー側では code なし（1005）の close を送れないので、生のソケットで WebSocket サーバー役をする
        ServerSocket(0).use { serverSocket ->
            val serverThread = thread { closeWithoutCode(serverSocket) }
            val url = "ws://127.0.0.1:${serverSocket.localPort}/ws"

            val events = withTimeout(5_000) {
                val received = mutableListOf<WebSocketEvent>()
                client.connect(url).collect { received += it }
                received
            }
            serverThread.join(5_000)

            assertTrue(events.first() is WebSocketEvent.Opened)
            assertEquals(NO_STATUS_CODE, (events.last() as WebSocketEvent.Closed).code)
        }
    }

    /** 1 本だけ受け付けて、ハンドシェイクのあと payload なしの close フレームを送る。 */
    private fun closeWithoutCode(serverSocket: ServerSocket) {
        serverSocket.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val key = generateSequence { reader.readLine() }
                .takeWhile { it.isNotEmpty() }
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(":").trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
            )
            val output = socket.getOutputStream()
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
            )
            output.write(byteArrayOf(0x88.toByte(), 0x00)) // FIN + close、payload の長さ 0
            output.flush()
            // クライアントが閉じ返してくるのを読み切ってから切る
            runCatching { while (socket.getInputStream().read() != -1) Unit }
        }
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
        val failure = events.single() as WebSocketEvent.Failure
        assertEquals(null, failure.httpStatus)
    }

    @Test
    fun サーバーが401で拒否したら状態コード付きのFailureを流す() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())
        val url = server.url("/ws").toString().replaceFirst("http://", "ws://")

        val events = withTimeout(5_000) {
            val received = mutableListOf<WebSocketEvent>()
            client.connect(url).collect { received += it }
            received
        }

        val failure = events.single() as WebSocketEvent.Failure
        assertEquals(401, failure.httpStatus)
    }

    private companion object {
        /** close フレームに code が無かったときの値。 */
        const val NO_STATUS_CODE = 1005
    }
}
