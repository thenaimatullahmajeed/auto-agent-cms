package com.autoagent.cms.llm

import kotlinx.serialization.Serializable

enum class LlmProvider { GEMINI, GROQ }

enum class RequestPurpose {
    MESSAGE_UNDERSTANDING, SCREEN_DECISION, SCREEN_ANALYSIS, LONG_TEXT, SPEECH_TO_TEXT
}

@Serializable
data class LlmMessage(val role: String, val content: String)

data class LlmRequest(
    val purpose: RequestPurpose,
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val model: String,
    val maxTokens: Int = 400,
    val temperature: Double = 0.1,
    val jsonMode: Boolean = true
)

data class LlmResponse(
    val provider: LlmProvider,
    val model: String,
    val content: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)

data class ProviderQuota(
    val requestsPerMinute: Int,
    val tokensPerMinute: Int,
    val maxOutputTokens: Int = 600
) {
    init {
        require(requestsPerMinute > 0)
        require(tokensPerMinute > 0)
        require(maxOutputTokens in 1..600)
    }
}

data class ProviderConfig(
    val provider: LlmProvider,
    val baseUrl: String,
    val model: String,
    val quota: ProviderQuota,
    val enabled: Boolean = true
)

sealed class LlmFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val retryAfterMillis: Long?, message: String) : LlmFailure(message)
    class Unauthorized(message: String) : LlmFailure(message)
    class Server(message: String, cause: Throwable? = null) : LlmFailure(message, cause)
    class Network(message: String, cause: Throwable? = null) : LlmFailure(message, cause)
    class InvalidResponse(message: String) : LlmFailure(message)
    class NoAvailableKey(message: String) : LlmFailure(message)
    class Unsupported(message: String) : LlmFailure(message)
}

interface LlmClient {
    val provider: LlmProvider
    suspend fun generate(apiKey: String, request: LlmRequest): LlmResponse
}

/** Human-readable (Roman Urdu) message for every failure type. */
fun LlmFailure.userMessage(): String = when (this) {
    is LlmFailure.NoAvailableKey ->
        "Koi API key kaam nahi kar rahi. Keys check karo (ghalat/expired ho sakti hain)."
    is LlmFailure.Unauthorized -> "API key ghalat hai — nayi key add karo."
    is LlmFailure.RateLimited -> "Limit khatam ho gayi — thodi der baad dobara chalao."
    is LlmFailure.Network -> "Internet ka masla hai — connection check karo."
    is LlmFailure.Server -> "Server error — thodi der baad dobara try karo."
    is LlmFailure.InvalidResponse -> "Server se ghalat jawab aaya — dobara try karo."
    is LlmFailure.Unsupported -> "Ye request supported nahi hai."
}
