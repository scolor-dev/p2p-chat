package com.p2pchat.app.data.peer

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.network.ApiClient
import com.p2pchat.app.data.network.RemoteDevice
import com.p2pchat.app.data.settings.ServerSettingsRepository
import com.p2pchat.app.data.signaling.CandidatePayload
import com.p2pchat.app.data.signaling.IncomingSignal
import com.p2pchat.app.data.signaling.OutgoingSignal
import com.p2pchat.app.data.signaling.SignalingClient
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ConnectionRequestManager] の実体。
 *
 * - [onOffer] はすぐ戻る（シグナリングの受信を止めないため）。申請ごとに別のコルーチンで、
 *   名前の取得（`GET /devices/{id}`）→ 確認待ち → 許可 / 拒否 を進める。
 * - 締め切りは offer を受けた時点から [timeoutMillis]（相手の接続タイムアウトも同じ 60 秒なので、
 *   順番待ちの間も進む）。過ぎたら何も送らずに捨てる。
 * - 同じ相手からの offer がまた届いたら、古い申請を捨てて新しい offer に差し替える（相手が作り直した可能性が高い）。
 * - 名前を取得できなかったら、公開鍵も分からず許可しても安全に保存できないので、確認を出さず reject を返す。
 * - シグナリングが `ready` でなくなったら、保留中の申請はすべて何も送らずに捨てる。
 * - 保留中の candidate は [DefaultPeerConnector] から渡されないので、自分で incoming から集める。
 */
class DefaultConnectionRequestManager(
    private val signaling: SignalingClient,
    private val apiClient: ApiClient,
    private val serverSettingsRepository: ServerSettingsRepository,
    private val contactRepository: ContactRepository,
    /** 循環（PeerConnector が handler を持つ）を避けるため、使うときに取り出す。 */
    private val peerConnector: () -> PeerConnector,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = ConnectionRequest::nowMillis,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : ConnectionRequestManager {

    private class Entry(val offer: IncomingSignal.Offer, val expiresAtMillis: Long) {
        val decision = CompletableDeferred<Boolean>()
        var job: Job? = null

        /** 名前を取得できて、確認を出せる状態になったら入る。 */
        var request: ConnectionRequest? = null
        var decided = false
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val candidateBuffers = HashMap<String, MutableList<CandidatePayload>>()

    private val _current = MutableStateFlow<ConnectionRequest?>(null)
    override val current: StateFlow<ConnectionRequest?> = _current.asStateFlow()

    init {
        // 購読を確実に offer より前に始める
        scope.launch(start = CoroutineStart.UNDISPATCHED) { signaling.incoming.collect { onSignal(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.state.collect { if (it !is SignalingState.Ready) clearAll() }
        }
    }

    override suspend fun onOffer(offer: IncomingSignal.Offer) {
        if (contactRepository.getContact(offer.from) != null) return
        val entry = Entry(offer, nowMillis() + timeoutMillis)
        val job = synchronized(lock) {
            entries.remove(offer.from)?.job?.cancel()
            entries[offer.from] = entry
            candidateBuffers.getOrPut(offer.from) { mutableListOf() }
            scope.launch(start = CoroutineStart.LAZY) { process(entry) }.also { entry.job = it }
        }
        job.start()
    }

    override fun allow() = decide(true)

    override fun deny() = decide(false)

    private fun decide(allowed: Boolean) {
        val entry = synchronized(lock) {
            val e = entries.values.firstOrNull { it.request != null && !it.decided } ?: return
            e.decided = true
            refresh()
            e
        }
        entry.decision.complete(allowed)
    }

    private suspend fun process(entry: Entry) {
        val peer = entry.offer.from
        try {
            val remaining = entry.expiresAtMillis - nowMillis()
            val outcome = withTimeoutOrNull(remaining) {
                val device = fetchDevice(peer) ?: return@withTimeoutOrNull null to false
                synchronized(lock) {
                    entry.request = ConnectionRequest(peer, device.name, entry.expiresAtMillis)
                    refresh()
                }
                device to entry.decision.await()
            } ?: return // 60 秒応答なし: 何も送らない
            val (device, allowed) = outcome
            if (allowed && device != null) accept(entry, device) else signaling.send(OutgoingSignal.Reject(peer))
        } finally {
            finish(entry)
        }
    }

    private suspend fun fetchDevice(peer: String): RemoteDevice? {
        return try {
            val server = serverSettingsRepository.settings.first() ?: return null
            apiClient.getDevice(server, peer).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun accept(entry: Entry, device: RemoteDevice) {
        val peer = entry.offer.from
        try {
            if (contactRepository.getContact(peer) == null) {
                contactRepository.saveContact(
                    Contact(
                        deviceId = peer,
                        name = device.name,
                        publicKey = device.publicKey,
                        isVerified = false,
                        createdAt = wallClockMillis(),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 連絡先に保存できなかったので、接続はしない
            signaling.send(OutgoingSignal.Reject(peer))
            return
        }
        val candidates = synchronized(lock) { candidateBuffers[peer].orEmpty().toList() }
        peerConnector().acceptOffer(entry.offer, candidates)
    }

    private fun finish(entry: Entry) {
        synchronized(lock) {
            val peer = entry.offer.from
            if (entries[peer] === entry) {
                entries.remove(peer)
                candidateBuffers.remove(peer)
            }
            refresh()
        }
    }

    private fun clearAll() {
        synchronized(lock) {
            if (entries.isEmpty() && candidateBuffers.isEmpty()) return
            val jobs = entries.values.mapNotNull { it.job }
            entries.clear()
            candidateBuffers.clear()
            refresh()
            jobs.forEach { it.cancel() }
        }
    }

    /** [lock] の中で呼ぶ。 */
    private fun refresh() {
        _current.value = entries.values.firstOrNull { !it.decided && it.request != null }?.request
    }

    private suspend fun onSignal(signal: IncomingSignal) {
        when (signal) {
            is IncomingSignal.Offer -> {
                // 連絡先にいない相手の offer の直後の candidate を取りこぼさないよう、onOffer より先に溜め始める
                if (contactRepository.getContact(signal.from) == null) startBuffer(signal.from)
            }
            is IncomingSignal.Candidate -> synchronized(lock) {
                candidateBuffers[signal.from]?.let { if (it.size < MAX_BUFFERED_CANDIDATES) it += signal.payload }
            }
            else -> Unit
        }
    }

    private fun startBuffer(peer: String) {
        val buffer = mutableListOf<CandidatePayload>()
        synchronized(lock) { candidateBuffers[peer] = buffer }
        // onOffer が来なかった（別の経路で処理された）場合に残り続けないようにする
        scope.launch {
            delay(timeoutMillis)
            synchronized(lock) {
                if (candidateBuffers[peer] === buffer && entries[peer] == null) candidateBuffers.remove(peer)
            }
        }
    }

    companion object {
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
        private const val MAX_BUFFERED_CANDIDATES = 100
    }
}
