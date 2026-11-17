package com.celander.app.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

class Converters {
    @TypeConverter
    fun fromMinutes(list: List<Int>): String = list.joinToString(",")

    @TypeConverter
    fun toMinutes(value: String): List<Int> = if (value.isBlank()) emptyList() else value.split(',').mapNotNull { it.trim().toIntOrNull() }
}

@Database(
    entities = [
        CalendarEntity::class,
        EventEntity::class,
        EventExceptionEntity::class,
        TaskEntity::class,
        EventFts::class,
        TaskFts::class,
        SnoozeEntity::class,
        DeviceCalendarPrefEntity::class,
        GoogleTaskListEntity::class,
        GoogleTaskLinkEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class CelanderDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
    abstract fun eventDao(): EventDao
    abstract fun exceptionDao(): ExceptionDao
    abstract fun taskDao(): TaskDao
    abstract fun snoozeDao(): SnoozeDao
    abstract fun devicePrefsDao(): DevicePrefsDao
    abstract fun googleTasksDao(): GoogleTasksDao

    companion object {
        /** New file name: the v1 prototype used "celander.db" with an incompatible schema. */
        const val NAME = "celander_local.db"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): CelanderDatabase =
        Room.databaseBuilder(context, CelanderDatabase::class.java, CelanderDatabase.NAME).build()

    @Provides fun calendarDao(db: CelanderDatabase) = db.calendarDao()

    @Provides fun eventDao(db: CelanderDatabase) = db.eventDao()

    @Provides fun exceptionDao(db: CelanderDatabase) = db.exceptionDao()

    @Provides fun taskDao(db: CelanderDatabase) = db.taskDao()

    @Provides fun snoozeDao(db: CelanderDatabase) = db.snoozeDao()

    @Provides fun devicePrefsDao(db: CelanderDatabase) = db.devicePrefsDao()

    @Provides fun googleTasksDao(db: CelanderDatabase) = db.googleTasksDao()
}
