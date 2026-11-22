package com.celander.app.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
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

/** Version 5: the system calendar is no longer read, so its per-calendar settings table goes. */
@DeleteTable(tableName = "device_calendar_prefs")
class DropDeviceCalendarPrefs : AutoMigrationSpec

/** Version 6: the "Manage calendars" switch (`enabled`) is separate from the drawer check (`visible`); what was switched off stays off. */
class AddCalendarEnabled : AutoMigrationSpec {
    override fun onPostMigrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE calendars SET enabled = visible")
    }
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
        GoogleTaskListEntity::class,
        GoogleTaskLinkEntity::class,
        AccountEntity::class,
        RemoteCalendarEntity::class,
        EventLinkEntity::class,
        ExceptionLinkEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5, spec = DropDeviceCalendarPrefs::class), AutoMigration(from = 5, to = 6, spec = AddCalendarEnabled::class)],
)
@TypeConverters(Converters::class)
abstract class CelanderDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
    abstract fun eventDao(): EventDao
    abstract fun exceptionDao(): ExceptionDao
    abstract fun taskDao(): TaskDao
    abstract fun snoozeDao(): SnoozeDao
    abstract fun googleTasksDao(): GoogleTasksDao

    abstract fun accountDao(): AccountDao

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
        Room.databaseBuilder(context, CelanderDatabase::class.java, CelanderDatabase.NAME).addCallback(AccountTriggers.callback).build()

    @Provides fun calendarDao(db: CelanderDatabase) = db.calendarDao()

    @Provides fun eventDao(db: CelanderDatabase) = db.eventDao()

    @Provides fun exceptionDao(db: CelanderDatabase) = db.exceptionDao()

    @Provides fun taskDao(db: CelanderDatabase) = db.taskDao()

    @Provides fun snoozeDao(db: CelanderDatabase) = db.snoozeDao()

    @Provides fun googleTasksDao(db: CelanderDatabase) = db.googleTasksDao()

    @Provides fun accountDao(db: CelanderDatabase) = db.accountDao()
}
