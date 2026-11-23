package com.p2pchat.app.data.message

import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.crypto.EcdhSharedSecretProvider
import com.p2pchat.app.data.crypto.AesGcmMessageCipher
import com.p2pchat.app.data.crypto.MessageCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class InMemoryMessageRepository : MessageRepository {
    val messages = MutableStateFlow<List<Message>>(emptyList())

    override fun observeMessages(deviceId: String): Flow<List<Message>> =
        messages.map { list -> list.filter { it.deviceId == deviceId } }

    override suspend fun upsertMessage(message: Message) {
        messages.value = messages.value.filter { it.id != message.id } + message
    }

    override suspend fun getMessage(id: String): Message? = messages.value.find { it.id == id }

    override suspend fun updateStatus(id: String, status: MessageStatus) {
        messages.value = messages.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    override fun observeLatestMessages(): Flow<List<Message>> = messages.map { list ->
        list.groupBy { it.deviceId }.values.map { group -> group.maxWith(compareBy({ it.sentAt }, { it.id })) }
    }

    override fun observeLatestReceivedMessages(): Flow<List<Message>> = messages.map { list ->
        list.filter { it.direction == MessageDirection.RECEIVED }
            .groupBy { it.deviceId }.values.map { group -> group.maxWith(compareBy({ it.sentAt }, { it.id })) }
    }

    override suspend fun failSendingBefore(olderThan: Long): Int {
        var count = 0
        messages.value = messages.value.map {
            if (it.status == MessageStatus.SENDING && it.direction == MessageDirection.SENT && it.sentAt < olderThan) {
                count++
                it.copy(status = MessageStatus.FAILED)
            } else it
        }
        return count
    }
}

class InMemoryContacts(vararg contacts: Contact) : ContactRepository {
    private val state = MutableStateFlow(contacts.toList())

    override fun observeContacts(): Flow<List<Contact>> = state
    override suspend fun getContact(deviceId: String): Contact? = state.value.find { it.deviceId == deviceId }
    override suspend fun saveContact(contact: Contact) { state.value = state.value + contact }
    override suspend fun updateName(deviceId: String, name: String) = Unit
    override suspend fun updateVerified(deviceId: String, isVerified: Boolean) = Unit
    override suspend fun deleteContact(deviceId: String) = Unit
}

/** 端末 1 台ぶんの鍵ペアと、実物の [AesGcmMessageCipher]（JVM で動く ECDH 実装）。 */
class TestDevice(val deviceId: String) {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val cipher: MessageCipher = AesGcmMessageCipher(EcdhSharedSecretProvider { keyPair.private })
    val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** この端末から見た、[peer] の連絡先。 */
    fun contactOf(peer: TestDevice) = Contact(peer.deviceId, peer.deviceId, peer.publicKeyBase64, false, 1L)
}

class InMemoryReadStateRepository : ReadStateRepository {
    val positions = MutableStateFlow<Map<String, Long>>(emptyMap())

    override fun observeReadPositions(): Flow<Map<String, Long>> = positions
    override suspend fun markRead(deviceId: String, readUpTo: Long) {
        if (readUpTo > (positions.value[deviceId] ?: Long.MIN_VALUE)) positions.value = positions.value + (deviceId to readUpTo)
    }

    /** `read` を伝え終えた位置（既読位置とは別に持つ）。 */
    val receiptSent = mutableMapOf<String, Long>()
    override suspend fun getReceiptSentUpTo(deviceId: String): Long? = receiptSent[deviceId]
    override suspend fun markReceiptSent(deviceId: String, sentUpTo: Long) {
        if (sentUpTo > (receiptSent[deviceId] ?: Long.MIN_VALUE)) receiptSent[deviceId] = sentUpTo
    }
}
