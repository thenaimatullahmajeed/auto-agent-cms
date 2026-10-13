package com.autoagent.cms.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE status IN ('QUEUED', 'PAUSED') ORDER BY createdAt ASC")
    suspend fun getRecoverable(): List<TaskEntity>

    @Query("UPDATE agent_tasks SET status = 'PAUSED', lastError = :reason, updatedAt = :now WHERE status = 'RUNNING'")
    suspend fun pauseInterrupted(reason: String, now: Long)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface TaskEventDao {
    @Insert
    suspend fun insert(event: TaskEventEntity)

    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeForTask(taskId: String): Flow<List<TaskEventEntity>>

    @Query("SELECT * FROM task_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskEventEntity>>
}

@Dao
interface ProcessedMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(message: ProcessedMessageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM processed_messages WHERE source = :source AND messageKey = :messageKey)")
    suspend fun exists(source: String, messageKey: String): Boolean
}

@Dao
interface RecipeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: RecipeEntity)

    @Query("SELECT * FROM task_recipes WHERE targetPackage = :targetPackage AND intentKey = :intentKey ORDER BY successfulRuns DESC LIMIT 1")
    suspend fun findCandidate(targetPackage: String, intentKey: String): RecipeEntity?

    @Query("SELECT * FROM task_recipes ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<RecipeEntity>>
}

@Dao
interface ProviderKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: ProviderKeyEntity)

    @Query("SELECT * FROM provider_keys WHERE provider = :provider")
    suspend fun getForProvider(provider: String): List<ProviderKeyEntity>

    @Query("SELECT * FROM provider_keys ORDER BY provider, nickname")
    fun observeAll(): Flow<List<ProviderKeyEntity>>

    @Query("SELECT * FROM provider_keys WHERE status IN ('ACTIVE','VALID','UNCHECKED') ORDER BY provider, nickname")
    suspend fun getActive(): List<ProviderKeyEntity>

    @Query("DELETE FROM provider_keys WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE provider_keys SET status = :status, cooldownUntil = :cooldownUntil, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, cooldownUntil: Long, now: Long)
}

@Dao
interface ProviderUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: ProviderUsageEntity)

    @Query("SELECT * FROM provider_usage WHERE provider = :provider AND model = :model ORDER BY windowStartedAt DESC")
    suspend fun getRecent(provider: String, model: String): List<ProviderUsageEntity>
}
