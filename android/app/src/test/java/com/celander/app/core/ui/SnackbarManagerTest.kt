package com.celander.app.core.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Deferred deletes rely on onExpire: once on timeout, replacement or the fallback timer, never after undo. */
@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarManagerTest {
    private val scope = TestScope()
    private val manager = SnackbarManager(scope)
    private var expired = 0
    private var undone = 0

    private fun showUndoable() = manager.show(UiText.Raw("Deleted"), UiText.Raw("Undo"), onExpire = { expired++ }) { undone++ }

    @Test
    fun timeoutExpiresOnce() {
        showUndoable()
        val message = manager.current.value!!
        manager.dismiss(message.id)
        manager.dismiss(message.id)
        scope.advanceTimeBy(200_000)
        scope.runCurrent()
        assertEquals(1, expired)
    }

    @Test
    fun undoNeverExpires() {
        showUndoable()
        manager.performAction(manager.current.value!!)
        scope.advanceTimeBy(200_000)
        scope.runCurrent()
        assertEquals(1, undone)
        assertEquals(0, expired)
    }

    @Test
    fun replacingExpiresTheOldMessage() {
        showUndoable()
        manager.show(UiText.Raw("Something else"))
        scope.runCurrent()
        assertEquals(1, expired)
    }

    @Test
    fun withoutAScreenTheFallbackTimerExpires() {
        showUndoable()
        scope.advanceTimeBy(149_000)
        scope.runCurrent()
        assertEquals(0, expired)
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(1, expired)
    }
}
