package com.p2pchat.app.data.peer

import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.FakeSignalingClient
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.NetworkMonitor
import com.p2pchat.app.data.signaling.ReconnectBackoff
import com.p2pchat.app.data.signaling.SdpPayload
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerReconnectorTest {
    private val peer = "a1"

    /** connect の回数と、作った session を記録する。session の状態はテストが動かす。 */
    private class RecordingConnector : PeerConnector {
        private val _sessions = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
        override val sessions: StateFlow<Map<String, PeerSession>> = _sessions
        override val acceptedSessions: SharedFlow<PeerSession> = MutableSharedFlow()
        val created = mutableListOf<FakePeerSession>()

        override fun start() = Unit

        override fun connect(peerDeviceId: String): PeerSession {
            _sessions.value[peerDeviceId]?.let { if (!it.state.value.isTerminal) return it }
            return FakePeerSession(peerDeviceId, PeerState.Connecting).also {
                created += it
                _sessions.value = _sessions.value + (peerDeviceId to it)
            }
        }

        override fun disconnect(peerDeviceId: String) {
            _sessions.value[peerDeviceId]?.close()
        }

        override fun acceptOffer(offer: IncomingSignal.Offer, candidates: List<CandidatePayload>): PeerSession =
            FakePeerSession(offer.from).also {
                created += it
                _sessions.value = _sessions.value + (offer.from to it)
            }

        fun lastSession() = created.last()
    }

    private class FakeNetwork : NetworkMonitor {
        override val defaultNetwork = MutableStateFlow<Long?>(1L)
    }

    private val connector = RecordingConnector()
    private val signaling = FakeSignalingClient(SignalingState.Ready("self"))
    private val network = FakeNetwork()
    private val reconnector =
        DefaultPeerReconnector(connector, signaling, network, ReconnectBackoff(jitter = 0.0))

    /** keepConnected を動かし始める。止めるときは返り値の Job をキャンセルする。 */
    private fun TestScope.keep() = backgroundScope.launch { reconnector.keepConnected(peer) }.also { runCurrent() }

    private fun TestScope.open() {
        connector.lastSession().state.value = PeerState.Open
        runCurrent()
    }

    @Test
    fun `画面を開くと接続し、open になっても追加の connect はしない`() = runTest {
        keep()
        assertEquals(1, connector.created.size)
        open()
        advanceTimeBy(60_000)
        assertEquals(1, connector.created.size)
        assertTrue(reconnector.reconnecting.value.isEmpty())
    }

    @Test
    fun `切断すると待ち時間を伸ばしながら再試行し、成功でリセットする`() = runTest {
        keep()
        open()

        connector.lastSession().state.value = PeerState.Disconnected // open だった接続が切れる
        runCurrent()
        assertEquals(setOf(peer), reconnector.reconnecting.value)
        advanceTimeBy(999)
        assertEquals(1, connector.created.size)
        advanceTimeBy(2)
        assertEquals(2, connector.created.size) // 1 秒後

        connector.lastSession().state.value = PeerState.Failed(PeerFailure.ConnectionFailed)
        runCurrent()
        advanceTimeBy(1_999)
        assertEquals(2, connector.created.size)
        advanceTimeBy(2)
        assertEquals(3, connector.created.size) // 2 秒後

        connector.lastSession().state.value = PeerState.Failed(PeerFailure.Timeout)
        runCurrent()
        advanceTimeBy(3_999)
        assertEquals(3, connector.created.size)
        advanceTimeBy(2)
        assertEquals(4, connector.created.size) // 4 秒後

        open() // 成功 → リセット
        assertTrue(reconnector.reconnecting.value.isEmpty())
        connector.lastSession().state.value = PeerState.Disconnected
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(5, connector.created.size) // また 1 秒
    }

    @Test
    fun `PeerOffline は自動では再試行せず、WebSocket が ready に戻ったときに 1 回試す`() = runTest {
        keep()
        connector.lastSession().state.value = PeerState.Failed(PeerFailure.PeerOffline)
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(1, connector.created.size)
        assertTrue(reconnector.reconnecting.value.isEmpty())

        signaling.state.value = SignalingState.Connecting(2)
        runCurrent()
        assertEquals(1, connector.created.size)
        signaling.state.value = SignalingState.Ready("self")
        runCurrent()
        assertEquals(2, connector.created.size)

        connector.lastSession().state.value = PeerState.Failed(PeerFailure.PeerOffline)
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(2, connector.created.size) // 1 回きり
    }

    @Test
    fun `Rejected は再試行しない`() = runTest {
        keep()
        connector.lastSession().state.value = PeerState.Failed(PeerFailure.Rejected)
        runCurrent()
        signaling.state.value = SignalingState.Connecting(2)
        runCurrent()
        signaling.state.value = SignalingState.Ready("self")
        network.defaultNetwork.value = 2L
        advanceTimeBy(120_000)
        assertEquals(1, connector.created.size)
    }

    @Test
    fun `画面を閉じたら再接続をやめる`() = runTest {
        val job = keep()
        open()
        connector.lastSession().state.value = PeerState.Disconnected
        runCurrent()
        job.cancel()
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(1, connector.created.size)
        assertTrue(reconnector.reconnecting.value.isEmpty())
    }

    @Test
    fun `WebSocket が ready でない間は試さず、ready になったら試す`() = runTest {
        signaling.state.value = SignalingState.Connecting(1)
        keep()
        advanceTimeBy(30_000)
        assertEquals(0, connector.created.size)

        signaling.state.value = SignalingState.Ready("self")
        runCurrent()
        assertEquals(1, connector.created.size)
        open()

        // open のあと WebSocket も切れた: 待ち時間が過ぎても ready になるまで試さない
        signaling.state.value = SignalingState.WaitingToReconnect(1, 1_000, null)
        connector.lastSession().state.value = PeerState.Disconnected
        runCurrent()
        advanceTimeBy(30_000)
        assertEquals(1, connector.created.size)
        signaling.state.value = SignalingState.Ready("self")
        runCurrent()
        assertEquals(2, connector.created.size)
    }

    @Test
    fun `回線が切り替わったら待たずにすぐ張り直す`() = runTest {
        keep()
        open()

        network.defaultNetwork.value = 2L
        runCurrent()
        assertEquals(2, connector.created.size) // 待ち時間なし
        assertEquals(PeerState.Disconnected, connector.created[0].state.value)

        // 待ち時間の途中で回線が切り替わっても、すぐ試す
        connector.lastSession().state.value = PeerState.Failed(PeerFailure.ConnectionFailed)
        runCurrent()
        advanceTimeBy(500)
        network.defaultNetwork.value = 3L
        runCurrent()
        assertEquals(3, connector.created.size)
    }

    @Test
    fun `相手のほうから張り直してくれたなら、二重に connect しない`() = runTest {
        keep()
        open()

        // 相手の offer を受け入れた session が先に出来て、古い session が閉じられる
        val accepted = connector.acceptOffer(IncomingSignal.Offer(peer, SdpPayload("x")))
        connector.created[0].close()
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(2, connector.created.size)
        assertEquals(PeerState.Open, accepted.state.value)
    }

    @Test
    fun `接続中は connect を重ねない`() = runTest {
        keep()
        advanceTimeBy(30_000) // Connecting のまま
        assertEquals(1, connector.created.size)
    }
}
