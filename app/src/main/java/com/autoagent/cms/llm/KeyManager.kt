package com.autoagent.cms.llm

import com.autoagent.cms.data.AgentRepository
import com.autoagent.cms.data.ProviderKeyEntity
import com.autoagent.cms.security.ApiKeyCipher
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Result of a live key check against the provider's API. */
sealed interface KeyCheck {
    data object Valid : KeyCheck
    data class Invalid(val reason: String) : KeyCheck
    data class NetworkError(val reason: String) : KeyCheck
}

/**
 * Manages provider API keys: encrypted storage in Room and runtime KeyPool loading.
 */
class KeyManager(private val repository: AgentRepository) {
    private val cipher = ApiKeyCipher()
    private val validateHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun observeKeys(): Flow<List<ProviderKeyEntity>> = repository.observeProviderKeys()

    /**
     * Lightweight live validation: hits the provider's models endpoint.
     * Runs on Dispatchers.IO with 10s timeouts; never throws.
     */
    suspend fun validateKey(provider: LlmProvider, secret: String): KeyCheck =
        withContext(Dispatchers.IO) {
            val request = when (provider) {
                LlmProvider.GEMINI -> Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$secret")
                    .get()
                    .build()
                LlmProvider.GROQ -> Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .header("Authorization", "Bearer $secret")
                    .get()
                    .build()
            }
            val response = try {
                validateHttp.newCall(request).execute()
            } catch (_: IOException) {
                return@withContext KeyCheck.NetworkError("Internet check karo — key verify nahi ho saki.")
            }
            response.use { res ->
                when {
                    res.isSuccessful -> KeyCheck.Valid
                    res.code == 400 || res.code == 401 || res.code == 403 ->
                        KeyCheck.Invalid(
                            if (provider == LlmProvider.GEMINI) "Key ghalat hai ya API enable nahi."
                            else "Key ghalat hai."
                        )
                    res.code == 429 ->
                        KeyCheck.NetworkError("Abhi limit lagi hai — thodi der baad dobara try karo.")
                    else -> KeyCheck.Invalid("Key kaam nahi kar rahi (HTTP ${res.code}).")
                }
            }
        }

    suspend fun addKey(
        provider: LlmProvider,
        nickname: String,
        secret: String,
        status: String = "VALID"
    ): ProviderKeyEntity {
        require(secret.isNotBlank()) { "Key khaali hai" }
        val entity = ProviderKeyEntity(
            id = UUID.randomUUID().toString(),
            provider = provider.name,
            nickname = nickname.ifBlank { provider.name.lowercase() },
            encryptedSecret = cipher.encrypt(secret),
            status = status
        )
        repository.upsertProviderKey(entity)
        return entity
    }

    suspend fun deleteKey(id: String) = repository.deleteProviderKey(id)

    /** Marks a saved key INVALID after the provider rejected it (e.g. 401 at runtime). */
    suspend fun markKeyInvalid(id: String) {
        try {
            repository.updateProviderKeyStatus(id, "INVALID")
        } catch (_: Exception) {
        }
    }

    suspend fun hasAnyKey(): Boolean = repository.getActiveProviderKeys().isNotEmpty()

    /**
     * Decrypts stored keys and fills one KeyPool per provider.
     * Keys that fail to decrypt are skipped without failing the task.
     */
    suspend fun loadPools(): Pair<KeyPool, KeyPool> {
        val geminiPool = KeyPool(ProviderQuota(requestsPerMinute = 10, tokensPerMinute = 30_000))
        val groqPool = KeyPool(ProviderQuota(requestsPerMinute = 20, tokensPerMinute = 12_000))
        for (key in repository.getActiveProviderKeys()) {
            val secret = try {
                cipher.decrypt(key.encryptedSecret)
            } catch (_: Exception) {
                continue
            }
            val entry = ApiKeyEntry(id = key.id, secret = secret, nickname = key.nickname)
            when (key.provider) {
                LlmProvider.GEMINI.name -> geminiPool.add(entry)
                LlmProvider.GROQ.name -> groqPool.add(entry)
                else -> Unit
            }
        }
        return geminiPool to groqPool
    }
}
