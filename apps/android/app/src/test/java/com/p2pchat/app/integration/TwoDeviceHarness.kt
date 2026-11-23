package com.p2pchat.app.integration

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.message.DefaultOutgoingMessageTracker
import com.p2pchat.app.data.message.InMemoryMessageRepository
import com.p2pchat.app.data.message.InMemoryReadStateRepository
import com.p2pchat.app.data.message.ReadReceiptSender
import com.p2pchat.app.data.message.IncomingMessageHandler
import com.p2pchat.app.data.message.P2PChatMessageSender
import com.p2pchat.app.data.message.TestDevice
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.ApiException
import com.p2pchat.app.data.network.RegisteredDevice
import com.p2pchat.app.data.network.RemoteDevice
import com.p2pchat.app.data.peer.DefaultConnectionRequestManager
import com.p2pchat.app.data.peer.DefaultPeerConnector
import com.p2pchat.app.data.peer.DefaultPeerReconnector
import com.p2pchat.app.data.peer.RtcConnectionState
import com.p2pchat.app.data.peer.RtcListener
import com.p2pchat.app.data.peer.RtcPeerConnection
import com.p2pchat.app.data.peer.RtcPeerConnectionFactory
import com.p2pchat.app.data.settings.ServerSettings
import com.p2pchat.app.data.settings.ServerSettingsRepository
import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.ErrorCodes
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.NetworkMonitor
import com.p2pchat.app.data.signaling.OutgoingSignal
import com.p2pchat.app.data.signaling.ReconnectBackoff
import com.p2pchat.app.data.signaling.SignalingClient
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/*
 * 2 台ぶんのアプリの部品を、偽のシグナリングサーバーと偽の WebRTC でつなぐ結合テスト用の道具（JVM のみ）。
 * 本物を使うのは、各部品（PeerConnector / ConnectionRequestManager / MessageSender / IncomingMessageHandler /
 * OutgoingMessageTracker / PeerReconnector）と AesGcmMessageCipher。
 * 偽物にするのは、WebSocket のサーバー、libwebrtc、DB、HTTP API だけ。
 */

/** 連絡先の保存先（メモリ）。同じ deviceId は上書きする。 */
class MemoryContacts(vararg initial: Contact) : ContactRepository {
    private val state = MutableStateFlow(initial.toList())
    val all: List<Contact> get() = state.value

    override fun observeContacts(): Flow<List<Contact>> = state
    override suspend fun getContact(deviceId: String): Contact? = state.value.find { it.deviceId == deviceId }
    override suspend fun saveContact(contact: Contact) {
        state.value = state.value.filter { it.deviceId != contact.deviceId } + contact
    }

    override suspend fun updateName(deviceId: String, name: String) = Unit
    override suspend fun updateVerified(deviceId: String, isVerified: Boolean) = Unit
    override suspend fun deleteContact(deviceId: String) = Unit
}

/**
 * 偽のシグナリングサーバー。端末ごとの [Endpoint]（[SignalingClient]）を持ち、offer / answer / candidate / reject を
 * 宛先の [Endpoint] に `from` を付けて渡す。宛先が online でなければ送り主に `error(peer-offline, to)` を返す。
 */
class FakeSignalingServer {
    private val endpoints = HashMap<String, Endpoint>()

    /** サーバーが取り次いだ信号（送り主, 信号）。 */
    val relayed = mutableListOf<Pair<String, OutgoingSignal>>()

    fun endpoint(deviceId: String): Endpoint = endpoints.getOrPut(deviceId) { Endpoint(deviceId) }

    fun offersSentBy(deviceId: String) = relayed.count { it.first == deviceId && it.second is OutgoingSignal.Offer }

