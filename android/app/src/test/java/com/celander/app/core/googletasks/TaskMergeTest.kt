package com.celander.app.core.googletasks

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskMergeTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val base = TaskFields("Milk", "2 litres", day, done = false, completedAt = null)
    private val older = 1_000L
    private val newer = 2_000L

    private fun merge(local: TaskFields, remote: TaskFields, localAt: Long = older, remoteAt: Long = newer) =
        TaskMerge.merge(base, local, remote, localAt, remoteAt)

    /** The truth table for one field, checked with every timestamp order. */
    private fun <T> checkField(baseValue: T, a: T, b: T, set: (T) -> TaskFields) {
        for ((localAt, remoteAt) in listOf(older to newer, newer to older, older to older)) {
            val remoteWins = remoteAt >= localAt
            fun run(local: T, remote: T) = TaskMerge.merge(set(baseValue), set(local), set(remote), localAt, remoteAt)
            assertEquals("unchanged", set(baseValue), run(baseValue, baseValue))
            assertEquals("remote only", set(a), run(baseValue, a))
            assertEquals("local only", set(a), run(a, baseValue))
            assertEquals("same change", set(a), run(a, a))
            assertEquals("conflict", set(if (remoteWins) b else a), run(a, b))
        }
    }

    @Test
    fun titleFollowsTheTruthTable() = checkField("Milk", "Oat milk", "Soy milk") { base.copy(title = it) }

    @Test
    fun noteFollowsTheTruthTable() = checkField("2 litres", "", "1 litre") { base.copy(note = it) }

    @Test
    fun dueDayFollowsTheTruthTable() {
        checkField(day, day.plusDays(1), day.plusDays(2)) { base.copy(dueDay = it) }
        checkField(day, null, day.plusDays(2)) { base.copy(dueDay = it) }
        checkField(null, day, day.plusDays(2)) { base.copy(dueDay = it) }
    }

    @Test
    fun completionFollowsTheTruthTableAsOneUnit() {
        fun done(at: Long?) = base.copy(done = true, completedAt = at)
        checkField(base, done(10), done(20)) { it }
        checkField(done(10), base, done(20)) { it }
    }

    @Test
    fun completionTimeChangesOnlyCountWhileDone() {
        val localStale = base.copy(completedAt = 7)
        val remoteDone = base.copy(done = true, completedAt = 20)
        assertEquals(remoteDone, merge(localStale, remoteDone, localAt = newer, remoteAt = older))
    }

    @Test
    fun fieldsMergeIndependently() {
        val local = base.copy(title = "Oat milk", done = true, completedAt = 50)
        val remote = base.copy(note = "1 litre", dueDay = null)
        assertEquals(
            TaskFields("Oat milk", "1 litre", null, done = true, completedAt = 50),
            merge(local, remote, localAt = older, remoteAt = newer),
        )
    }

    @Test
    fun conflictsPerFieldUseTheSameWinner() {
        val local = base.copy(title = "Local", note = "local note", dueDay = day.plusDays(1))
        val remote = base.copy(title = "Remote", note = "remote note", dueDay = day.plusDays(2))
        assertEquals(local, merge(local, remote, localAt = newer, remoteAt = older))
        assertEquals(remote, merge(local, remote, localAt = older, remoteAt = newer))
        assertEquals(remote, merge(local, remote, localAt = newer, remoteAt = newer))
    }

    @Test
    fun completingAgainstAnEditedCompletionTimeKeepsTheWinnersTime() {
        val doneBase = base.copy(done = true, completedAt = 10)
        val local = doneBase.copy(completedAt = 30)
        val remote = doneBase.copy(completedAt = 40)
        assertEquals(local, TaskMerge.merge(doneBase, local, remote, newer, older))
        assertEquals(remote, TaskMerge.merge(doneBase, local, remote, older, older))
    }
}
