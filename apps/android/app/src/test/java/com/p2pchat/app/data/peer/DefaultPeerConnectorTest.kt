package com.p2pchat.app.data.peer

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.ErrorCodes
import com.p2pchat.app.data.signaling.FakeSignalingClient
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.OutgoingSignal
import com.p2pchat.app.data.signaling.SdpPayload
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRtc(val id: Int, val listener: RtcListener) : RtcPeerConnection {
    val calls = mutableListOf<String>()
    val sentTexts = mutableListOf<String>()
    var closed = false

    /** createOffer / acceptOffer の途中で出す candidate（SDP を送る前に見つかる場合の再現）。 */
    var candidatesDuringSdp = emptyList<CandidatePayload>()
    var failAcceptAnswer = false

    override suspend fun createOffer(): String {
        calls += "createOffer"
        candidatesDuringSdp.forEach(listener::onLocalCandidate)
        return "offer-$id"
    }

    override suspend fun acceptOffer(sdp: String): String {
        calls += "acceptOffer:$sdp"
        candidatesDuringSdp.forEach(listener::onLocalCandidate)
        return "answer-$id"
    }

    override suspend fun acceptAnswer(sdp: String) {
        calls += "acceptAnswer:$sdp"
        if (failAcceptAnswer) error("bad sdp")
    }

    override suspend fun addRemoteCandidate(candidate: CandidatePayload) {
        calls += "candidate:${candidate.candidate}"
    }

    override fun send(text: String): Boolean {
        sentTexts += text
        return true
    }

    override fun close() {
        closed = true
    }
}

private class FakeRtcFactory : RtcPeerConnectionFactory {
    val created = mutableListOf<FakeRtc>()
    var candidatesDuringSdp = emptyList<CandidatePayload>()

    override fun create(listener: RtcListener): RtcPeerConnection =
        FakeRtc(created.size, listener).also {
            it.candidatesDuringSdp = candidatesDuringSdp
            created += it
        }
}

