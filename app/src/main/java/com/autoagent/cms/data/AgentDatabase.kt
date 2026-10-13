package com.autoagent.cms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class AgentConverters {
    @TypeConverter
    fun taskStatusToString(value: TaskStatus): String = value.name

    @TypeConverter
    fun stringToTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)
}

@Database(
    entities = [
        TaskEntity::class,
        TaskEventEntity::class,
        ProcessedMessageEntity::class,
        RecipeEntity::class,
        ProviderKeyEntity::class,
        ProviderUsageEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(AgentConverters::class)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskEventDao(): TaskEventDao
    abstract fun processedMessageDao(): ProcessedMessageDao
    abstract fun recipeDao(): RecipeDao
    abstract fun providerKeyDao(): ProviderKeyDao
    abstract fun providerUsageDao(): ProviderUsageDao

    companion object {
        @Volatile
        private var instance: AgentDatabase? = null

        fun get(context: Context): AgentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "auto_agent_cms.db"
                ).build().also { instance = it }
            }
    }
}
