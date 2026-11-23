package com.p2pchat.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** チャット画面専用の色。ライトがデザイン（docs/ui）の基準で、ダークは崩れないよう別に持つ。 */
data class ChatColors(
    /** トップバーと下部の帯。 */
    val bar: Color,
    val onBar: Color,
    val receivedBubble: Color,
    val onReceivedBubble: Color,
    val sentBubble: Color,
    val onSentBubble: Color,
    val inputContainer: Color,
    /** 入力欄の文字とヒント。 */
    val inputText: Color,
    /** 自分の吹き出しの下に出す「送信中…」「送信済み」（背景の上に載る淡い文字）。 */
    val sendStatus: Color,
    /** 「既読」（背景の上に載る。「送信済み」より目立たせすぎない強調）。 */
    val readStatus: Color,
    /** 「送信失敗（タップで再送）」。背景の上に載る。 */
    val sendFailed: Color,
    /** 復号失敗などの警告の帯と、その上の文字。 */
    val warning: Color,
    val onWarning: Color,
)

val LightChatColors = ChatColors(
    bar = MainBlue,
    onBar = Color.White,
    receivedBubble = Color(0xFFA0B9E3),
    onReceivedBubble = Color.Black,
    sentBubble = MainBlue,
    onSentBubble = Color(0xFFA0B9E3),
    inputContainer = Color.White,
    inputText = MainBlue,
    sendStatus = Color(0xFF5F6B85),
    readStatus = MainBlue,
    sendFailed = Color(0xFFB3261E),
    warning = Color(0xFFFFE9B8),
    onWarning = Color(0xFF3D2E00),
)

val DarkChatColors = ChatColors(
    bar = MainBlue,
    onBar = Color.White,
    receivedBubble = Color(0xFF2E3550),
    onReceivedBubble = Color(0xFFE6EAF5),
    sentBubble = MainBlue,
    onSentBubble = Color.White,
    inputContainer = MainDarkGray,
    inputText = Color(0xFFA0B9E3),
    sendStatus = Color(0xFFA9B1C7),
    readStatus = Color(0xFF8FB0FF),
    sendFailed = Color(0xFFFF8A80),
    warning = Color(0xFF4A3B12),
    onWarning = Color(0xFFFFE9B8),
)

/** [P2PChatTheme] が明暗に合わせて差し込む。 */
val LocalChatColors = staticCompositionLocalOf { LightChatColors }
