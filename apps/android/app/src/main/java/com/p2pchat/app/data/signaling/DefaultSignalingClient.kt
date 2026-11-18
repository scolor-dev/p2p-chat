package com.p2pchat.app.data.signaling

import com.p2pchat.app.data.device.DeviceIdentity
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.network.WebSocketClient
import com.p2pchat.app.data.network.WebSocketEvent
import com.p2pchat.app.data.network.WebSocketSession
import com.p2pchat.app.data.settings.ServerSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SignalingClient] の実装。#17 の [WebSocketClient] の上に、認証・`ready` 待ち・再接続を載せる。
 *
 * 接続先（[ServerSettingsRepository]）と身元（[DeviceRepository]）を見張り、
 * どちらかが変わったら、新しい値でつなぎ直す。どちらかが無ければ [SignalingState.NotConfigured]。
 */
class DefaultSignalingClient(
    private val webSocketClient: WebSocketClient,
    private val deviceRepository: DeviceRepository,
    private val serverSettingsRepository: ServerSettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    /** 接続を始めてから `ready` が届くまでの上限。過ぎたら切ってつなぎ直す。 */
    private val readyTimeoutMillis: Long = 10_000,
    /**
     * `ready` のあと、この時間つながり続けたら、待ち時間を最初（1 秒）に戻す。
     * `ready` の直後に切られ続ける場合に、毎秒つなぎ直さないように。
     */
    private val stableMillis: Long = 30_000,
    /**
     * 中の処理はすべてこの 1 本の流れで順に動かす（状態を複数のスレッドから同時に触らないように）。
     * テストでは仮想時間のディスパッチャーを渡す。
     */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : SignalingClient {

    private val _state = MutableStateFlow<SignalingState>(SignalingState.Stopped)
    override val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingSignal>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingSignal> = _incoming.asSharedFlow()

    /** `ready` を受け取った接続。それ以外のときは null（送らない）。 */
    @Volatile
    private var readySession: WebSocketSession? = null

    private var job: Job? = null

    override fun send(signal: OutgoingSignal): Boolean =
        readySession?.send(SignalingCodec.encode(signal)) ?: false

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        val previous = job
        // 前の接続の後片付け（状態を Stopped にする）が、新しい接続の状態を上書きしないように、終わるのを待つ
        job = scope.launch(dispatcher) {
            previous?.cancelAndJoin()
            maintain()
        }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        readySession = null
    }

    private suspend fun maintain() {
        try {
            combine(deviceRepository.identity, serverSettingsRepository.settings, ::Pair)
                .distinctUntilChanged()
                .collectLatest { (identity, settings) ->
                    if (identity == null || settings == null) {
                        _state.value = SignalingState.NotConfigured
                    } else {
                        keepConnected(settings.webSocketUrl, identity)
                    }
                }
        } finally {
            readySession = null
            _state.value = SignalingState.Stopped
        }
    }

    /** 止められるまで、接続 → 切断 → 待つ → 再接続 を繰り返す。 */
    private suspend fun keepConnected(url: String, identity: DeviceIdentity): Nothing {
        var failures = 0
        while (true) {
            _state.value = SignalingState.Connecting(attempt = failures + 1)
            val outcome = connectOnce(url, identity)
            // ready のあとしばらく続いたなら、サーバーは正常に動いていた。待ち時間は最初からにする
            if (outcome.wasStable) failures = 0

            when (val end = outcome.end) {
                SessionEnd.NetworkChanged -> failures = 0 // 新しい回線ですぐにつなぎ直す

                SessionEnd.Unauthorized -> {
                    _state.value = SignalingState.Unauthorized
                    networkMonitor.awaitNewNetwork()
                    failures = 0
                }

                is SessionEnd.Lost -> {
                    failures++
                    val delayMillis = backoff.delayMillis(failures)
                    _state.value = SignalingState.WaitingToReconnect(failures, delayMillis, end.reason)
                    // 待っている間に回線が切り替わったら、待たずにつなぎ直す
                    withTimeoutOrNull(delayMillis) { networkMonitor.awaitNewNetwork() }
                }
            }
        }
    }

    /** 1 回つないで、切れるまで（またはネットワークが切り替わるまで）待つ。 */
    private suspend fun connectOnce(url: String, identity: DeviceIdentity): Outcome = coroutineScope {
        var wasStable = false
        val session = async { runSession(url, identity) { wasStable = true } }
        val networkChanged = async { networkMonitor.awaitNewNetwork() }
        select {
            session.onAwait { end ->
                networkChanged.cancel()
                Outcome(end, wasStable)
            }
            networkChanged.onAwait {
                // 古い回線の接続は、ping が途切れるまで切れたと分からないので、こちらから切る
                session.cancel()
                Outcome(SessionEnd.NetworkChanged, wasStable)
            }
        }
    }

    /** 1 回分の接続。[onStable] は `ready` のあと [stableMillis] つながり続けたときに呼ぶ。 */
    private suspend fun runSession(url: String, identity: DeviceIdentity, onStable: () -> Unit): SessionEnd =
        coroutineScope {
            var end: SessionEnd = SessionEnd.Lost(null)
            val ready = CompletableDeferred<Unit>()
            var opened: WebSocketSession? = null

            val connection = launch {
                val headers = mapOf("Authorization" to "Bearer ${identity.token}")
                webSocketClient.connect(url, headers).collect { event ->
                    when (event) {
                        is WebSocketEvent.Opened -> opened = event.session
                        is WebSocketEvent.Message -> when (val signal = SignalingCodec.decode(event.text)) {
                            null -> Unit // 知らない type は無視する
                            is IncomingSignal.Ready -> {
                                ready.complete(Unit)
                                readySession = opened
                                _state.value = SignalingState.Ready(signal.deviceId)
                            }
                            else -> _incoming.emit(signal)
                        }
                        is WebSocketEvent.Closed -> end = SessionEnd.Lost(DisconnectReason.Closed(event.code))
                        is WebSocketEvent.Failure -> end =
                            if (event.httpStatus == HTTP_UNAUTHORIZED) SessionEnd.Unauthorized
                            else SessionEnd.Lost(
                                DisconnectReason.Failed(event.error.message ?: event.error.javaClass.simpleName),
                            )
                    }
                }
            }
            val readyWatcher = launch {
                if (withTimeoutOrNull(readyTimeoutMillis) { ready.await() } == null) {
                    end = SessionEnd.Lost(DisconnectReason.ReadyTimeout)
                    connection.cancel() // collect をやめると切断される
                    return@launch
                }
                delay(stableMillis)
                onStable()
            }

            try {
                connection.join()
                end
            } finally {
                readyWatcher.cancel()
                readySession = null
            }
        }

    /** [wasStable] は `ready` のあと [stableMillis] 以上つながっていたか。 */
    private data class Outcome(val end: SessionEnd, val wasStable: Boolean)

    /** 1 回の接続が終わった理由。 */
    private sealed interface SessionEnd {
        /** 切断・失敗・タイムアウト。待ってから再接続する。 */
        data class Lost(val reason: DisconnectReason?) : SessionEnd

        /** `401`。token が無効なので、きっかけがあるまで再試行しない。 */
        data object Unauthorized : SessionEnd

        /** ネットワークが切り替わったので、こちらから切った。すぐに再接続する。 */
        data object NetworkChanged : SessionEnd
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
