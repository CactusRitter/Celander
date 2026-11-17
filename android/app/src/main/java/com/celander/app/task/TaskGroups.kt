package com.celander.app.task

import com.celander.app.core.model.TaskItem
import java.time.LocalDate
import java.time.LocalTime

enum class TaskGroup { OVERDUE, TODAY, UPCOMING, NO_DATE, DONE }

data class TaskSection(val group: TaskGroup, val items: List<TaskItem>)

/**
 * Sorts tasks into the sections of the task overview. Overdue tasks keep their date and never move
 * to today on their own. Pure, so it is unit-testable.
 */
object TaskGroups {
    private val byDue = compareBy<TaskItem>({ it.task.dueDate }, { it.task.dueTime ?: LocalTime.MIN }, { it.task.title.lowercase() }, { it.task.id })

    fun build(items: List<TaskItem>, today: LocalDate): List<TaskSection> {
        val (done, open) = items.partition { it.task.done }
        val grouped = open.groupBy { item ->
            val due = item.task.dueDate
            when {
                due == null -> TaskGroup.NO_DATE
                due.isBefore(today) -> TaskGroup.OVERDUE
                due == today -> TaskGroup.TODAY
                else -> TaskGroup.UPCOMING
            }
        }
        return buildList {
            for (group in listOf(TaskGroup.OVERDUE, TaskGroup.TODAY, TaskGroup.UPCOMING)) {
                grouped[group]?.let { add(TaskSection(group, it.sortedWith(byDue))) }
            }
            // Newest first, so a task added with quick add shows up at the top of its section.
            grouped[TaskGroup.NO_DATE]?.let { list -> add(TaskSection(TaskGroup.NO_DATE, list.sortedByDescending { it.task.createdAt })) }
            if (done.isNotEmpty()) add(TaskSection(TaskGroup.DONE, done.sortedByDescending { it.task.completedAt ?: 0 }))
        }
    }
}
