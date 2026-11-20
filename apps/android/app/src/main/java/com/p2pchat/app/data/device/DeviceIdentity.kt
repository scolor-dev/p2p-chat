package com.p2pchat.app.data.device

/**
 * 登録済みのこの端末の「身元」。[deviceId] は表示してよいが、[token] は秘密。
 * [toString] には token を出さない（ログや例外メッセージに混ざらないように）。
 */
data class DeviceIdentity(val deviceId: String, val token: String) {
    override fun toString(): String = "DeviceIdentity(deviceId=$deviceId, token=***)"
}
