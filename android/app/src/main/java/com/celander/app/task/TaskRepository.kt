package com.celander.app.task

import androidx.room.withTransaction
import com.celander.app.core.database.CelanderDatabase
import com.celander.app.core.database.GoogleTasksDao
import com.celander.app.core.database.TaskDao
import com.celander.app.core.database.TaskEntity
import com.celander.app.core.database.toEntity
import com.celander.app.core.database.toModel
import com.celander.app.core.model.Task
import com.celander.app.core.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val db: CelanderDatabase,
    private val dao: TaskDao,
    private val googleTasks: GoogleTasksDao,
    private val time: TimeSource,
) {
    /** All tasks of visible calendars, for the task overview. */
    val visibleTasks: Flow<List<Task>> = dao.observeVisible().map { list -> list.map { it.toModel() } }

    suspend fun get(id: Long): Task? = dao.getById(id)?.toModel()

    fun observe(id: Long): Flow<Task?> = dao.observeById(id).map { it?.toModel() }

    suspend fun create(task: Task): Long {
        val now = time.now().toEpochMilli()
        return dao.insert(task.copy(id = 0, createdAt = now, updatedAt = now).toEntity())
    }

    suspend fun update(task: Task) = dao.update(task.copy(updatedAt = time.now().toEpochMilli()).toEntity())

    suspend fun setDone(id: Long, done: Boolean) {
        val now = time.now().toEpochMilli()
        dao.setDone(id, done, if (done) now else null, now)
    }

    /**
     * Deletes a task. A task in a Google list keeps its link as a tombstone; its undo window starts
     * now, and the delete reaches Google only after it.
     */
    suspend fun delete(id: Long): TaskEntity? {
        val now = time.now().toEpochMilli()
        return db.withTransaction {
            val entity = dao.getById(id) ?: return@withTransaction null
            dao.delete(id)
            googleTasks.markDeleted(id, now)
            entity
        }
    }

    /** Undo of [delete]; a delete not yet sent to Google is dropped. */
    suspend fun restore(entity: TaskEntity) = db.withTransaction {
        dao.restore(entity)
        googleTasks.clearDeleted(entity.id)
    }
}
