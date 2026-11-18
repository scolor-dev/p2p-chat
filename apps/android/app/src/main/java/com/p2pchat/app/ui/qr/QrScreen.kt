package com.p2pchat.app.ui.qr

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.p2pchat.app.R
import com.p2pchat.app.ui.common.PlaceholderScreen

/** QR 画面（仮）。#23（表示）と #24（読み取り）で本実装にする。 */
@Composable
fun QrScreen(onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.qr_title),
        message = stringResource(R.string.placeholder_message),
        onBack = onBack,
    )
}
