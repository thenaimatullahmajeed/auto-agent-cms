package com.autoagent.cms.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class IncomingNotification(
    val packageName: String,
    val notificationKey: String,
    val title: String?,
    val text: String?,
    val receivedAt: Long
)

class AgentNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val packageName = sbn.packageName
        if (packageName != WHATSAPP && packageName != WHATSAPP_BUSINESS) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        _notifications.tryEmit(
            IncomingNotification(
                packageName = packageName,
                notificationKey = sbn.key,
                title = title,
                text = text,
                receivedAt = sbn.postTime
            )
        )
    }

    companion object {
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        private val _notifications = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 64)
        val notifications: SharedFlow<IncomingNotification> = _notifications
    }
}
