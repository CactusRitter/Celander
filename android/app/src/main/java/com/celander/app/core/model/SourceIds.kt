package com.celander.app.core.model

/**
 * Ids of calendars, events and exceptions that live in Android's calendar provider (Google Calendar
 * and other accounts on the phone) instead of Celander's database. They sit in their own range from
 * 2^40, which Room's autoincrement ids never reach, so every place that passes a Long id keeps
 * working and can tell the two apart.
 */
object SourceIds {
    const val DEVICE_BASE: Long = 1L shl 40

    fun device(providerId: Long): Long {
        require(providerId in 0 until DEVICE_BASE) { "provider id out of range: $providerId" }
        return DEVICE_BASE + providerId
    }

    fun isDevice(id: Long): Boolean = id >= DEVICE_BASE

    fun providerId(id: Long): Long {
        require(isDevice(id)) { "not a device id: $id" }
        return id - DEVICE_BASE
    }
}
