package com.p2pchat.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.message.ChatMessageSender
import com.p2pchat.app.data.message.ChatSendException
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageRepository
import com.p2pchat.app.data.message.MessageStatus
import com.p2pchat.app.data.message.NoOpReadStateRepository
import com.p2pchat.app.data.message.ReadStateRepository
import com.p2pchat.app.data.peer.ConnectOncePeerReconnector
import com.p2pchat.app.data.peer.PeerConnector
import com.p2pchat.app.data.peer.PeerFailure
import com.p2pchat.app.data.peer.PeerReconnector
import com.p2pchat.app.data.peer.PeerState
import com.p2pchat.app.notification.ActiveChatTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * チャット画面の ViewModel。連絡先名とメッセージ履歴を購読し、入力と送信の可否を扱う。
 * 開いたときに相手へ接続し（open 済みならそのまま使う）、接続状態を [ChatUiState.connection] に出す。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val deviceId: String,
    contactRepository: ContactRepository,
    messageRepository: MessageRepository,
    private val sender: ChatMessageSender,
    private val peerConnector: PeerConnector,
    /** 復号できずに捨てた `msg` の送り主（端末 ID）。自分の相手の分だけ警告に使う。 */
    decryptFailures: Flow<String> = emptyFlow(),
    /** 画面を開いている間、相手との接続を保つ（切れたら自動でつなぎ直す）。 */
    private val reconnector: PeerReconnector = ConnectOncePeerReconnector(peerConnector),
    /** この相手のチャットを開いている間は、その相手のメッセージを通知しないために、開いた・閉じたを伝える。 */
    private val activeChatTracker: ActiveChatTracker = ActiveChatTracker.None,
    /** 画面を開いている間、この相手のメッセージを既読にする。 */
    readStateRepository: ReadStateRepository = NoOpReadStateRepository,
) : ViewModel() {

    /** チャット画面が見えている（前面にある）間だけ true。閉じたあと・背面に届いたものは未読のままにする。 */
    private val visible = MutableStateFlow(false)

    private data class InputState(
        val text: String = "",
        val isSending: Boolean = false,
        val sendFailed: Boolean = false,
        val sendError: ChatSendError = ChatSendError.Generic,
    )

    private val inputState = MutableStateFlow(InputState())

    /** 再送の処理中（接続の待ちを含む）のメッセージ。DB が SENDING になるまでの間も「送信中」に見せる。 */
    private val resendingIds = MutableStateFlow<Set<String>>(emptySet())

    private val decryptWarning = MutableStateFlow(false)
    private var decryptWarningJob: Job? = null

    /** 相手の最新の session の状態。session がまだ無い間は null（接続中として扱う）。 */
    private val connection = combine(
        peerConnector.sessions
            .map { it[deviceId] }
            .distinctUntilChanged()
            .flatMapLatest { session -> session?.state ?: flowOf(null) },
        reconnector.reconnecting,
    ) { state, reconnecting ->
        // 切れたあとにつなぎ直している間は「再接続中」。つながれば「接続済み」になる
        if (state != PeerState.Open && deviceId in reconnecting) ChatConnection.Reconnecting else state.toConnection()
    }

    init {
        activeChatTracker.opened(deviceId)
    }

    override fun onCleared() {
        activeChatTracker.closed(deviceId)
    }

    init {
        // 見えている間は、届いたメッセージを（開いた時点の分も含めて）既読にする。
        viewModelScope.launch {
            combine(visible, messageRepository.observeMessages(deviceId)) { v, messages ->
                if (v) messages.maxOfOrNull { it.sentAt } else null
            }
                .filterNotNull()
                .collect { readStateRepository.markRead(deviceId, it) }
        }
        // 画面を開いたら接続し、閉じる（ViewModel が破棄される）まで、切れたらつなぎ直す。
        viewModelScope.launch { reconnector.keepConnected(deviceId) }
        viewModelScope.launch {
            decryptFailures.filter { it == deviceId }.collect {
                decryptWarning.value = true
                decryptWarningJob?.cancel()
                decryptWarningJob = launch {
                    delay(DECRYPT_WARNING_MILLIS)
                    decryptWarning.value = false
                }
            }
        }
    }

    val uiState: StateFlow<ChatUiState> =
        combine(
            contactRepository.observeContacts().map { contacts -> contacts.firstOrNull { it.deviceId == deviceId }?.name },
            messageRepository.observeMessages(deviceId),
            inputState,
            connection,
            combine(resendingIds, decryptWarning) { ids, warning -> ids to warning },
        ) { name, messages, input, connection, (resending, warning) ->
            val tails = tailFlags(messages.map { it.direction })
            val tooLong = isTooLong(input.text)
            ChatUiState(
                contactName = name,
                messages = messages.mapIndexed { index, message ->
                    ChatMessageUiModel(
                        id = message.id,
                        body = message.body,
                        isSent = message.direction == MessageDirection.SENT,
                        showTail = tails[index],
                        sendStatus = when {
                            message.id in resending -> ChatSendStatus.Sending
                            message.status == MessageStatus.SENDING -> ChatSendStatus.Sending
                            message.status == MessageStatus.FAILED -> ChatSendStatus.Failed
                            else -> ChatSendStatus.Sent
                        },
                    )
                },
                input = input.text,
                isInputTooLong = tooLong,
                canSend = input.text.isNotBlank() && !tooLong && !input.isSending,
                isSending = input.isSending,
                sendFailed = input.sendFailed,
                sendError = input.sendError,
                connection = connection,
                decryptWarning = warning,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState(),
        )

    /** 画面が見えているか（前面か）が変わったとき。見えている間のメッセージを既読にする。 */
    fun onVisibleChanged(isVisible: Boolean) {
        visible.value = isVisible
    }

    fun onInputChange(text: String) {
        inputState.update { it.copy(text = text, sendFailed = false) }
    }

    fun onSendClick() {
        val current = inputState.value
        val body = current.text
        if (current.isSending || body.isBlank() || isTooLong(body)) return
        inputState.update { it.copy(isSending = true, sendFailed = false, sendError = ChatSendError.Generic) }
        viewModelScope.launch {
            val result = sender.send(deviceId, body)
            inputState.update { state ->
                if (result.isSuccess) {
                    // 送っている間に打ち足していたら、その文字は消さない。
                    state.copy(text = if (state.text == body) "" else state.text, isSending = false)
                } else {
                    val error = result.exceptionOrNull()
                    // 接続や送信の失敗では、メッセージは「送信失敗」として履歴に残っている（タップで再送できる）ので入力欄は空にする。
                    // 入力の検証エラー（空・長すぎ・公開鍵なし）は保存されないので、入力を残す。
                    val kept = error is ChatSendException && error.reason.keepsMessage()
                    state.copy(
                        text = if (kept && state.text == body) "" else state.text,
                        isSending = false,
                        sendFailed = true,
                        sendError = error.toSendError(),
                    )
                }
            }
        }
    }

    /** 送信失敗のメッセージをタップしたとき。同じ id で再送する（再送中・失敗でないものは無視）。 */
    fun onResendClick(messageId: String) {
        if (messageId in resendingIds.value) return
        val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (!message.isSent || message.sendStatus != ChatSendStatus.Failed) return
        resendingIds.update { it + messageId }
        inputState.update { it.copy(sendFailed = false) }
        viewModelScope.launch {
            val result = try {
                sender.resend(deviceId, messageId)
            } finally {
                resendingIds.update { it - messageId }
            }
            if (result.isFailure) {
                inputState.update { it.copy(sendFailed = true, sendError = result.exceptionOrNull().toSendError()) }
            }
        }
    }

    private fun PeerState?.toConnection(): ChatConnection = when (this) {
        null, PeerState.Connecting -> ChatConnection.Connecting
        PeerState.Open -> ChatConnection.Connected
        PeerState.Disconnected -> ChatConnection.Disconnected
        is PeerState.Failed -> when (reason) {
            PeerFailure.PeerOffline -> ChatConnection.PeerOffline
            PeerFailure.Rejected -> ChatConnection.Rejected
            PeerFailure.Timeout -> ChatConnection.NoResponse
            PeerFailure.SignalingUnavailable -> ChatConnection.SignalingUnavailable
            PeerFailure.ConnectionFailed -> ChatConnection.Failed
        }
    }

    private fun ChatSendException.Reason.keepsMessage(): Boolean = when (this) {
        ChatSendException.Reason.EMPTY_BODY,
        ChatSendException.Reason.TOO_LONG,
        ChatSendException.Reason.NO_PUBLIC_KEY,
        -> false
        else -> true
    }

    private fun Throwable?.toSendError(): ChatSendError {
        val e = this as? ChatSendException ?: return ChatSendError.Generic
        return when (e.reason) {
            ChatSendException.Reason.NO_PUBLIC_KEY -> ChatSendError.NoPublicKey
            ChatSendException.Reason.DISCONNECTED -> ChatSendError.Disconnected
            ChatSendException.Reason.PEER -> when (e.peerFailure) {
                PeerFailure.PeerOffline -> ChatSendError.PeerOffline
                PeerFailure.Rejected -> ChatSendError.Rejected
                PeerFailure.Timeout -> ChatSendError.NoResponse
                PeerFailure.SignalingUnavailable -> ChatSendError.SignalingUnavailable
                PeerFailure.ConnectionFailed -> ChatSendError.ConnectionFailed
                null -> ChatSendError.Generic
            }
            else -> ChatSendError.Generic
        }
    }

    private fun isTooLong(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES

    companion object {
        /** 本文の上限（UTF-8 のバイト数）。設計書「本文の長さ」。 */
        const val MAX_BODY_BYTES = 4096

        /** 復号失敗の警告を出しておく時間。 */
        const val DECRYPT_WARNING_MILLIS = 5_000L

        fun factory(deviceId: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                ChatViewModel(
                    deviceId = deviceId,
                    contactRepository = app.container.contactRepository,
                    messageRepository = app.container.messageRepository,
                    sender = app.container.chatMessageSender,
                    peerConnector = app.container.peerConnector,
                    decryptFailures = app.container.incomingMessageHandler.decryptFailures,
                    reconnector = app.container.peerReconnector,
                    activeChatTracker = app.container.activeChatTracker,
                    readStateRepository = app.container.readStateRepository,
                )
            }
        }
    }
}
