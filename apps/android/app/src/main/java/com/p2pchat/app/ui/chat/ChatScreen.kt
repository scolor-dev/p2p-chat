package com.p2pchat.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.p2pchat.app.R
import com.p2pchat.app.ui.common.PlaceholderScreen

/** チャット画面（仮）。#26 で本実装にする。 */
@Composable
fun ChatScreen(deviceId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.chat_title),
        message = deviceId,
        onBack = onBack,
    )
}
