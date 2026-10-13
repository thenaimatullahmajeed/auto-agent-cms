package com.autoagent.cms.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
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
        @Volatile
        var connected: Boolean = false
            private set
        private val _notifications = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 64)
        val notifications: SharedFlow<IncomingNotification> = _notifications
    }
}