    inner class Endpoint(private val deviceId: String) : SignalingClient {
        override val state = MutableStateFlow<SignalingState>(SignalingState.Ready(deviceId))
        override val incoming = MutableSharedFlow<IncomingSignal>(extraBufferCapacity = 1024)

        val online: Boolean get() = state.value is SignalingState.Ready

        /** WebSocket の切断（false）と再接続（true）。 */
        fun setOnline(on: Boolean) {
            state.value = if (on) SignalingState.Ready(deviceId) else SignalingState.WaitingToReconnect(1, 1_000, null)
        }

        override fun send(signal: OutgoingSignal): Boolean {
            if (!online) return false
            val target = endpoints[signal.to]
            if (target == null || !target.online) {
                incoming.tryEmit(IncomingSignal.ServerError(ErrorCodes.PEER_OFFLINE, "offline", signal.to))
                return true
            }
            relayed += deviceId to signal
            target.incoming.tryEmit(
                when (signal) {
                    is OutgoingSignal.Offer -> IncomingSignal.Offer(deviceId, signal.payload)
                    is OutgoingSignal.Answer -> IncomingSignal.Answer(deviceId, signal.payload)
                    is OutgoingSignal.Candidate -> IncomingSignal.Candidate(deviceId, signal.payload)
                    is OutgoingSignal.Reject -> IncomingSignal.Reject(deviceId)
                },
            )
            return true
        }

        override fun start() = Unit
        override fun stop() = Unit
    }
}

/**
 * 偽の WebRTC。[RtcPeerConnection] を 2 つ対にして、DataChannel の文字列を相手の [RtcListener] に渡す。
 * offer の SDP は `offer:<pc の番号>` で、answer する側が番号から相手の pc を探して対にする。
 */
class FakeWebRtcNetwork {
    private val all = mutableListOf<FakePc>()
    private var nextId = 0

    /** DataChannel が open になった回数（対 1 組につき 1）。 */
    var channelsOpened = 0
        private set

    /** 流れたフレーム（送り主, 宛先, テキスト）。[interceptor] が加工したあとのもの。 */
    val frames = mutableListOf<Triple<String, String, String>>()

    /** フレームを相手に渡す前の加工。null を返すと捨てる（ack の喪失、改ざんの再現）。 */
    var interceptor: (from: String, to: String, text: String) -> String? = { _, _, text -> text }

    /** ここまでに作られた PeerConnection の数。 */
    val created: Int get() = all.size

    fun factoryFor(deviceId: String) =
        RtcPeerConnectionFactory { listener -> FakePc(nextId++, deviceId, listener).also { all += it } }

    /** [from] から [to] へ、フレームを 1 つ余計に流し込む（二重配送の再現）。 */
    fun deliver(from: String, to: String, text: String) {
        all.last { it.owner == to && it.open && !it.closed && it.remote?.owner == from }.listener.onMessage(text)
    }

    /** 2 台のあいだの回線が切れた（Wi-Fi 断など）。生きている対の両側に closed を通知する。 */
    fun cut(a: String, b: String) {
        all.filter { it.open && !it.closed && setOf(it.owner, it.remote?.owner) == setOf(a, b) }.forEach { it.linkLost() }
    }

    private inner class FakePc(val id: Int, val owner: String, val listener: RtcListener) : RtcPeerConnection {
        var remote: FakePc? = null
        var open = false
        var closed = false

        override suspend fun createOffer(): String {
            listener.onLocalCandidate(CandidatePayload("cand-$id", "0", 0))
            return "offer:$id"
        }

        override suspend fun acceptOffer(sdp: String): String {
            val offerer = all.first { it.id == sdp.removePrefix("offer:").toInt() }
            remote = offerer
            offerer.remote = this
            listener.onLocalCandidate(CandidatePayload("cand-$id", "0", 0))
            return "answer:$id"
        }

        override suspend fun acceptAnswer(sdp: String) {
            val answerer = all.first { it.id == sdp.removePrefix("answer:").toInt() }
            check(remote === answerer) { "対になっていない" }
            channelsOpened++
            listOf(this, answerer).forEach {
                it.open = true
                it.listener.onConnectionState(RtcConnectionState.Connected)
                it.listener.onChannelOpen()
            }
        }

        override suspend fun addRemoteCandidate(candidate: CandidatePayload) = Unit

        override fun send(text: String): Boolean {
            val peer = remote ?: return false
            if (!open || closed || peer.closed) return false
            val delivered = interceptor(owner, peer.owner, text) ?: return true
            frames += Triple(owner, peer.owner, delivered)
            peer.listener.onMessage(delivered)
            return true
        }

        fun linkLost() {
            closed = true
            listener.onConnectionState(RtcConnectionState.Failed)
            listener.onChannelClosed()
        }

        override fun close() {
            if (closed) return
            closed = true
            // 相手には、DataChannel が閉じたことが伝わる
            remote?.takeIf { open && !it.closed }?.linkLost()
        }
    }
}

