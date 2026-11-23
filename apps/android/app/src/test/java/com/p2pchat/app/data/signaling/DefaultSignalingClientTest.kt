package com.p2pchat.app.data.signaling

import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.FakeDeviceRepository
import com.p2pchat.app.data.network.WebSocketClient
import com.p2pchat.app.data.network.WebSocketEvent
import com.p2pchat.app.data.network.WebSocketSession
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DefaultSignalingClientTest {

    /** 1 回分の接続。テストからサーバー役として、開く・受信させる・切る、ができる。 */
    private class FakeConnection(val url: String, val headers: Map<String, String>) {
        val events = Channel<WebSocketEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        /** クライアントがまだ collect している（つながっている）か。 */
        var collecting = true

        private val session = object : WebSocketSession {
            override fun send(text: String): Boolean {
                if (!collecting) return false
                sent += text
                return true
            }

            override fun close() {
                events.close()
            }
        }

        fun open() = events.trySend(WebSocketEvent.Opened(session))
        fun receive(text: String) = events.trySend(WebSocketEvent.Message(text))
        fun ready(deviceId: String = DEVICE_ID) = receive("""{"type":"ready","deviceId":"$deviceId"}""")

        fun closeFromServer(code: Int = 1001) {
            events.trySend(WebSocketEvent.Closed(code, ""))
            events.close()
        }

        fun fail(httpStatus: Int? = null) {
            events.trySend(WebSocketEvent.Failure(IOException("つながりません"), httpStatus))
            events.close()
        }
    }

    private class FakeWebSocketClient : WebSocketClient {
        val connections = mutableListOf<FakeConnection>()

        override fun connect(url: String, headers: Map<String, String>): Flow<WebSocketEvent> = flow {
            val connection = FakeConnection(url, headers)
            connections += connection
            try {
                for (event in connection.events) emit(event)
            } finally {
                connection.collecting = false
            }
        }
    }

    private class FakeServerSettingsRepository(initial: ServerSettings?) : ServerSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: ServerSettings) {
            this.settings.value = settings
        }
    }

    private class FakeNetworkMonitor : NetworkMonitor {
        override val defaultNetwork = MutableStateFlow<Long?>(1L)
    }

    private val webSocket = FakeWebSocketClient()
    private val deviceRepository = FakeDeviceRepository(DeviceIdentity(DEVICE_ID, "secret-token"))
    private val settingsRepository = FakeServerSettingsRepository(ServerSettings("192.168.1.10"))
    private val network = FakeNetworkMonitor()

    /** ばらつきなし（1 秒 → 2 秒 → 4 秒 …）で作る。 */
    private fun TestScope.newClient() = DefaultSignalingClient(
        webSocketClient = webSocket,
        deviceRepository = deviceRepository,
        serverSettingsRepository = settingsRepository,
        networkMonitor = network,
        scope = backgroundScope,
        backoff = ReconnectBackoff(jitter = 0.0),
        readyTimeoutMillis = 10_000,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.startedClient(): DefaultSignalingClient = newClient().also {
        it.start()
        runCurrent()
    }

    private val SignalingClient.current get() = state.value

    @Test
    fun startするとtoken付きで接続しreadyでReadyになる() = runTest {
        val client = startedClient()

        assertEquals(SignalingState.Connecting(1), client.current)
        val connection = webSocket.connections.single()
        assertEquals("ws://192.168.1.10:8080/ws", connection.url)
        assertEquals("Bearer secret-token", connection.headers["Authorization"])

        connection.open()
        connection.ready()
        runCurrent()

        assertEquals(SignalingState.Ready(DEVICE_ID), client.current)
    }

    @Test
    fun readyまでは送らずready後はJSONで送る() = runTest {
        val client = startedClient()
        val connection = webSocket.connections.single()
        connection.open()
        runCurrent()

        assertFalse(client.send(OutgoingSignal.Reject("peer")))

        connection.ready()
        runCurrent()

        assertTrue(client.send(OutgoingSignal.Reject("peer")))
        assertEquals(listOf("""{"type":"reject","to":"peer"}"""), connection.sent)
    }

    @Test
    fun 受信したメッセージを流し知らないtypeは無視する() = runTest {
        val client = startedClient()
        val received = mutableListOf<IncomingSignal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.incoming.toList(received) }
        val connection = webSocket.connections.single()

        connection.open()
        connection.ready()
        connection.receive("""{"type":"hello"}""")
        connection.receive("""{"type":"offer","from":"peer","payload":{"sdp":"v=0"}}""")
        connection.receive("""{"type":"error","code":"peer-offline","message":"offline","to":"peer"}""")
        runCurrent()

        assertEquals(
            listOf(
                IncomingSignal.Offer("peer", SdpPayload("v=0")),
                IncomingSignal.ServerError(ErrorCodes.PEER_OFFLINE, "offline", "peer"),
            ),
            received,
        )
    }

    @Test
    fun サーバーが切断すると待ってから再接続しReadyに戻る() = runTest {
        val client = startedClient()
        val first = webSocket.connections.single()
        first.open()
        first.ready()
        runCurrent()

        // サーバーの再起動
        first.closeFromServer(1001)
        runCurrent()

        assertEquals(SignalingState.WaitingToReconnect(1, 1_000, DisconnectReason.Closed(1001)), client.current)
        assertFalse(client.send(OutgoingSignal.Reject("peer")))

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, webSocket.connections.size)

        advanceTimeBy(2)
        runCurrent()
        // 1 回切れたあとなので、2 回目の試行
        assertEquals(SignalingState.Connecting(2), client.current)
        val second = webSocket.connections[1]
        second.open()
        second.ready()
        runCurrent()

        assertEquals(SignalingState.Ready(DEVICE_ID), client.current)
    }

    /** 接続の失敗を 2 回続けて、待ち時間が 1 秒 → 2 秒と延びるのを確かめ、3 回目の接続を返す。 */
    private fun TestScope.failTwice(client: SignalingClient): FakeConnection {
        webSocket.connections[0].fail()
        runCurrent()
        assertEquals(SignalingState.WaitingToReconnect(1, 1_000, FAILED), client.current)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(SignalingState.Connecting(2), client.current)
        webSocket.connections[1].fail()
        runCurrent()
        assertEquals(SignalingState.WaitingToReconnect(2, 2_000, FAILED), client.current)

        advanceTimeBy(2_001)
        runCurrent()
        return webSocket.connections[2]
    }

    @Test
    fun 失敗が続くと待ち時間が延びreadyが30秒続けば最初に戻る() = runTest {
        val client = startedClient()
        val third = failTwice(client)
        third.open()
        third.ready()
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        third.closeFromServer(1001)
        runCurrent()

        assertEquals(SignalingState.WaitingToReconnect(1, 1_000, DisconnectReason.Closed(1001)), client.current)
    }

    @Test
    fun readyの直後に切られると待ち時間は延び続ける() = runTest {
        val client = startedClient()
        val third = failTwice(client)
        third.open()
        third.ready()
        runCurrent()

        advanceTimeBy(29_000)
        runCurrent()
        third.closeFromServer(1001)
        runCurrent()

        assertEquals(SignalingState.WaitingToReconnect(3, 4_000, DisconnectReason.Closed(1001)), client.current)
    }

    @Test
    fun readyのあとは時間が経っても切らない() = runTest {
        val client = startedClient()
        val connection = webSocket.connections.single()
        connection.open()
        connection.ready()
        runCurrent()

        // ready の待ち時間（10 秒）を大きく過ぎても、つながったまま
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(connection.collecting)
        assertEquals(1, webSocket.connections.size)
        assertEquals(SignalingState.Ready(DEVICE_ID), client.current)
        assertTrue(client.send(OutgoingSignal.Reject("peer")))
    }

    @Test
    fun 認証に失敗したら再試行せずネットワークが変わったら試す() = runTest {
        val client = startedClient()

        webSocket.connections.single().fail(httpStatus = 401)
        runCurrent()
        assertEquals(SignalingState.Unauthorized, client.current)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, webSocket.connections.size)

        network.defaultNetwork.value = 2L
        runCurrent()
        assertEquals(2, webSocket.connections.size)
        assertEquals(SignalingState.Connecting(1), client.current)
    }

    @Test
    fun 接続中にネットワークが切り替わるとすぐにつなぎ直す() = runTest {
        val client = startedClient()
        val first = webSocket.connections.single()
        first.open()
        first.ready()
        runCurrent()

        network.defaultNetwork.value = 2L
        runCurrent()

        assertFalse(first.collecting)
        assertEquals(2, webSocket.connections.size)
        assertEquals(SignalingState.Connecting(1), client.current)
    }

    @Test
    fun 再接続を待っている間にネットワークが切り替わると待たずにつなぐ() = runTest {
        val client = startedClient()
        webSocket.connections.single().fail()
        runCurrent()
        assertTrue(client.current is SignalingState.WaitingToReconnect)

        network.defaultNetwork.value = 2L
        runCurrent()

        assertEquals(2, webSocket.connections.size)
    }

    @Test
    fun ネットワークが切れただけでは再接続しない() = runTest {
        startedClient()
        webSocket.connections.single().open()
        runCurrent()

        network.defaultNetwork.value = null
        runCurrent()

        assertEquals(1, webSocket.connections.size)
        assertTrue(webSocket.connections.single().collecting)
    }

    @Test
    fun 未登録ならNotConfiguredで登録されるとつなぐ() = runTest {
        deviceRepository.identity.value = null
        val client = startedClient()

        assertEquals(SignalingState.NotConfigured, client.current)
        assertTrue(webSocket.connections.isEmpty())

        deviceRepository.identity.value = DeviceIdentity(DEVICE_ID, "secret-token")
        runCurrent()

        assertEquals(1, webSocket.connections.size)
    }

    @Test
    fun 接続先が未設定ならNotConfigured() = runTest {
        settingsRepository.settings.value = null
        val client = startedClient()

        assertEquals(SignalingState.NotConfigured, client.current)
        assertTrue(webSocket.connections.isEmpty())
    }

    @Test
    fun 接続先を変えると新しい接続先につなぎ直す() = runTest {
        startedClient()
        val first = webSocket.connections.single()

        settingsRepository.settings.value = ServerSettings("10.0.2.2", 9000)
        runCurrent()

        assertFalse(first.collecting)
        assertEquals("ws://10.0.2.2:9000/ws", webSocket.connections[1].url)
    }

    @Test
    fun stopで切断してStoppedになりstartでまたつなぐ() = runTest {
        val client = startedClient()
        val first = webSocket.connections.single()
        first.open()
        first.ready()
        runCurrent()

        client.stop()
        runCurrent()

        assertEquals(SignalingState.Stopped, client.current)
        assertFalse(first.collecting)
        assertFalse(client.send(OutgoingSignal.Reject("peer")))

        client.start()
        runCurrent()

        assertEquals(2, webSocket.connections.size)
        assertEquals(SignalingState.Connecting(1), client.current)
    }

    @Test
    fun startを2回呼んでも接続は1本() = runTest {
        val client = startedClient()
        client.start()
        runCurrent()

        assertEquals(1, webSocket.connections.size)
    }

    @Test
    fun readyが届かなければ切って再接続を待つ() = runTest {
        val client = startedClient()
        val connection = webSocket.connections.single()
        connection.open()
        runCurrent()

        advanceTimeBy(10_001)
        runCurrent()

        assertFalse(connection.collecting)
        assertEquals(SignalingState.WaitingToReconnect(1, 1_000, DisconnectReason.ReadyTimeout), client.current)
    }

    private companion object {
        const val DEVICE_ID = "a1b2c3d4e5f60718"

        /** [FakeConnection.fail] の理由。 */
        val FAILED = DisconnectReason.Failed("つながりません")
    }

    @Test
    fun 認証エラーのあと再登録して新しい身元になると新しいtokenでつなぎ直す() = runTest {
        val client = startedClient()
        webSocket.connections.single().fail(httpStatus = 401)
        runCurrent()
        assertEquals(SignalingState.Unauthorized, client.current)

        // 再登録: 身元が消え、新しい身元が保存される
        deviceRepository.clearIdentity()
        runCurrent()
        assertEquals(SignalingState.NotConfigured, client.current)
        deviceRepository.identity.value = DeviceIdentity("0123456789abcdef", "new-token")
        runCurrent()

        assertEquals(2, webSocket.connections.size)
        assertEquals("Bearer new-token", webSocket.connections.last().headers["Authorization"])
        webSocket.connections.last().open()
        webSocket.connections.last().ready("0123456789abcdef")
        runCurrent()
        assertEquals(SignalingState.Ready("0123456789abcdef"), client.current)
    }
}
