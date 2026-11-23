package com.p2pchat.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.PublicKeyFingerprint
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageRepository
import com.p2pchat.app.data.message.MessageStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 連絡先一覧のViewModel。
 */
class ContactsViewModel(
    repository: ContactRepository,
    messageRepository: MessageRepository,
) : ViewModel() {

    val uiState: StateFlow<ContactsUiState> =
        combine(
            repository.observeContacts(),
            messageRepository.observeLatestMessages(),
        ) { contacts, latestMessages ->
            val latestByDevice = latestMessages.associateBy { it.deviceId }
            ContactsUiState(
                contacts = contacts.map { contact ->
                    val latest = latestByDevice[contact.deviceId]
                    ContactListItemUiModel(
                        deviceId = contact.deviceId,
                        name = contact.name,
                        fingerprint = PublicKeyFingerprint.fromBase64(contact.publicKey),
                        isVerified = contact.isVerified,
                        // その相手との最新のメッセージ（送受信どちらも）の本文。まだ無ければ空。
                        latestMessage = latest?.body.orEmpty(),

                        // TODO: 実際の未確認状態へ差し替える（既読の管理は別の Issue）
                        isUnread = false,

                        // 最新のメッセージが、自分が送ったもので送信失敗のとき
                        sendError = latest != null &&
                            latest.direction == MessageDirection.SENT &&
                            latest.status == MessageStatus.FAILED,
                    )
                },
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(),
        )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp

                ContactsViewModel(
                    repository = app.container.contactRepository,
                    messageRepository = app.container.messageRepository,
                )
            }
        }
    }
}
