package com.p2pchat.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.PublicKeyFingerprint
import com.p2pchat.app.data.message.MessageDirection
import com.p2pchat.app.data.message.MessageRepository
import com.p2pchat.app.data.message.MessageStatus
import com.p2pchat.app.data.message.ReadStateRepository
import com.p2pchat.app.data.message.unreadDeviceIds
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
    readStateRepository: ReadStateRepository,
) : ViewModel() {

    val uiState: StateFlow<ContactsUiState> =
        combine(
            repository.observeContacts(),
            messageRepository.observeLatestMessages(),
            // 受信メッセージのうち既読位置より新しいものがある相手（自分が送っただけの相手は含まれない）
            combine(
                messageRepository.observeLatestReceivedMessages(),
                readStateRepository.observeReadPositions(),
                ::unreadDeviceIds,
            ),
        ) { contacts, latestMessages, unread ->
            val latestByDevice = latestMessages.associateBy { it.deviceId }
            ContactsUiState(
                // 最新メッセージの新しい順。メッセージのない相手は、名前順で後ろに置く。
                contacts = contacts.sortedWith(
                    compareByDescending<Contact> { latestByDevice[it.deviceId]?.sentAt ?: Long.MIN_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                ).map { contact ->
                    val latest = latestByDevice[contact.deviceId]
                    ContactListItemUiModel(
                        deviceId = contact.deviceId,
                        name = contact.name,
                        fingerprint = PublicKeyFingerprint.fromBase64(contact.publicKey),
                        isVerified = contact.isVerified,
                        // その相手との最新のメッセージ（送受信どちらも）の本文。まだ無ければ空。
                        latestMessage = latest?.body.orEmpty(),

                        isUnread = contact.deviceId in unread,

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
                    readStateRepository = app.container.readStateRepository,
                )
            }
        }
    }
}
