package com.autoagent.cms.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.autoagent.cms.service.AgentNotificationListener

data class WhatsAppMessageCandidate(
    val packageName: String,
    val contactLabel: String?,
    val text: String,
    val sourceKey: String?,
    val receivedAt: Long,
    val identityVerified: Boolean
)

class WhatsAppController(
    private val context: Context,
    private val allowedContact: String
) {
    fun launchWhatsApp(packageName: String): Boolean {
        if (packageName !in setOf(
                AgentNotificationListener.WHATSAPP,
                AgentNotificationListener.WHATSAPP_BUSINESS
            )
        ) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openChatUsingClickToChat(phoneDigits: String, packageName: String): Boolean {
        if (packageName !in setOf(
                AgentNotificationListener.WHATSAPP,
                AgentNotificationListener.WHATSAPP_BUSINESS
            )
        ) return false
        val digits = phoneDigits.filter(Char::isDigit)
        if (digits.length !in 8..15) return false
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$digits")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(packageName)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun notificationCandidate(
        packageName: String,
        notificationKey: String,
        title: String?,
        text: String?,
        receivedAt: Long
    ): WhatsAppMessageCandidate? {
        if (packageName !in setOf(
                AgentNotificationListener.WHATSAPP,
                AgentNotificationListener.WHATSAPP_BUSINESS
            )
        ) return null
        val content = text?.trim().orEmpty()
        if (content.isBlank()) return null
        val expected = normalize(allowedContact)
        val actual = normalize(title.orEmpty())
        val identityVerified = expected.isNotBlank() && (
            actual == expected ||
                actual.startsWith("$expected ") ||
                actual.startsWith("$expected:")
            )
        return WhatsAppMessageCandidate(
            packageName = packageName,
            contactLabel = title,
            text = content,
            sourceKey = notificationKey,
            receivedAt = receivedAt,
            identityVerified = identityVerified
        )
    }

    fun inspectChatElements(screen: ObservedScreen): List<UiElement> {
        if (screen.packageName !in setOf(
                AgentNotificationListener.WHATSAPP,
                AgentNotificationListener.WHATSAPP_BUSINESS
            )
        ) return emptyList()
        return screen.elements.filter { element ->
            !element.password &&
                (!element.text.isNullOrBlank() || !element.description.isNullOrBlank())
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
        .replace(Regex("\\s+"), " ")
}
