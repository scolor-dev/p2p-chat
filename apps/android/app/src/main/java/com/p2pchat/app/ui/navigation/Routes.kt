package com.p2pchat.app.ui.navigation

import kotlinx.serialization.Serializable

/**
 * 画面の「行き先」。画面を足すときは、ここに 1 つ追加する。
 * 引数が要る画面は data class にする（例: navigate(Chat(deviceId = "...")) ）。
 */
@Serializable
data object Contacts

@Serializable
data class Chat(val deviceId: String)

@Serializable
data object Qr

@Serializable
data object Settings
