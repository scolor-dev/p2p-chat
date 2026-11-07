package com.p2pchat.app.ui.contacts

import com.p2pchat.app.MainDispatcherRule
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ViewModel のテストの見本。
 * Repository をテスト用の偽物（Fake）に差し替えられるのが、手動 DI の利点。
 */
class ContactsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeContactRepository : ContactRepository {
        val contacts = MutableStateFlow<List<Contact>>(emptyList())
        override fun observeContacts(): Flow<List<Contact>> = contacts
        override suspend fun getContact(deviceId: String): Contact? = contacts.value.find { it.deviceId == deviceId }
        override suspend fun saveContact(contact: Contact) { contacts.value = contacts.value + contact }
        override suspend fun updateName(deviceId: String, name: String) {
            contacts.value = contacts.value.map { if (it.deviceId == deviceId) it.copy(name = name) else it }
        }
        override suspend fun updateVerified(deviceId: String, isVerified: Boolean) {
            contacts.value = contacts.value.map { if (it.deviceId == deviceId) it.copy(isVerified = isVerified) else it }
        }
        override suspend fun deleteContact(deviceId: String) { contacts.value = contacts.value.filterNot { it.deviceId == deviceId } }
    }

    @Test
    fun 連絡先がない間は読み込み完了で空の一覧になる() = runTest {
        val viewModel = ContactsViewModel(FakeContactRepository())

        val state = viewModel.uiState.first { !it.isLoading }

        assertTrue(state.contacts.isEmpty())
    }

    @Test
    fun Repositoryの連絡先が画面の状態に反映される() = runTest {
        val repository = FakeContactRepository()
        val viewModel = ContactsViewModel(repository)
        val alice = Contact(deviceId = "a1", name = "Alice", publicKey = "", isVerified = false, createdAt = 1000L)

        repository.contacts.value = listOf(alice)
        val state = viewModel.uiState.first { it.contacts.isNotEmpty() }

        assertEquals(listOf(alice), state.contacts)
        assertEquals(false, state.isLoading)
    }
}
