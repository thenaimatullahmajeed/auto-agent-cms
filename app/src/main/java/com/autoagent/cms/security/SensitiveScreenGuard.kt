package com.autoagent.cms.security

data class ScreenField(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val isPassword: Boolean
)

data class ScreenSnapshot(
    val packageName: String?,
    val fields: List<ScreenField>,
    val capturedAt: Long = System.currentTimeMillis()
)

data class GuardResult(
    val blocked: Boolean,
    val reason: String
)

class SensitiveScreenGuard(
    private val additionalBlockedPackages: Set<String> = emptySet()
) {
    private val defaultBlockedPackages = setOf(
        "com.android.vending",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "com.paypal.android.p2pmobile",
        "com.google.android.gms"
    )

    private val sensitiveTerms = listOf(
        "password", "passcode", "one-time password", "verification code",
        "security code", "otp", "pin number", "credit card", "debit card",
        "bank account", "cvv", "cvc", "enter your pin", "confirm payment",
        "transfer money"
    )

    fun inspect(snapshot: ScreenSnapshot): GuardResult {
        val packageName = snapshot.packageName.orEmpty()
        if (packageName in defaultBlockedPackages || packageName in additionalBlockedPackages) {
            return GuardResult(blocked = true, reason = "Sensitive package blocked.")
        }
        if (snapshot.fields.any { it.isPassword }) {
            return GuardResult(blocked = true, reason = "Password field detected.")
        }
        val searchableText = snapshot.fields
            .flatMap { listOfNotNull(it.text, it.contentDescription) }
            .joinToString(" ")
            .lowercase()
        if (sensitiveTerms.any(searchableText::contains)) {
            return GuardResult(blocked = true, reason = "Potential sensitive information detected.")
        }
        return GuardResult(blocked = false, reason = "No configured sensitive-screen indicators detected.")
    }
}
