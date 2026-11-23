package com.p2pchat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.ui.notification.RequestNotificationPermissionOnFirstChat
import com.p2pchat.app.ui.theme.LocalChatColors
import com.p2pchat.app.ui.theme.P2PChatTheme

/** 画面の入口。ViewModel の状態を [ChatContent] に渡す。 */
@Composable
fun ChatScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(key = deviceId, factory = ChatViewModel.factory(deviceId)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RequestNotificationPermissionOnFirstChat()
    // 前面にある間だけ、届いたメッセージを既読にする
    LifecycleStartEffect(viewModel) {
        viewModel.onVisibleChanged(true)
        onStopOrDispose { viewModel.onVisibleChanged(false) }
    }
    ChatContent(
        uiState = uiState,
        onBack = onBack,
        inputState = viewModel.inputState,
        onSendClick = viewModel::onSendClick,
        onResendClick = viewModel::onResendClick,
    )
}

/** ViewModel を知らない、表示だけの部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    uiState: ChatUiState,
    onBack: () -> Unit,
    inputState: TextFieldState,
    onSendClick: () -> Unit,
    onResendClick: (messageId: String) -> Unit = {},
) {
    val colors = LocalChatColors.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = uiState.contactName ?: stringResource(R.string.chat_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_arrow_back_ios_new_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bar,
                    titleContentColor = colors.onBar,
                    navigationIconContentColor = colors.onBar,
                ),
            )
        },
        bottomBar = {
            ChatInputBar(
                inputState = inputState,
                canSend = uiState.canSend,
                errorResId = when {
                    uiState.isInputTooLong -> R.string.chat_error_too_long
                    uiState.sendFailed -> uiState.sendError.messageResId()
                    else -> null
                },
                onSendClick = onSendClick,
            )
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LaunchedEffect(uiState.messages.size) {
            if (uiState.messages.isNotEmpty()) listState.scrollToItem(uiState.messages.lastIndex)
        }
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ConnectionBand(uiState.connection)
            if (uiState.decryptWarning) DecryptWarningBand()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 16.dp),
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message, onResendClick)
                }
            }
        }
    }
}

/** トップバー下の細い帯。接続の状態を 1 行で控えめに出す。 */
@Composable
private fun ConnectionBand(connection: ChatConnection) {
    val colors = LocalChatColors.current
    Text(
        text = stringResource(connection.labelResId()),
        color = colors.onBar.copy(alpha = 0.8f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bar.copy(alpha = 0.6f))
            .padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

/** 復号できないメッセージを捨てた直後に、数秒だけ出す 1 行。原因は断定しない。 */
@Composable
private fun DecryptWarningBand() {
    val colors = LocalChatColors.current
    Text(
        text = stringResource(R.string.chat_warning_decrypt_failed),
        color = colors.onWarning,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warning)
            .padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

private fun ChatConnection.labelResId(): Int = when (this) {
    ChatConnection.Connecting -> R.string.chat_connection_connecting
    ChatConnection.Connected -> R.string.chat_connection_connected
    ChatConnection.Reconnecting -> R.string.chat_connection_reconnecting
    ChatConnection.Disconnected -> R.string.chat_connection_disconnected
    ChatConnection.PeerOffline -> R.string.chat_connection_peer_offline
    ChatConnection.Rejected -> R.string.chat_connection_rejected
    ChatConnection.NoResponse -> R.string.chat_connection_no_response
    ChatConnection.SignalingUnavailable -> R.string.chat_connection_signaling_unavailable
    ChatConnection.Failed -> R.string.chat_connection_failed
}

private fun ChatSendError.messageResId(): Int = when (this) {
    ChatSendError.Generic -> R.string.chat_error_send_failed
    ChatSendError.NoPublicKey -> R.string.chat_error_no_public_key
    ChatSendError.PeerOffline -> R.string.chat_error_peer_offline
    ChatSendError.Rejected -> R.string.chat_error_rejected
    ChatSendError.NoResponse -> R.string.chat_error_no_response
    ChatSendError.SignalingUnavailable -> R.string.chat_error_signaling_unavailable
    ChatSendError.ConnectionFailed -> R.string.chat_error_connection_failed
    ChatSendError.Disconnected -> R.string.chat_error_disconnected
}

@Composable
private fun MessageBubble(message: ChatMessageUiModel, onResendClick: (String) -> Unit) {
    val colors = LocalChatColors.current
    val failed = message.isSent && message.sendStatus == ChatSendStatus.Failed
    val resendLabel = stringResource(R.string.chat_resend_action)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 同じ人が続く間は詰め、しっぽのあとは広めに空ける。
            .padding(bottom = if (message.showTail) 20.dp else 6.dp)
            .then(
                if (failed) Modifier.clickable(onClickLabel = resendLabel) { onResendClick(message.id) } else Modifier,
            ),
        horizontalAlignment = if (message.isSent) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = message.body,
            color = if (message.isSent) colors.onSentBubble else colors.onReceivedBubble,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (message.isSent) colors.sentBubble else colors.receivedBubble,
                    shape = BubbleShape(isSent = message.isSent, showTail = message.showTail),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        // 送信状態は、吹き出しの下に小さく添える（しっぽの下に回り込まない余白を取る）。
        if (message.showSendStatus) {
            val (labelRes, color) = when (message.sendStatus) {
                ChatSendStatus.Sending -> R.string.chat_send_status_sending to colors.sendStatus
                ChatSendStatus.Failed -> R.string.chat_send_status_failed to colors.sendFailed
                ChatSendStatus.Sent -> R.string.chat_send_status_sent to colors.sendStatus
                ChatSendStatus.Read -> R.string.chat_send_status_read to colors.readStatus
            }
            Text(
                text = stringResource(labelRes),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = if (message.showTail) 10.dp else 2.dp, end = 4.dp),
            )
        }
    }
}

/**
 * 角丸 10dp の吹き出し。[showTail] なら、下に出っ張る小さな三角（受信は左下、送信は右下）を足す。
 * 三角は吹き出しの内側に少し食い込ませ、角丸の矩形との和集合（[Path.combine]）で 1 つの輪郭にする。
 * 2 つの図形を 1 つの Path に重ねて足すと、重なった部分が塗りの規則で抜けて、薄い三角形が見えてしまう。
 */
private class BubbleShape(private val isSent: Boolean, private val showTail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 10.dp.toPx() }
        val body = Path().apply {
            addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radius)))
        }
        if (!showTail) return Outline.Generic(body)

        val inset = with(density) { 6.dp.toPx() }
        val width = with(density) { 12.dp.toPx() }
        val rise = with(density) { 4.dp.toPx() }
        val drop = with(density) { 9.dp.toPx() }
        val top = size.height - rise
        val bottom = size.height + drop
        val tail = Path().apply {
            if (isSent) {
                // 右下: 外側の辺を下へ垂らし、先端から内側の上へ斜めに戻る。
                val edge = size.width - inset
                moveTo(edge, top)
                lineTo(edge, bottom)
                lineTo(edge - width, top)
            } else {
                moveTo(inset, top)
                lineTo(inset, bottom)
                lineTo(inset + width, top)
            }
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, tail))
    }
}

