package com.p2pchat.app.data.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** OkHttp を使う実装。 */
class OkHttpWebSocketClient(
    private val client: OkHttpClient,
) : WebSocketClient {

    override fun connect(url: String, headers: Map<String, String>): Flow<WebSocketEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(WebSocketEvent.Opened(OkHttpWebSocketSession(webSocket)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(WebSocketEvent.Message(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 相手から切断されたら、こちらも閉じ返す
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WebSocketEvent.Closed(code, reason))
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WebSocketEvent.Failure(t))
                channel.close()
            }
        })

        awaitClose { webSocket.close(NORMAL_CLOSURE, null) }
    }.buffer(Channel.UNLIMITED) // 受信を取りこぼさないように、溜めておく

    private class OkHttpWebSocketSession(private val webSocket: WebSocket) : WebSocketSession {
        override fun send(text: String): Boolean = webSocket.send(text)
        override fun close() {
            webSocket.close(NORMAL_CLOSURE, null)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
