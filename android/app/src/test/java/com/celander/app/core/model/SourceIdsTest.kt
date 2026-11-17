package com.celander.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIdsTest {
    @Test
    fun deviceIdsRoundTrip() {
        for (providerId in listOf(0L, 1L, 42L, (1L shl 40) - 1)) {
            val id = SourceIds.device(providerId)
            assertTrue(SourceIds.isDevice(id))
            assertEquals(providerId, SourceIds.providerId(id))
        }
    }

    @Test
    fun roomIdsAndSentinelsAreNotDeviceIds() {
        for (id in listOf(-1L, 0L, 1L, 123_456L, (1L shl 40) - 1)) assertFalse(SourceIds.isDevice(id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeProviderIdsAreRejected() {
        SourceIds.device(-5)
    }

    @Test
    fun calendarsHoldTheRightKinds() {
        val local = CalendarInfo(1, "Personal", "lavender")
        val device = local.copy(source = CalendarSource.DEVICE)
        val tasks = local.copy(source = CalendarSource.GOOGLE_TASKS)
        assertTrue(local.canHold(EntryKind.EVENT) && local.canHold(EntryKind.TASK))
        assertTrue(device.canHold(EntryKind.EVENT) && !device.canHold(EntryKind.TASK))
        assertTrue(!tasks.canHold(EntryKind.EVENT) && tasks.canHold(EntryKind.TASK))
        assertFalse(device.copy(writable = false).canHold(EntryKind.EVENT))
    }
}
