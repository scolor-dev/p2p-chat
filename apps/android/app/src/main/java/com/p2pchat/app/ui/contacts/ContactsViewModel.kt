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
import com.p2pchat.app.data.device.DeviceRepository
import com.p2pchat.app.data.signaling.SignalingClient
import com.p2pchat.app.data.signaling.SignalingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 連絡先一覧のViewModel。
 */
class ContactsViewModel(
    repository: ContactRepository,
    messageRepository: MessageRepository,
    readStateRepository: ReadStateRepository,
    private val deviceRepository: DeviceRepository,
    signalingClient: SignalingClient,
) : ViewModel() {

    private val showReRegisterDialog = MutableStateFlow(false)
    private var isReRegistering = false

    private val listState: Flow<ContactsUiState> =
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
        }

    val uiState: StateFlow<ContactsUiState> =
        combine(listState, signalingClient.state, showReRegisterDialog) { list, signaling, dialog ->
            list.copy(
                // 認証エラーのときだけバナーを出す（接続中・待機中などでは出さない）
                isUnauthorized = signaling is SignalingState.Unauthorized,
                showReRegisterDialog = dialog,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(),
        )

    /** バナーの「再登録」。すぐには消さず、確認ダイアログを出す。 */
    fun onReRegisterClick() {
        showReRegisterDialog.value = true
    }

    fun onReRegisterDismiss() {
        showReRegisterDialog.value = false
    }

    /**
     * 確認ダイアログで「再登録する」を押したとき。`deviceId` と token を消す。
     * 消すと [DeviceRepository.identity] が null になり、画面は登録画面へ戻る。連絡先とメッセージは消さない。
     */
    fun onReRegisterConfirm() {
        // ダイアログを出していないときは何もしない（確認を経ずに消さない）。二重に押されても 1 回だけ
        if (!showReRegisterDialog.value || isReRegistering) return
        isReRegistering = true
        showReRegisterDialog.value = false
        viewModelScope.launch {
            try {
                deviceRepository.clearIdentity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 消せなかったときは、もう一度押せるようにする（メッセージは出さない）
            } finally {
                isReRegistering = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp

                ContactsViewModel(
                    repository = app.container.contactRepository,
                    messageRepository = app.container.messageRepository,
                    readStateRepository = app.container.readStateRepository,
                    deviceRepository = app.container.deviceRepository,
                    signalingClient = app.container.signalingClient,
                )
            }
        }
    }
}
