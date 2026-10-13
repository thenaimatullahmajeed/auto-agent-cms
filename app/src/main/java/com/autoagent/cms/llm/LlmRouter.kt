package com.autoagent.cms.llm

import kotlinx.coroutines.delay

class LlmRouter(
    private val gemini: LlmClient,
    private val groq: LlmClient,
    private val geminiKeys: KeyPool,
    private val groqKeys: KeyPool,
    private val geminiModel: String,
    private val groqTextModel: String,
    private val groqVisionModel: String = "meta-llama/llama-4-scout-17b-16e-instruct",
    private val maxTransientRetries: Int = 2,
    private val onKeyUnauthorized: (suspend (keyId: String) -> Unit)? = null
) {
    suspend fun generate(request: LlmRequest): LlmResponse {
        val providers = providerOrder(request.purpose)
        val triedProviders = LinkedHashSet<LlmProvider>()
        var lastFailure: Throwable? = null
        for (provider in providers) {
            triedProviders.add(provider)
            val client = clientFor(provider)
            val pool = poolFor(provider)
            var transientRetries = 0
            var model = primaryModel(provider, request.purpose)
            var fallbackAttempted = false
            while (transientRetries <= maxTransientRetries) {
                val lease = pool.acquire(
                    estimatedRequestTokens = estimateTokens(request)
                )
                if (lease == null) {
                    // Diagnose WHY no key was eligible (quota gate used to silently
                    // reject valid keys when the estimated tokens exceeded TPM).
                    lastFailure = LlmFailure.NoAvailableKey(
                        "No eligible $provider key: active=${pool.hasActiveKeys()}, " +
                            "availableNow=${pool.availableCount()}."
                    )
                    break
                }
                try {
                    val response = client.generate(
                        apiKey = lease.secret,
                        request = request.copy(model = model)
                    )
                    pool.recordActualUsage(lease.keyId, response.inputTokens, response.outputTokens)
                    return response
                } catch (e: LlmFailure.RateLimited) {
                    pool.coolDown(lease.keyId, e.retryAfterMillis)
                    lastFailure = e
                } catch (e: LlmFailure.Unauthorized) {
                    pool.markDead(lease.keyId)
                    try {
                        onKeyUnauthorized?.invoke(lease.keyId)
                    } catch (_: Exception) {
                    }
                    lastFailure = e
                } catch (e: LlmFailure.InvalidResponse) {
                    lastFailure = e
                    val fb = fallbackModel(provider, request.purpose)
                    if ((e.httpCode == 400 || e.httpCode == 404) && !fallbackAttempted && fb != model) {
                        fallbackAttempted = true
                        model = fb
                        continue
                    }
                    break
                } catch (e: LlmFailure.Server) {
                    lastFailure = e
                    transientRetries++
                    if (transientRetries <= maxTransientRetries) {
                        delay(500L * (1L shl (transientRetries - 1)))
                    }
                } catch (e: LlmFailure.Network) {
                    lastFailure = e
                    transientRetries++
                    if (transientRetries <= maxTransientRetries) {
                        delay(500L * (1L shl (transientRetries - 1)))
                    }
                } catch (e: LlmFailure) {
                    lastFailure = e
                    break
                }
            }
        }
        throw LlmFailure.NoAvailableKey(
            "Tried providers: ${triedProviders.joinToString(", ")}. " +
                "Last error: ${lastFailure?.message ?: "no eligible key"}"
        )
    }

    private fun providerOrder(purpose: RequestPurpose): List<LlmProvider> = when (purpose) {
        RequestPurpose.SPEECH_TO_TEXT -> listOf(LlmProvider.GROQ)
        else -> listOf(LlmProvider.GEMINI, LlmProvider.GROQ)
    }

    private fun clientFor(provider: LlmProvider): LlmClient = when (provider) {
        LlmProvider.GEMINI -> gemini
        LlmProvider.GROQ -> groq
    }

    private fun poolFor(provider: LlmProvider): KeyPool = when (provider) {
        LlmProvider.GEMINI -> geminiKeys
        LlmProvider.GROQ -> groqKeys
    }

    private fun primaryModel(provider: LlmProvider, purpose: RequestPurpose): String = when (provider) {
        LlmProvider.GEMINI -> geminiModel
        LlmProvider.GROQ -> if (purpose == RequestPurpose.SCREEN_ANALYSIS) groqVisionModel else groqTextModel
    }

    /** One-shot fallback model used only when the primary model is rejected with HTTP 400/404. */
    private fun fallbackModel(provider: LlmProvider, purpose: RequestPurpose): String = when (provider) {
        LlmProvider.GEMINI -> "gemini-2.5-flash-lite"
        LlmProvider.GROQ -> if (purpose == RequestPurpose.SCREEN_ANALYSIS) {
            "meta-llama/llama-4-maverick-17b-128e-instruct"
        } else {
            "llama-3.1-8b-instant"
        }
    }

    private fun estimateTokens(request: LlmRequest): Int {
        val chars = request.systemPrompt.length + request.messages.sumOf { it.content.length }
        return (chars / 3).coerceAtLeast(100)
    }
}
