package com.autoagent.cms.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TaskStatus {
    QUEUED, RUNNING, PAUSED, WAITING_FOR_CONFIRMATION, COMPLETED, FAILED, CANCELLED
}

@Entity(
    tableName = "agent_tasks",
    indices = [Index(value = ["status"]), Index(value = ["createdAt"])]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val source: String,
    val sourceMessageId: String?,
    val title: String,
    val goal: String,
    val targetPackage: String?,
    val parametersJson: String = "{}",
    val status: TaskStatus = TaskStatus.QUEUED,
    val stepCount: Int = 0,
    val lastObservedScreenHash: String? = null,
    val lastActionSummary: String? = null,
    val lastError: String? = null,
    val requiresConfirmation: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "task_events",
    indices = [Index(value = ["taskId", "createdAt"])]
)
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val eventType: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "processed_messages",
    indices = [Index(value = ["source", "messageKey"], unique = true)]
)
data class ProcessedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val messageKey: String,
    val processedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "task_recipes",
    indices = [Index(value = ["targetPackage", "intentKey"])]
)
data class RecipeEntity(
    @PrimaryKey val id: String,
    val targetPackage: String,
    val intentKey: String,
    val parameterSchemaJson: String = "{}",
    val successfulRuns: Int = 0,
    val failedRuns: Int = 0,
    val lastUsedAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "provider_keys",
    indices = [Index(value = ["provider", "nickname"])]
)
data class ProviderKeyEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val nickname: String,
    val encryptedSecret: String,
    val status: String = "ACTIVE",
    val cooldownUntil: Long = 0L,
    val requestCount: Long = 0L,
    val estimatedTokens: Long = 0L,
    val lastUsedAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "provider_usage",
    indices = [Index(value = ["provider", "windowStartedAt"])]
)
data class ProviderUsageEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val model: String,
    val windowStartedAt: Long,
    val requestCount: Int = 0,
    val estimatedTokens: Long = 0,
    val lastErrorCode: Int? = null
)
