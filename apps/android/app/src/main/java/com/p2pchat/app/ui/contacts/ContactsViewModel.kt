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
 * 連絡先一覧の ViewModel。MVVM の見本。
 *
 * - 必要な部品（Repository）は、コンストラクターで受け取る
 * - 画面には [uiState] だけを公開する。画面は表示するだけで、判断はここで行う
 */
class ContactsViewModel(
    repository: ContactRepository,
) : ViewModel() {

    val uiState: StateFlow<ContactsUiState> = repository.observeContacts()
        .map { contacts -> ContactsUiState(contacts = contacts, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            // 画面が見えている間だけ Repository を購読する（画面の回転では 5 秒間つなぎっぱなしにする）
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(),
        )

    companion object {
        /** AppContainer から部品を取り出して ViewModel を作る。新しい ViewModel も同じ形で書く。 */
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as P2PChatApp
                ContactsViewModel(app.container.contactRepository)
            }
        }
    }
}
