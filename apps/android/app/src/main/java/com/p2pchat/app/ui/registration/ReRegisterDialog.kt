package com.p2pchat.app.ui.registration

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.p2pchat.app.R

/** 再登録の確認。連絡先一覧のバナーと設定画面の両方から使う。 */
@Composable
fun ReRegisterDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reregister_dialog_title)) },
        text = { Text(stringResource(R.string.reregister_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.reregister_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reregister_dialog_cancel)) }
        },
    )
}
