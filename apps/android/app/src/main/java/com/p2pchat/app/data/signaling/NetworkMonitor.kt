package com.p2pchat.app.data.signaling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/** 端末がいま使っている回線（既定のネットワーク）を見張る。Wi-Fi ⇔ モバイルの切り替えなどを知るため。 */
interface NetworkMonitor {
    /** 既定のネットワークを見分ける値。つながっていなければ null。 */
    val defaultNetwork: StateFlow<Long?>
}

/** 呼び出した時点より**あと**に、新しいネットワークが使えるようになるまで待つ。 */
suspend fun NetworkMonitor.awaitNewNetwork() {
    // StateFlow は最初に今の値を流すので、それを読み飛ばす
    defaultNetwork.drop(1).filterNotNull().first()
}

/** [ConnectivityManager] を使う実装。 */
class ConnectivityNetworkMonitor(context: Context, scope: CoroutineScope) : NetworkMonitor {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override val defaultNetwork: StateFlow<Long?> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                current = network
                trySend(network.networkHandle)
            }

            override fun onLost(network: Network) {
                // 切り替えでは、新しい回線の onAvailable のあとに古い回線の onLost が来ることがある
                if (network == current) {
                    current = null
                    trySend(null)
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        // 登録直後の onAvailable を「切り替え」と取り違えないように、今の回線から始める
        initialValue = connectivityManager.activeNetwork?.networkHandle,
    )
}
