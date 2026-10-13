package com.autoagent.cms.automation

import com.autoagent.cms.data.AgentDatabase
import com.autoagent.cms.data.RecipeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class RecipeCandidate(
    val recipeId: String,
    val targetPackage: String,
    val intentKey: String,
    val parameterSchemaJson: String,
    val successfulRuns: Int
)

class RecipeStore(private val database: AgentDatabase) {
    suspend fun findCandidate(targetPackage: String, intentKey: String): RecipeCandidate? =
        withContext(Dispatchers.IO) {
            val recipe = database.recipeDao()
                .findCandidate(targetPackage, intentKey) ?: return@withContext null
            RecipeCandidate(
                recipeId = recipe.id,
                targetPackage = recipe.targetPackage,
                intentKey = recipe.intentKey,
                parameterSchemaJson = recipe.parameterSchemaJson,
                successfulRuns = recipe.successfulRuns
            )
        }

    suspend fun recordSuccessfulRun(
        targetPackage: String,
        intentKey: String,
        parameterSchemaJson: String
    ) = withContext(Dispatchers.IO) {
        val dao = database.recipeDao()
        val existing = dao.findCandidate(targetPackage, intentKey)
        val now = System.currentTimeMillis()
        dao.upsert(
            RecipeEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                targetPackage = targetPackage,
                intentKey = intentKey,
                parameterSchemaJson = parameterSchemaJson,
                successfulRuns = (existing?.successfulRuns ?: 0) + 1,
                failedRuns = existing?.failedRuns ?: 0,
                lastUsedAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun recordFailedRun(targetPackage: String, intentKey: String) =
        withContext(Dispatchers.IO) {
            val dao = database.recipeDao()
            val existing = dao.findCandidate(targetPackage, intentKey) ?: return@withContext
            dao.upsert(
                existing.copy(
                    failedRuns = existing.failedRuns + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
}
