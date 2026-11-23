package com.p2pchat.app.ui.request

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.data.peer.ConnectionRequest
import kotlinx.coroutines.delay

/** どの画面でも、接続の申請があれば確認を出す。NavGraph の外側に置く。 */
@Composable
fun ConnectionRequestHost(viewModel: ConnectionRequestViewModel = viewModel(factory = ConnectionRequestViewModel.Factory)) {
    val request by viewModel.request.collectAsStateWithLifecycle()
    request?.let {
        ConnectionRequestDialog(request = it, onAllow = viewModel::allow, onDeny = viewModel::deny)
    }
}

@Composable
fun ConnectionRequestDialog(request: ConnectionRequest, onAllow: () -> Unit, onDeny: () -> Unit) {
    // 申請ごとに残り時間を数え直す（閉じるのは管理側。ここは表示だけ）
    var remainingSeconds by remember(request) { mutableLongStateOf(remainingSeconds(request)) }
    LaunchedEffect(request) {
        while (true) {
            remainingSeconds = remainingSeconds(request)
            delay(1_000)
        }
    }
    AlertDialog(
        // 外側のタップや戻るで誤って閉じない（閉じるのは許可・拒否・60 秒経過のとき）
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.connection_request_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.connection_request_message, request.name))
                Text(
                    text = stringResource(R.string.connection_request_remaining, remainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.connection_request_allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.connection_request_deny)) } },
    )
}

private fun remainingSeconds(request: ConnectionRequest): Long =
    ((request.expiresAtMillis - ConnectionRequest.nowMillis() + 999) / 1000).coerceAtLeast(0)
