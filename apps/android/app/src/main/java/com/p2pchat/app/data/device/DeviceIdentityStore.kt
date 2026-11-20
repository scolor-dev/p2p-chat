package com.p2pchat.app.data.device

import kotlinx.coroutines.flow.Flow

/**
 * [DeviceIdentity]（`deviceId` と `token`）を端末に保存する窓口。
 * #20（WebSocket 接続）や #23（QR）は、ここから読む。
 */
interface DeviceIdentityStore {
    /** 保存されている身元。未登録、または token を復号できないときは null。 */
    val identity: Flow<DeviceIdentity?>

    /**
     * 身元を保存する。`deviceId` と `token` は 1 回の書き込みで保存し、片方だけ残ることはない。
     * 失敗したら例外を投げる（保存できていない）。
     */
    suspend fun save(identity: DeviceIdentity)
}
