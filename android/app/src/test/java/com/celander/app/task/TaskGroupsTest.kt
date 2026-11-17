package com.celander.app.task

import com.celander.app.testing.taskItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TaskGroupsTest {
    private val today = LocalDate.of(2026, 10, 23)

    @Test
    fun tasksAreGroupedAndOverdueOnesKeepTheirDate() {
        val items = listOf(
            taskItem(1, today.minusDays(3)),
            taskItem(2, today, LocalTime.of(18, 0)),
            taskItem(3, today),
            taskItem(4, today.plusDays(2)),
            taskItem(5, null),
            taskItem(6, today.minusDays(1)).let { it.copy(task = it.task.copy(done = true, completedAt = 10)) },
            taskItem(7, null).let { it.copy(task = it.task.copy(done = true, completedAt = 20)) },
        )
        val sections = TaskGroups.build(items, today)
        assertEquals(
            listOf(TaskGroup.OVERDUE, TaskGroup.TODAY, TaskGroup.UPCOMING, TaskGroup.NO_DATE, TaskGroup.DONE),
            sections.map { it.group },
        )
        assertEquals(listOf(1L), sections[0].items.map { it.task.id })
        // Untimed tasks come first within a day.
        assertEquals(listOf(3L, 2L), sections[1].items.map { it.task.id })
        // Most recently completed first.
        assertEquals(listOf(7L, 6L), sections[4].items.map { it.task.id })
    }

    @Test
    fun newestTasksWithoutDateComeFirst() {
        val older = taskItem(1, null).let { it.copy(task = it.task.copy(createdAt = 1)) }
        val newer = taskItem(2, null).let { it.copy(task = it.task.copy(createdAt = 2)) }
        val section = TaskGroups.build(listOf(older, newer), today).single()
        assertEquals(listOf(2L, 1L), section.items.map { it.task.id })
    }

    @Test
    fun emptySectionsAreLeftOut() {
        assertEquals(emptyList<TaskSection>(), TaskGroups.build(emptyList(), today))
    }
}
