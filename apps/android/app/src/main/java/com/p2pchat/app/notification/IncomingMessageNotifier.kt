package com.p2pchat.app.notification

import com.p2pchat.app.data.contact.ContactRepository
import com.p2pchat.app.data.message.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 受信したメッセージを [IncomingNotificationPolicy] で判定して、[MessageNotifier] で通知する常駐の部品。
 * 本文は端末内で復号済みのもの。通知の外（ログなど）には出さない。
 */
class IncomingMessageNotifier(
    private val received: Flow<Message>,
    private val contactRepository: ContactRepository,
    private val appForeground: AppForegroundState,
    private val activeChat: ActiveChatTracker,
    private val notifier: MessageNotifier,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** 1 回だけ有効。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            received.collect { message ->
                try {
                    notifyIfNeeded(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 通知の失敗で受信の処理を止めない
                }
            }
        }
        // 前面でその相手のチャットを開いたら、その相手の通知を消す
        scope.launch {
            combine(appForeground.isForeground, activeChat.activeDeviceId) { foreground, active ->
                active.takeIf { foreground }
            }.collect { active -> if (active != null) notifier.cancel(active) }
        }
    }

    private suspend fun notifyIfNeeded(message: Message) {
        val contact = contactRepository.getContact(message.deviceId)
        val should = IncomingNotificationPolicy.shouldNotify(
            message = message,
            isContactKnown = contact != null,
            isAppForeground = appForeground.isForeground.value,
            activeChatDeviceId = activeChat.activeDeviceId.value,
        )
        if (should && contact != null) {
            notifier.show(message.deviceId, contact.name, message.body, message.sentAt)
        }
    }
}