/** 回線の監視（このテストでは切り替えを起こさない）。 */
class StaticNetworkMonitor : NetworkMonitor {
    override val defaultNetwork: StateFlow<Long?> = MutableStateFlow(1L)
}

/** 偽の HTTP API。`GET /devices/{id}` だけ。 */
class FakeDirectoryApi(private val devices: Map<String, RemoteDevice>) : ApiClient {
    override suspend fun healthz(server: ServerSettings): Result<Unit> = error("使わない")
    override suspend fun registerDevice(server: ServerSettings, name: String, publicKey: String): Result<RegisteredDevice> =
        error("使わない")

    override suspend fun getDevice(server: ServerSettings, deviceId: String): Result<RemoteDevice> =
        devices[deviceId]?.let { Result.success(it) } ?: Result.failure(ApiException(404, null, null))
}

private class FixedSettings : ServerSettingsRepository {
    override val settings = MutableStateFlow<ServerSettings?>(ServerSettings("192.168.0.10", 8081))
    override suspend fun save(settings: ServerSettings) {
        this.settings.value = settings
    }
}

/** 1 台ぶんのアプリの部品。実際の組み立て（AppContainer）と同じ依存の向きで組む。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phone(
    testScope: TestScope,
    val device: TestDevice,
    server: FakeSignalingServer,
    network: FakeWebRtcNetwork,
    directory: FakeDirectoryApi,
    val contacts: MemoryContacts,
    ackTimeoutMillis: Long = 10_000L,
    backoff: ReconnectBackoff = ReconnectBackoff(jitter = 0.0),
) {
    val id: String get() = device.deviceId
    val signaling: FakeSignalingServer.Endpoint = server.endpoint(device.deviceId)
    val messages = InMemoryMessageRepository()

    /** 復号に失敗して捨てた msg の送り主（画面の警告の元）。 */
    val decryptFailures = mutableListOf<String>()

    private val scope = testScope.backgroundScope
    private val unconfined = UnconfinedTestDispatcher(testScope.testScheduler)
    private val now: () -> Long = { 1_000_000L + testScope.testScheduler.currentTime }

    val tracker = DefaultOutgoingMessageTracker(messages, scope, ackTimeoutMillis, now)

    // connector は manager を、manager は connector を使う。AppContainer と同じく、取り出しを遅らせて切る
    lateinit var requests: DefaultConnectionRequestManager
        private set

    val connector = DefaultPeerConnector(
        signaling = signaling,
        contactRepository = contacts,
        factory = network.factoryFor(device.deviceId),
        scope = scope,
        unknownOfferHandler = { requests.onOffer(it) },
    )

    init {
        requests = DefaultConnectionRequestManager(
            signaling = signaling,
            apiClient = directory,
            serverSettingsRepository = FixedSettings(),
            contactRepository = contacts,
            peerConnector = { connector },
            scope = scope,
            nowMillis = { testScope.testScheduler.currentTime },
            wallClockMillis = now,
        )
    }

    val incoming = IncomingMessageHandler(connector, contacts, messages, device.cipher, tracker, scope, unconfined, now)
    val sender = P2PChatMessageSender(contacts, connector, device.cipher, tracker, messages, unconfined, clock = now)
    val reconnector = DefaultPeerReconnector(connector, signaling, StaticNetworkMonitor(), backoff)

    /** 既読位置（チャットを開くと ChatViewModel が markRead で進める）と、それを `read` で伝える部品。 */
    val reads = InMemoryReadStateRepository()
    val receipts = ReadReceiptSender(connector, messages, reads, scope)

    /** [peerId] のチャットを開いて、いまある全メッセージを読んだことにする（ChatViewModel と同じ markRead）。 */
    suspend fun openChatAndRead(peerId: String) {
        val latest = messages.messages.value.filter { it.deviceId == peerId }.maxOfOrNull { it.sentAt } ?: return
        reads.markRead(peerId, latest)
    }

    fun start() {
        connector.start()
        incoming.start()
        receipts.start()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { incoming.decryptFailures.collect { decryptFailures += it } }
    }
}

fun remoteDeviceOf(d: TestDevice, name: String = d.deviceId) = RemoteDevice(d.deviceId, name, d.publicKeyBase64)
