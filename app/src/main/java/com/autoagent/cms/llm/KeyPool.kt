package com.autoagent.cms.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

data class ApiKeyEntry(
    val id: String,
    val secret: String,
    val nickname: String,
    var active: Boolean = true,
    var cooldownUntil: Long = 0L,
    var requests: Int = 0,
    var estimatedTokens: Int = 0,
    var windowStartedAt: Long = System.currentTimeMillis(),
    var lastUsedAt: Long = 0L
)

data class KeyLease(val keyId: String, val secret: String)

class KeyPool(
    private val quota: ProviderQuota,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, ApiKeyEntry>()

    suspend fun add(entry: ApiKeyEntry) = mutex.withLock {
        require(entry.id.isNotBlank())
        require(entry.secret.isNotBlank())
        entries[entry.id] = entry
    }

    suspend fun remove(id: String) = mutex.withLock {
        entries.remove(id)
    }

    suspend fun setActive(id: String, active: Boolean) = mutex.withLock {
        entries[id]?.active = active
    }

    suspend fun acquire(estimatedRequestTokens: Int = 400): KeyLease? = mutex.withLock {
        val now = clock()
        val windowMs = 60_000L
        entries.values.forEach { entry ->
            if (now - entry.windowStartedAt >= windowMs) {
                entry.windowStartedAt = now
                entry.requests = 0
                entry.estimatedTokens = 0
            }
        }
        val candidate = entries.values.asSequence()
            .filter { it.active && it.cooldownUntil <= now }
            .filter { it.requests < quota.requestsPerMinute }
            .filter { it.estimatedTokens + estimatedRequestTokens <= quota.tokensPerMinute }
            .minWithOrNull(
                compareBy<ApiKeyEntry> { it.requests }
                    .thenBy { it.estimatedTokens }
                    .thenBy { it.lastUsedAt }
            ) ?: return@withLock null
        candidate.requests++
        candidate.estimatedTokens += max(estimatedRequestTokens, 1)
        candidate.lastUsedAt = now
        KeyLease(candidate.id, candidate.secret)
    }

    suspend fun recordActualUsage(keyId: String, inputTokens: Int?, outputTokens: Int?) =
        mutex.withLock {
            val entry = entries[keyId] ?: return@withLock
            val actual = max(0, inputTokens ?: 0) + max(0, outputTokens ?: 0)
            entry.estimatedTokens = max(entry.estimatedTokens, actual)
        }

    suspend fun coolDown(keyId: String, retryAfterMillis: Long?) = mutex.withLock {
        val entry = entries[keyId] ?: return@withLock
        val delay = (retryAfterMillis ?: 30_000L).coerceIn(1_000L, 3_600_000L)
        entry.cooldownUntil = clock() + delay
    }

    suspend fun markDead(keyId: String) = mutex.withLock {
        entries[keyId]?.active = false
    }

    suspend fun resetKey(keyId: String) = mutex.withLock {
        entries[keyId]?.apply {
            active = true
            cooldownUntil = 0L
            requests = 0
            estimatedTokens = 0
            windowStartedAt = clock()
        }
    }

    suspend fun nextCooldownMillis(): Long? = mutex.withLock {
        val now = clock()
        entries.values.filter { it.active && it.cooldownUntil > now }
            .minOfOrNull { it.cooldownUntil - now }
    }

    suspend fun hasActiveKeys(): Boolean = mutex.withLock {
        entries.values.any { it.active }
    }

    suspend fun availableCount(): Int = mutex.withLock {
        val now = clock()
        entries.values.count { it.active && it.cooldownUntil <= now }
    }
}