private class FakeContacts(vararg ids: String) : ContactRepository {
    private val ids = ids.toSet()
    override fun observeContacts(): Flow<List<Contact>> = emptyFlow()
    override suspend fun getContact(deviceId: String): Contact? =
        if (deviceId in ids) Contact(deviceId, deviceId, "", true, 0L) else null
    override suspend fun saveContact(contact: Contact) = Unit
    override suspend fun updateName(deviceId: String, name: String) = Unit
    override suspend fun updateVerified(deviceId: String, isVerified: Boolean) = Unit
    override suspend fun deleteContact(deviceId: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPeerConnectorTest {
    private val small = "1111111111111111"
    private val large = "ffffffffffffffff"

    private fun cand(name: String) = CandidatePayload(name, "0", 0)
    private fun offerFrom(peer: String) = IncomingSignal.Offer(peer, SdpPayload("remote-offer"))

    private class Env(scope: CoroutineScope, self: String, contacts: ContactRepository) {
        val signaling = FakeSignalingClient(SignalingState.Ready(self))
        val factory = FakeRtcFactory()
        val unknownOffers = mutableListOf<IncomingSignal.Offer>()
        val connector = DefaultPeerConnector(
            signaling = signaling,
            contactRepository = contacts,
            factory = factory,
            scope = scope,
            unknownOfferHandler = { unknownOffers += it },
        ).also { it.start() }
    }

    private fun TestScope.env(self: String, vararg contacts: String) =
        Env(backgroundScope, self, FakeContacts(*contacts)).also { runCurrent() }

    private suspend fun TestScope.receive(env: Env, signal: IncomingSignal) {
        env.signaling.incoming.emit(signal)
        runCurrent()
    }

    @Test
    fun `話しかける側は offer を送り、answer と candidate を受けて open になる`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        assertEquals(PeerState.Connecting, session.state.value)
        runCurrent()

        assertEquals(listOf<OutgoingSignal>(OutgoingSignal.Offer(large, SdpPayload("offer-0"))), env.signaling.sent)
        val rtc = env.factory.created.single()

        receive(env, IncomingSignal.Answer(large, SdpPayload("remote-answer")))
        receive(env, IncomingSignal.Candidate(large, cand("c1")))
        assertEquals(listOf("createOffer", "acceptAnswer:remote-answer", "candidate:c1"), rtc.calls)

        val received = mutableListOf<String>()
        backgroundScope.launch { session.incoming.collect { received += it } }
        runCurrent()
        rtc.listener.onChannelOpen()
        runCurrent()
        assertEquals(PeerState.Open, session.state.value)
        assertTrue(session.send("hello"))
        assertEquals(listOf("hello"), rtc.sentTexts)

        rtc.listener.onMessage("from-peer")
        runCurrent()
        assertEquals(listOf("from-peer"), received)
    }

    @Test
    fun `offer を送る前に見つかった candidate は、offer のあとに送る`() = runTest {
        val env = env(small, large)
        env.factory.candidatesDuringSdp = listOf(cand("early"))
        env.connector.connect(large)
        runCurrent()

        assertEquals(
            listOf<OutgoingSignal>(
                OutgoingSignal.Offer(large, SdpPayload("offer-0")),
                OutgoingSignal.Candidate(large, cand("early")),
            ),
            env.signaling.sent,
        )
        // 以降に見つかったものはすぐ送る
        env.factory.created.single().listener.onLocalCandidate(cand("late"))
        runCurrent()
        assertEquals(OutgoingSignal.Candidate(large, cand("late")), env.signaling.sent.last())
    }

    @Test
    fun `answer より前に届いた candidate は溜めて、setRemoteDescription のあとに追加する`() = runTest {
        val env = env(small, large)
        env.connector.connect(large)
        runCurrent()
        val rtc = env.factory.created.single()

        receive(env, IncomingSignal.Candidate(large, cand("c1")))
        receive(env, IncomingSignal.Candidate(large, cand("c2")))
        assertEquals(listOf("createOffer"), rtc.calls)

        receive(env, IncomingSignal.Answer(large, SdpPayload("remote-answer")))
        assertEquals(listOf("createOffer", "acceptAnswer:remote-answer", "candidate:c1", "candidate:c2"), rtc.calls)
    }

    @Test
    fun `連絡先にいる相手の offer は自動で受け入れて answer を返す`() = runTest {
        val env = env(large, small)
        val accepted = mutableListOf<PeerSession>()
        backgroundScope.launch { env.connector.acceptedSessions.collect { accepted += it } }
        runCurrent()

        receive(env, offerFrom(small))
        receive(env, IncomingSignal.Candidate(small, cand("c1")))

        val rtc = env.factory.created.single()
        assertEquals(listOf("acceptOffer:remote-offer", "candidate:c1"), rtc.calls)
        assertEquals(listOf<OutgoingSignal>(OutgoingSignal.Answer(small, SdpPayload("answer-0"))), env.signaling.sent)
        assertEquals(1, accepted.size)
        assertSame(accepted.single(), env.connector.sessions.value[small])

        rtc.listener.onChannelOpen()
        runCurrent()
        assertEquals(PeerState.Open, accepted.single().state.value)
    }

    @Test
    fun `acceptOffer に渡した保留中の candidate は setRemoteDescription のあとに追加する`() = runTest {
        val env = env(large)
        env.connector.acceptOffer(offerFrom(small), listOf(cand("held1"), cand("held2")))
        runCurrent()

        assertEquals(listOf("acceptOffer:remote-offer", "candidate:held1", "candidate:held2"), env.factory.created.single().calls)
        assertEquals(1, env.signaling.sent.filterIsInstance<OutgoingSignal.Answer>().size)
    }

    @Test
    fun `連絡先にいない相手の offer は何もせず、UnknownOfferHandler に渡す`() = runTest {
        val env = env(large, small)
        receive(env, offerFrom("2222222222222222"))
        receive(env, IncomingSignal.Candidate("2222222222222222", cand("c1")))

        assertTrue(env.factory.created.isEmpty())
        assertTrue(env.signaling.sent.isEmpty())
        assertTrue(env.connector.sessions.value.isEmpty())
        assertEquals(listOf(offerFrom("2222222222222222")), env.unknownOffers)
    }

    @Test
    fun `同時に offer を送り合ったとき deviceId が小さい側は相手の offer を無視する`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()

        receive(env, offerFrom(large))
        assertEquals(PeerState.Connecting, session.state.value)
        assertEquals(1, env.factory.created.size)
        assertFalse(env.factory.created.single().closed)
        assertEquals(1, env.signaling.sent.size) // 自分の offer だけ

        // 相手は rollback して answer を返してくる
        receive(env, IncomingSignal.Answer(large, SdpPayload("remote-answer")))
        env.factory.created.single().listener.onChannelOpen()
        runCurrent()
        assertEquals(PeerState.Open, session.state.value)
    }

    @Test
    fun `同時に offer を送り合ったとき deviceId が大きい側は自分の offer を捨てて相手の offer を受け入れる`() = runTest {
        val env = env(large, small)
        val session = env.connector.connect(small)
        runCurrent()
        val first = env.factory.created.single()

        receive(env, offerFrom(small))

        assertTrue(first.closed)
        assertEquals(2, env.factory.created.size)
        assertEquals(listOf("acceptOffer:remote-offer"), env.factory.created[1].calls)
        assertEquals(
            listOf<OutgoingSignal>(
                OutgoingSignal.Offer(small, SdpPayload("offer-0")),
                OutgoingSignal.Answer(small, SdpPayload("answer-1")),
            ),
            env.signaling.sent,
        )
        // session は同じまま。捨てた PeerConnection からの通知は無視する
        assertSame(session, env.connector.sessions.value[small])
        first.listener.onChannelClosed()
        runCurrent()
        assertEquals(PeerState.Connecting, session.state.value)

        env.factory.created[1].listener.onChannelOpen()
        runCurrent()
        assertEquals(PeerState.Open, session.state.value)
    }

