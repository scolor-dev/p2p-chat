package com.p2pchat.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.p2pchat.app.P2PChatApp
import com.p2pchat.app.data.contact.ContactRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 連絡先一覧のViewModel。
 */
class ContactsViewModel(
    repository: ContactRepository,
) : ViewModel() {

    val uiState: StateFlow<ContactsUiState> =
        repository.observeContacts()
            .map { contacts ->
                ContactsUiState(
                    contacts = contacts.map { contact ->
                        ContactListItemUiModel(
                            deviceId = contact.deviceId,
                            name = contact.name,

                            // TODO: 実際の最新メッセージへ差し替える
                            latestMessage = "ここにメッセージ内容が入ります",

                            // TODO: 実際の未確認状態へ差し替える
                            isUnread = false,

                            // TODO: 実際の送信結果へ差し替える
                            sendError = false,
                        )
                    },
                    isLoading = false,
                )
            }
            .stateIn(
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
                )
            }
        }
    }
}
