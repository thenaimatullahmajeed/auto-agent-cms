package com.autoagent.cms.llm

import com.autoagent.cms.data.AgentRepository
import com.autoagent.cms.data.ProviderKeyEntity
import com.autoagent.cms.security.ApiKeyCipher
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Manages provider API keys: encrypted storage in Room and runtime KeyPool loading.
 */
class KeyManager(private val repository: AgentRepository) {
    private val cipher = ApiKeyCipher()

    fun observeKeys(): Flow<List<ProviderKeyEntity>> = repository.observeProviderKeys()

    suspend fun addKey(provider: LlmProvider, nickname: String, secret: String): ProviderKeyEntity {
        require(secret.isNotBlank()) { "Key khaali hai" }
        val entity = ProviderKeyEntity(
            id = UUID.randomUUID().toString(),
            provider = provider.name,
            nickname = nickname.ifBlank { provider.name.lowercase() },
            encryptedSecret = cipher.encrypt(secret)
        )
        repository.upsertProviderKey(entity)
        return entity
    }

    suspend fun deleteKey(id: String) = repository.deleteProviderKey(id)

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