    @Test
    fun `peer-offline のエラーで失敗する`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()

        receive(env, IncomingSignal.ServerError(ErrorCodes.PEER_OFFLINE, "offline", to = large))

        assertEquals(PeerState.Failed(PeerFailure.PeerOffline), session.state.value)
        assertTrue(env.factory.created.single().closed)
    }

    @Test
    fun `別の相手宛ての peer-offline は影響しない`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()

        receive(env, IncomingSignal.ServerError(ErrorCodes.PEER_OFFLINE, "offline", to = "3333333333333333"))
        assertEquals(PeerState.Connecting, session.state.value)
    }

    @Test
    fun `reject を受けたら拒否された失敗になる`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()

        receive(env, IncomingSignal.Reject(large))
        assertEquals(PeerState.Failed(PeerFailure.Rejected), session.state.value)
    }

    @Test
    fun `60 秒で open にならなければタイムアウトで失敗する`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()

        advanceTimeBy(CONNECT_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(PeerState.Connecting, session.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(PeerState.Failed(PeerFailure.Timeout), session.state.value)
    }

    @Test
    fun `open になったあとはタイムアウトしない`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()
        env.factory.created.single().listener.onChannelOpen()
        runCurrent()

        advanceTimeBy(CONNECT_TIMEOUT_MILLIS * 2)
        runCurrent()
        assertEquals(PeerState.Open, session.state.value)
    }

    @Test
    fun `シグナリングが ready でないときは失敗した session を返す`() = runTest {
        val env = env(small, large)
        env.signaling.state.value = SignalingState.Stopped

        val session = env.connector.connect(large)
        runCurrent()

        assertEquals(PeerState.Failed(PeerFailure.SignalingUnavailable), session.state.value)
        assertTrue(env.factory.created.isEmpty())
    }

    @Test
    fun `offer を送れなかったら失敗する`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        env.signaling.state.value = SignalingState.Stopped // connect 後、offer を送る前に切れた
        runCurrent()

        assertEquals(PeerState.Failed(PeerFailure.SignalingUnavailable), session.state.value)
    }

    @Test
    fun `answer の SDP が不正なら接続失敗になる`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()
        env.factory.created.single().failAcceptAnswer = true

        receive(env, IncomingSignal.Answer(large, SdpPayload("broken")))
        assertEquals(PeerState.Failed(PeerFailure.ConnectionFailed), session.state.value)
    }

    @Test
    fun `open のあと DataChannel が閉じたら切断になり、connect で新しい session を張れる`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()
        env.factory.created.single().listener.onChannelOpen()
        runCurrent()

        env.factory.created.single().listener.onChannelClosed()
        runCurrent()
        assertEquals(PeerState.Disconnected, session.state.value)
        assertFalse(session.send("x"))
        assertTrue(env.factory.created.single().closed)

        val again = env.connector.connect(large)
        runCurrent()
        assertNotSame(session, again)
        assertEquals(PeerState.Connecting, again.state.value)
        assertEquals(2, env.factory.created.size)
    }

    @Test
    fun `ICE が failed になったら、open 前は失敗、open 後は切断になる`() = runTest {
        val env = env(small, large, "4444444444444444")
        val connecting = env.connector.connect(large)
        val opened = env.connector.connect("4444444444444444")
        runCurrent()
        val (rtc1, rtc2) = env.factory.created
        rtc2.listener.onChannelOpen()
        runCurrent()

        rtc1.listener.onConnectionState(RtcConnectionState.Failed)
        rtc2.listener.onConnectionState(RtcConnectionState.Failed)
        runCurrent()

        assertEquals(PeerState.Failed(PeerFailure.ConnectionFailed), connecting.state.value)
        assertEquals(PeerState.Disconnected, opened.state.value)
    }

    @Test
    fun `disconnect で切ると Disconnected になり PeerConnection を閉じる`() = runTest {
        val env = env(small, large)
        val session = env.connector.connect(large)
        runCurrent()

        env.connector.disconnect(large)
        assertEquals(PeerState.Disconnected, session.state.value)
        runCurrent()
        assertTrue(env.factory.created.single().closed)
    }

    @Test
    fun `接続中の相手に connect を重ねても同じ session を返す`() = runTest {
        val env = env(small, large)
        val first = env.connector.connect(large)
        val second = env.connector.connect(large)
        runCurrent()
        assertSame(first, second)
        assertEquals(1, env.factory.created.size)
    }

    @Test
    fun `session が無い相手の candidate や answer は無視する`() = runTest {
        val env = env(small, large)
        receive(env, IncomingSignal.Candidate(large, cand("c1")))
        receive(env, IncomingSignal.Answer(large, SdpPayload("x")))
        assertTrue(env.factory.created.isEmpty())
        assertTrue(env.connector.sessions.value.isEmpty())
    }
}
