package com.autoagent.cms.llm

import kotlinx.coroutines.delay

class LlmRouter(
    private val gemini: LlmClient,
    private val groq: LlmClient,
    private val geminiKeys: KeyPool,
    private val groqKeys: KeyPool,
    private val geminiModel: String,
    private val groqTextModel: String,
    private val groqVisionModel: String = "qwen/qwen3.8-27b",
    private val maxTransientRetries: Int = 2
) {
    suspend fun generate(request: LlmRequest): LlmResponse {
        val providers = providerOrder(request.purpose)
        var lastFailure: Throwable? = null
        for (provider in providers) {
            val client = clientFor(provider)
            val pool = poolFor(provider)
            var transientRetries = 0
            while (transientRetries <= maxTransientRetries) {
                val lease = pool.acquire(
                    estimatedRequestTokens = estimateTokens(request)
                ) ?: break
                try {
                    val model = when (provider) {
                        LlmProvider.GEMINI -> geminiModel
                        LlmProvider.GROQ -> if (request.purpose == RequestPurpose.SCREEN_ANALYSIS) {
                            groqVisionModel
                        } else {
                            groqTextModel
                        }
                    }
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
                    lastFailure = e
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
            "No provider/key could complete the request safely: " +
                (lastFailure?.message ?: "no eligible key")
        )
    }

    private fun providerOrder(purpose: RequestPurpose): List<LlmProvider> = when (purpose) {
        RequestPurpose.SCREEN_DECISION -> listOf(LlmProvider.GROQ, LlmProvider.GEMINI)
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

    private fun estimateTokens(request: LlmRequest): Int {
        val chars = request.systemPrompt.length + request.messages.sumOf { it.content.length }
        return (chars / 3).coerceAtLeast(100)
    }
}
