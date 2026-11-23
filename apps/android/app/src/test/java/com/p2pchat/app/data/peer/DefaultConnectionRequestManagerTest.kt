package com.p2pchat.app.data.peer

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.InMemoryContactRepository
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.network.RegisteredDevice
import com.p2pchat.app.data.network.RemoteDevice
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.FakeSignalingClient
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.OutgoingSignal
import com.p2pchat.app.data.signaling.SdpPayload
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultConnectionRequestManagerTest {
    private val alice = "aaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbb"

    private fun offer(from: String, sdp: String = "offer-$from") = IncomingSignal.Offer(from, SdpPayload(sdp))
    private fun cand(name: String) = CandidatePayload(name, "0", 0)

    private class FakeSettings : ServerSettingsRepository {
        override val settings = MutableStateFlow<ServerSettings?>(ServerSettings("10.0.2.2"))
        override suspend fun save(settings: ServerSettings) {
            this.settings.value = settings
        }
    }

    private class FakeApi : ApiClient {
        val devices = mutableMapOf<String, RemoteDevice>()
        val calls = mutableListOf<String>()

        /** 設定すると、getDevice はこれが完了するまで待つ。 */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun healthz(server: ServerSettings): Result<Unit> = error("使わない")
        override suspend fun registerDevice(server: ServerSettings, name: String, publicKey: String): Result<RegisteredDevice> =
            error("使わない")

        override suspend fun getDevice(server: ServerSettings, deviceId: String): Result<RemoteDevice> {
            calls += deviceId
            gate?.await()
            return devices[deviceId]?.let { Result.success(it) } ?: Result.failure(ApiException(404, null, null))
        }
    }

    /** acceptOffer の呼び出しを記録する。呼ばれていない = PeerConnection は作られていない。 */
    private class RecordingConnector : PeerConnector {
        private val fake = FakePeerConnector()
        val accepted = mutableListOf<Pair<IncomingSignal.Offer, List<CandidatePayload>>>()
        override val sessions get() = fake.sessions
        override val acceptedSessions get() = fake.acceptedSessions
        override fun start() = Unit
        override fun connect(peerDeviceId: String) = fake.connect(peerDeviceId)
        override fun disconnect(peerDeviceId: String) = Unit
        override fun acceptOffer(offer: IncomingSignal.Offer, candidates: List<CandidatePayload>): PeerSession {
            accepted += offer to candidates
            return fake.acceptOffer(offer, candidates)
        }
    }

    private class Env(scope: TestScope) {
        val signaling = FakeSignalingClient(SignalingState.Ready("5555555555555555"))
        val api = FakeApi()
        val contacts = InMemoryContactRepository()
        val connector = RecordingConnector()
        val manager = DefaultConnectionRequestManager(
            signaling = signaling,
            apiClient = api,
            serverSettingsRepository = FakeSettings(),
            contactRepository = contacts,
            peerConnector = { connector },
            scope = scope.backgroundScope,
            nowMillis = { scope.testScheduler.currentTime },
            wallClockMillis = { 123L },
        )
    }

    private fun TestScope.env(): Env = Env(this).also {
        it.api.devices["aaaaaaaaaaaaaaaa"] = RemoteDevice("aaaaaaaaaaaaaaaa", "Alice2", "pk-a")
        it.api.devices["bbbbbbbbbbbbbbbb"] = RemoteDevice("bbbbbbbbbbbbbbbb", "Bobby", "pk-b")
        runCurrent()
    }

    /** offer は、シグナリングの受信と、DefaultPeerConnector が呼ぶ onOffer の両方で渡す（実際の経路）。 */
    private suspend fun TestScope.receive(env: Env, signal: IncomingSignal) {
        env.signaling.incoming.emit(signal)
        runCurrent()
        if (signal is IncomingSignal.Offer) {
            env.manager.onOffer(signal)
            runCurrent()
        }
    }

    @Test
    fun `許可すると未確認の連絡先に追加して acceptOffer を呼び、保留中の candidate を渡す`() = runTest {
        val env = env()
        receive(env, offer(alice))
        // 名前を取得してから確認が出る。許可するまでは何も作らない
        assertEquals(listOf(alice), env.api.calls)
        assertEquals("Alice2", env.manager.current.value?.name)
        assertEquals(alice, env.manager.current.value?.deviceId)
        receive(env, IncomingSignal.Candidate(alice, cand("c1")))
        receive(env, IncomingSignal.Candidate(alice, cand("c2")))
        assertTrue(env.connector.accepted.isEmpty())
        assertNull(env.contacts.getContact(alice))
        assertTrue(env.signaling.sent.isEmpty())

        env.manager.allow()
        runCurrent()

        val contact = env.contacts.getContact(alice)
        assertEquals(Contact(alice, "Alice2", "pk-a", isVerified = false, createdAt = 123L), contact)
        assertEquals(1, env.connector.accepted.size)
        assertEquals(offer(alice), env.connector.accepted[0].first)
        assertEquals(listOf(cand("c1"), cand("c2")), env.connector.accepted[0].second)
        assertNull(env.manager.current.value)
        assertTrue(env.signaling.sent.isEmpty())
    }

    @Test
    fun `onOffer が遅れても、offer の直後に届いた candidate を取りこぼさない`() = runTest {
        val env = env()
        env.signaling.incoming.emit(offer(alice))
        env.signaling.incoming.emit(IncomingSignal.Candidate(alice, cand("early")))
        runCurrent()
        env.manager.onOffer(offer(alice))
        runCurrent()
        env.manager.allow()
        runCurrent()
        assertEquals(listOf(cand("early")), env.connector.accepted.single().second)
    }

    @Test
    fun `拒否すると reject を送り、連絡先にも追加せず PeerConnection も作らない`() = runTest {
        val env = env()
        receive(env, offer(alice))
        env.manager.deny()
        runCurrent()
        assertEquals(listOf<OutgoingSignal>(OutgoingSignal.Reject(alice)), env.signaling.sent)
        assertNull(env.contacts.getContact(alice))
        assertTrue(env.connector.accepted.isEmpty())
        assertNull(env.manager.current.value)
    }

    @Test
    fun `60 秒応答がなければ確認を閉じて何も送らない`() = runTest {
        val env = env()
        receive(env, offer(alice))
        assertNotNull(env.manager.current.value)
        advanceTimeBy(59_000)
        runCurrent()
        assertNotNull(env.manager.current.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertNull(env.manager.current.value)
        assertTrue(env.signaling.sent.isEmpty())
        assertTrue(env.connector.accepted.isEmpty())
        assertNull(env.contacts.getContact(alice))
        // 閉じたあとの許可は何も起こさない
        env.manager.allow()
        runCurrent()
        assertTrue(env.connector.accepted.isEmpty())
    }

    @Test
    fun `名前を取得できなければ確認を出さず reject を返す`() = runTest {
        val env = env()
        val stranger = "cccccccccccccccc"
        receive(env, offer(stranger))
        assertNull(env.manager.current.value)
        assertEquals(listOf<OutgoingSignal>(OutgoingSignal.Reject(stranger)), env.signaling.sent)
        assertTrue(env.connector.accepted.isEmpty())
        assertNull(env.contacts.getContact(stranger))
    }

    @Test
    fun `名前の取得中は確認を出さず、取得に時間がかかっても 60 秒で捨てる`() = runTest {
        val env = env()
        env.api.gate = CompletableDeferred()
        receive(env, offer(alice))
        assertNull(env.manager.current.value)
        advanceTimeBy(60_001)
        runCurrent()
        assertNull(env.manager.current.value)
        assertTrue(env.signaling.sent.isEmpty())
    }

    @Test
    fun `連絡先にいる相手の offer は対象外`() = runTest {
        val env = env()
        env.contacts.saveContact(Contact(alice, "Alice", "pk", isVerified = true, createdAt = 1))
        receive(env, offer(alice))
        assertNull(env.manager.current.value)
        assertTrue(env.api.calls.isEmpty())
        assertTrue(env.signaling.sent.isEmpty())
    }

    @Test
    fun `同じ相手からの offer が重複したら、新しい offer に差し替えて確認は 1 件のまま`() = runTest {
        val env = env()
        receive(env, offer(alice, "old"))
        receive(env, IncomingSignal.Candidate(alice, cand("old-c")))
        receive(env, offer(alice, "new"))
        receive(env, IncomingSignal.Candidate(alice, cand("new-c")))
        assertEquals(alice, env.manager.current.value?.deviceId)
        env.manager.allow()
        runCurrent()
        assertEquals(1, env.connector.accepted.size)
        assertEquals("new", env.connector.accepted[0].first.payload.sdp)
        assertEquals(listOf(cand("new-c")), env.connector.accepted[0].second)
        // 古い申請は reject も送らない
        assertTrue(env.signaling.sent.isEmpty())
        assertNull(env.manager.current.value)
    }

    @Test
    fun `複数人からの申請は届いた順に 1 件ずつ出す`() = runTest {
        val env = env()
        receive(env, offer(alice))
        receive(env, offer(bob))
        assertEquals(alice, env.manager.current.value?.deviceId)
        env.manager.deny()
        runCurrent()
        assertEquals(bob, env.manager.current.value?.deviceId)
        env.manager.allow()
        runCurrent()
        assertNull(env.manager.current.value)
        assertEquals(listOf<OutgoingSignal>(OutgoingSignal.Reject(alice)), env.signaling.sent)
        assertEquals(bob, env.connector.accepted.single().first.from)
    }

    @Test
    fun `順番待ちの間も締め切りは進み、待たされた申請は期限切れで消える`() = runTest {
        val env = env()
        receive(env, offer(alice))
        advanceTimeBy(30_000)
        receive(env, offer(bob))
        advanceTimeBy(30_001) // alice は期限切れ、bob は残り 30 秒
        runCurrent()
        assertEquals(bob, env.manager.current.value?.deviceId)
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(env.manager.current.value)
        assertTrue(env.signaling.sent.isEmpty())
    }

    @Test
    fun `シグナリングが切れたら保留中の申請は何も送らずに捨てる`() = runTest {
        val env = env()
        receive(env, offer(alice))
        assertNotNull(env.manager.current.value)
        env.signaling.state.value = SignalingState.Stopped
        runCurrent()
        assertNull(env.manager.current.value)
        assertTrue(env.signaling.sent.isEmpty())
        env.manager.allow()
        runCurrent()
        assertTrue(env.connector.accepted.isEmpty())
    }

    @Test
    fun `許可の二重タップや許可後の拒否は無視される`() = runTest {
        val env = env()
        receive(env, offer(alice))
        env.manager.allow()
        env.manager.allow()
        env.manager.deny()
        runCurrent()
        assertEquals(1, env.connector.accepted.size)
        assertTrue(env.signaling.sent.isEmpty())
    }
}
