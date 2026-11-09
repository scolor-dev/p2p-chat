package com.p2pchat.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.p2pchat.app.R
import com.p2pchat.app.ui.common.PlaceholderScreen

/** 設定画面（仮）。#17 で本実装にする。 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.settings_title),
        message = stringResource(R.string.placeholder_message),
        onBack = onBack,
    )
}
