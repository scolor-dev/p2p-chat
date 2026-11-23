package com.p2pchat.app.data.peer

import com.p2pchat.app.data.signaling.NetworkMonitor
import com.p2pchat.app.data.signaling.ReconnectBackoff
import com.p2pchat.app.data.signaling.SignalingClient
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 話しかけたい相手との P2P 接続を、つながった状態に保つ（設計書 5-7）。接続の「確立」だけを担当し、
 * 履歴・送信中のメッセージには触らない。
 */
interface PeerReconnector {
    /** 切れた・失敗したあと、つなぎ直している最中の相手の deviceId。画面の接続状態の帯に使う。 */
    val reconnecting: StateFlow<Set<String>>

    /**
     * [peerDeviceId] とつながった状態に保つ。呼び出し元のコルーチンが続く間（チャット画面を開いている間）だけ動き、
     * キャンセルされたら止まる。相手が接続を拒否したときは、自分で終わる。
     */
    suspend fun keepConnected(peerDeviceId: String)
}

/** 再接続しない実装。開いたときに 1 回 [PeerConnector.connect] するだけ（テスト・既定値用）。 */
class ConnectOncePeerReconnector(private val connector: PeerConnector) : PeerReconnector {
    override val reconnecting: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override suspend fun keepConnected(peerDeviceId: String) {
        connector.connect(peerDeviceId)
    }
}

/**
 * [PeerReconnector] の実体。
 *
 * - session が Disconnected / Failed(ConnectionFailed, Timeout) になったら、[backoff] で待ち時間を延ばしながら [PeerConnector.connect] し直す。成功したらリセット。
 * - [PeerFailure.PeerOffline] は自動では再試行しない。WebSocket が `ready` に戻ったとき・回線が切り替わったときに 1 回だけ試す。
 *   [PeerFailure.Rejected] は終わる（相手が許可するまで待つ必要があるため）。
 * - WebSocket が `ready` でない間は試さず、`ready` になったら試す。WebSocket 自体の再接続は #20 の仕事。
 * - 回線が切り替わったら、待たずにすぐ張り直す。
 * - 1 人の相手につき 1 本のループで順に処理し、[PeerConnector.connect] は生きている session を返すので、二重には張らない。
 */
class DefaultPeerReconnector(
    private val connector: PeerConnector,
    private val signaling: SignalingClient,
    private val networkMonitor: NetworkMonitor,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
) : PeerReconnector {
    private val _reconnecting = MutableStateFlow<Set<String>>(emptySet())
    override val reconnecting: StateFlow<Set<String>> = _reconnecting.asStateFlow()

    override suspend fun keepConnected(peerDeviceId: String) = coroutineScope {
        // 回線が切り替わるたびに増える。待ち時間を飛ばす合図として使う
        val networkEpoch = MutableStateFlow(0)
        val watcher = launch {
            networkMonitor.defaultNetwork.drop(1).filterNotNull().collect {
                networkEpoch.update { it + 1 }
                connector.disconnect(peerDeviceId) // 古い回線の session を畳む（待ち時間なしで張り直される）
            }
        }
        try {
            loop(peerDeviceId, networkEpoch)
        } finally {
            watcher.cancel()
            setReconnecting(peerDeviceId, false)
        }
    }

    private suspend fun loop(peer: String, networkEpoch: StateFlow<Int>) {
        var failures = 0
        while (true) {
            signaling.state.first { it is SignalingState.Ready }
            val epochAtStart = networkEpoch.value
            val session = connector.connect(peer)

            var outcome = session.state.first { it == PeerState.Open || it.isTerminal }
            if (outcome == PeerState.Open) {
                failures = 0
                setReconnecting(peer, false)
                outcome = session.state.first { it.isTerminal }
                setReconnecting(peer, true) // 開いていた接続が切れた
            }

            when ((outcome as? PeerState.Failed)?.reason) {
                PeerFailure.Rejected -> return
                PeerFailure.PeerOffline -> {
                    setReconnecting(peer, false)
                    awaitRetrigger(networkEpoch)
                    continue
                }
                PeerFailure.SignalingUnavailable -> {
                    setReconnecting(peer, true)
                    // ready でなければ、ループの先頭で ready を待つ。ready なのに送れなかったときだけ待ち時間を置く
                    if (signaling.state.value !is SignalingState.Ready) continue
                }
                else -> setReconnecting(peer, true)
            }

            // 回線が切り替わった、または相手のほうから張り直してくれたなら、待たずに続ける
            if (networkEpoch.value != epochAtStart) {
                failures = 0
                continue
            }
            if (connector.sessions.value[peer]?.state?.value?.isTerminal == false) {
                failures = 0
                continue
            }
            failures++
            withTimeoutOrNull(backoff.delayMillis(failures)) {
                networkEpoch.first { it != epochAtStart }
            }
            if (networkEpoch.value != epochAtStart) failures = 0
        }
    }

    /** WebSocket が一度切れてまた `ready` になる、または回線が切り替わるまで待つ。 */
    private suspend fun awaitRetrigger(networkEpoch: StateFlow<Int>) {
        merge(
            signaling.state.map { it is SignalingState.Ready }.distinctUntilChanged().drop(1).filter { it },
            networkEpoch.drop(1).map { true },
        ).first()
    }

    private fun setReconnecting(peer: String, on: Boolean) {
        _reconnecting.update { if (on) it + peer else it - peer }
    }
}