@Composable
private fun ChatInputBar(
    inputState: TextFieldState,
    canSend: Boolean,
    errorResId: Int?,
    onSendClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bar)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (errorResId != null) {
            Text(
                text = stringResource(errorResId),
                color = colors.onBar,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                state = inputState,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = colors.inputText, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                cursorBrush = SolidColor(colors.inputText),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorator = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.inputContainer, RoundedCornerShape(10.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (inputState.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_input_hint),
                                color = colors.inputText,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(onClick = onSendClick, enabled = canSend) {
                Icon(
                    painter = painterResource(R.drawable.baseline_send_24),
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (canSend) colors.onBar else colors.onBar.copy(alpha = 0.4f),
                )
            }
        }
    }
}

private val previewMessages = listOf(
    ChatMessageUiModel("1", "こんにちは", isSent = false, showTail = true),
    ChatMessageUiModel("2", "こんにちは", isSent = true, showTail = true),
    ChatMessageUiModel("3", "これはデモメッセージです\n改行することもできます。", isSent = false, showTail = false),
    ChatMessageUiModel("4", "同じ人物がメッセージを連続して送信したときは、下のメッセージのみ下付き吹き出しになります", isSent = false, showTail = true),
)

@Preview(showBackground = true, widthDp = 412, heightDp = 917)
@Composable
private fun ChatEmptyPreview() {
    P2PChatTheme { ChatContent(ChatUiState(contactName = "連絡先"), {}, rememberTextFieldState(), {}) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 917)
@Composable
private fun ChatNormalPreview() {
    P2PChatTheme {
        ChatContent(
            ChatUiState(contactName = "連絡先", messages = previewMessages.take(2), canSend = true),
            {}, rememberTextFieldState("返信を書いています"), {},
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 917)
@Composable
private fun ChatConsecutivePreview() {
    P2PChatTheme { ChatContent(ChatUiState(contactName = "連絡先", messages = previewMessages), {}, rememberTextFieldState(), {}) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 917)
@Composable
private fun ChatDarkPreview() {
    P2PChatTheme(darkTheme = true) {
        ChatContent(
            ChatUiState(
                contactName = "連絡先",
                messages = previewMessages + listOf(
                    ChatMessageUiModel("5", "送信中のメッセージ", isSent = true, showTail = false, sendStatus = ChatSendStatus.Sending),
                    ChatMessageUiModel("6", "送れなかったメッセージ", isSent = true, showTail = false, sendStatus = ChatSendStatus.Failed),
                    ChatMessageUiModel("7", "読まれたメッセージ", isSent = true, showTail = true, sendStatus = ChatSendStatus.Read),
                ),
                sendFailed = true,
                decryptWarning = true,
            ),
            {}, rememberTextFieldState(), {},
        )
    }
}
