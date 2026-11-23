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
)

/** [P2PChatTheme] が明暗に合わせて差し込む。 */
val LocalChatColors = staticCompositionLocalOf { LightChatColors }
