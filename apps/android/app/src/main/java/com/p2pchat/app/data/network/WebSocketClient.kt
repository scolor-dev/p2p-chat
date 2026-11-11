package com.p2pchat.app.data.network

import kotlinx.coroutines.flow.Flow

/**
 * WebSocket の最小限の部品。接続して、文字列を送受信するだけ。
 *
 * 認証（`Authorization` ヘッダー）、`ready` 待ち、再接続、メッセージの型は、
 * この部品の上に #20 で作る。
 */
interface WebSocketClient {
    /**
     * [url] に接続し、出来事を流す。collect している間だけ接続し、collect をやめると切断する。
     * 切断か失敗で Flow は終わる。再接続したいときは、もう一度 collect する。
     */
    fun connect(url: String, headers: Map<String, String> = emptyMap()): Flow<WebSocketEvent>
}

/** 接続中の WebSocket。[WebSocketEvent.Opened] で受け取る。 */
interface WebSocketSession {
    /** 送信の予約ができたら true。切断済みなどで送れないときは false。 */
    fun send(text: String): Boolean

    fun close()
}

sealed interface WebSocketEvent {
    data class Opened(val session: WebSocketSession) : WebSocketEvent
    data class Message(val text: String) : WebSocketEvent
    data class Closed(val code: Int, val reason: String) : WebSocketEvent
    data class Failure(val error: Throwable) : WebSocketEvent
}
