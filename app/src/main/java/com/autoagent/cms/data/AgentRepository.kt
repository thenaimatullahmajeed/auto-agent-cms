package com.autoagent.cms.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class AgentRepository(private val database: AgentDatabase) {
    private val tasks = database.taskDao()
    private val events = database.taskEventDao()
    private val messages = database.processedMessageDao()
    private val providerKeys = database.providerKeyDao()

    fun observeTasks(): Flow<List<TaskEntity>> = tasks.observeAll()

    fun observeTask(id: String): Flow<TaskEntity?> = tasks.observeById(id)

    fun observeProviderKeys(): Flow<List<ProviderKeyEntity>> = providerKeys.observeAll()

    suspend fun upsertProviderKey(key: ProviderKeyEntity) = providerKeys.upsert(key)

    suspend fun deleteProviderKey(id: String) = providerKeys.deleteById(id)

    suspend fun updateProviderKeyStatus(id: String, status: String) =
        providerKeys.updateStatus(id, status, 0L, System.currentTimeMillis())

    suspend fun getActiveProviderKeys(): List<ProviderKeyEntity> = providerKeys.getActive()

    fun observeRecentEvents(limit: Int = 200): Flow<List<TaskEventEntity>> =
        events.observeRecent(limit.coerceIn(1, 1000))

    fun observeTaskEvents(taskId: String): Flow<List<TaskEventEntity>> =
        events.observeForTask(taskId)

    suspend fun createTask(
        source: String,
        sourceMessageId: String?,
        title: String,
        goal: String,
        targetPackage: String?,
        parametersJson: String = "{}",
        requiresConfirmation: Boolean = false
    ): TaskEntity {
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            source = source,
            sourceMessageId = sourceMessageId,
            title = title,
            goal = goal,
            targetPackage = targetPackage,
            parametersJson = parametersJson,
            requiresConfirmation = requiresConfirmation,
            status = if (requiresConfirmation) TaskStatus.WAITING_FOR_CONFIRMATION else TaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now
        )
        tasks.insert(task)
        appendEvent(task.id, "TASK_CREATED", "Task added to queue.")
        return task
    }

    suspend fun claimIncomingMessage(source: String, messageKey: String): Boolean =
        database.withTransaction {
            messages.insertIfNew(
                ProcessedMessageEntity(source = source, messageKey = messageKey)
            ) != -1L
        }

    suspend fun getTask(id: String): TaskEntity? = tasks.getById(id)

    suspend fun getRecoverableTasks(): List<TaskEntity> = tasks.getRecoverable()

    suspend fun recoverAfterProcessDeath() {
        val now = System.currentTimeMillis()
        tasks.pauseInterrupted(
            reason = "Process stopped before task completion; screen must be revalidated.",
            now = now
        )
        appendEvent(
            taskId = "system",
            type = "RECOVERY",
            message = "Interrupted tasks paused. No UI actions were replayed."
        )
    }

    suspend fun setStatus(id: String, status: TaskStatus, message: String) {
        val current = tasks.getById(id) ?: return
        val updated = current.copy(
            status = status,
            updatedAt = System.currentTimeMillis(),
            lastError = if (status == TaskStatus.FAILED) message else current.lastError
        )
        tasks.update(updated)
        appendEvent(id, "STATUS_${status.name}", message)
    }

    suspend fun recordStep(id: String, observedScreenHash: String?, actionSummary: String) {
        val current = tasks.getById(id) ?: return
        tasks.update(
            current.copy(
                stepCount = current.stepCount + 1,
                lastObservedScreenHash = observedScreenHash,
                lastActionSummary = actionSummary,
                updatedAt = System.currentTimeMillis()
            )
        )
        appendEvent(id, "STEP", actionSummary)
    }

    suspend fun recordError(id: String, error: String) {
        val current = tasks.getById(id) ?: return
        tasks.update(
            current.copy(
                lastError = error,
                updatedAt = System.currentTimeMillis()
            )
        )
        appendEvent(id, "ERROR", error)
    }

    suspend fun confirmTask(id: String): Boolean {
        val task = tasks.getById(id) ?: return false
        if (task.status != TaskStatus.WAITING_FOR_CONFIRMATION) return false
        tasks.update(
            task.copy(
                status = TaskStatus.QUEUED,
                requiresConfirmation = false,
                updatedAt = System.currentTimeMillis()
            )
        )
        appendEvent(id, "CONFIRMED", "User confirmed task.")
        return true
    }

    suspend fun cancelTask(id: String) {
        setStatus(id, TaskStatus.CANCELLED, "Task cancelled by user.")
    }

    suspend fun appendEvent(taskId: String, type: String, message: String) {
        events.insert(
            TaskEventEntity(
                taskId = taskId,
                eventType = type,
                message = message.take(2000)
            )
        )
    }
}
