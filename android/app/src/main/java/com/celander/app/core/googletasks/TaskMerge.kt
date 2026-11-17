package com.celander.app.core.googletasks

/** Three-way merge of a task changed both locally and at Google since the last sync. */
object TaskMerge {
    /**
     * Merges per field: title, note, due date, and done with its completion time as one unit. A
     * field changed on one side only takes that side; changed on both sides to the same value it is
     * kept; changed to different values the newer side wins, and Google wins a tie.
     */
    fun merge(
        base: TaskFields,
        local: TaskFields,
        remote: TaskFields,
        localUpdatedAt: Long,
        remoteUpdated: Long,
    ): TaskFields {
        val remoteWins = remoteUpdated >= localUpdatedAt
        fun <T> pick(base: T, local: T, remote: T, same: (T, T) -> Boolean = { a, b -> a == b }): T = when {
            same(local, base) -> remote
            same(remote, base) -> local
            same(local, remote) -> local
            remoteWins -> remote
            else -> local
        }
        val completion = pick(base, local, remote) { a, b -> a.sameCompletion(b) }
        return TaskFields(
            title = pick(base.title, local.title, remote.title),
            note = pick(base.note, local.note, remote.note),
            dueDay = pick(base.dueDay, local.dueDay, remote.dueDay),
            done = completion.done,
            completedAt = completion.completedAt,
        )
    }
}
