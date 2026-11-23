package com.p2pchat.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.message.ChatMessageSender
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** チャット画面の ViewModel。連絡先名とメッセージ履歴を購読し、入力と送信の可否を扱う。 */
class ChatViewModel(
    private val deviceId: String,
    contactRepository: ContactRepository,
    messageRepository: MessageRepository,
    private val sender: ChatMessageSender,
) : ViewModel() {

    private data class InputState(
        val text: String = "",
        val isSending: Boolean = false,
        val sendFailed: Boolean = false,
    )

    private val inputState = MutableStateFlow(InputState())

    val uiState: StateFlow<ChatUiState> =
        combine(
            contactRepository.observeContacts().map { contacts -> contacts.firstOrNull { it.deviceId == deviceId }?.name },
            messageRepository.observeMessages(deviceId),
            inputState,
        ) { name, messages, input ->
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
                    )
                },
                input = input.text,
                isInputTooLong = tooLong,
                canSend = input.text.isNotBlank() && !tooLong && !input.isSending,
                isSending = input.isSending,
                sendFailed = input.sendFailed,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState(),
        )

    fun onInputChange(text: String) {
        inputState.update { it.copy(text = text, sendFailed = false) }
    }

    fun onSendClick() {
        val current = inputState.value
        val body = current.text
        if (current.isSending || body.isBlank() || isTooLong(body)) return
        inputState.update { it.copy(isSending = true, sendFailed = false) }
        viewModelScope.launch {
            val result = sender.send(deviceId, body)
            inputState.update { state ->
                if (result.isSuccess) {
                    // 送っている間に打ち足していたら、その文字は消さない。
                    state.copy(text = if (state.text == body) "" else state.text, isSending = false)
                } else {
                    state.copy(isSending = false, sendFailed = true)
                }
            }
        }
    }

    private fun isTooLong(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES

    companion object {
        /** 本文の上限（UTF-8 のバイト数）。設計書「本文の長さ」。 */
        const val MAX_BODY_BYTES = 4096

        fun factory(deviceId: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                ChatViewModel(
                    deviceId = deviceId,
                    contactRepository = app.container.contactRepository,
                    messageRepository = app.container.messageRepository,
                    sender = app.container.chatMessageSender,
                )
            }
        }
    }
}
