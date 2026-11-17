package com.celander.app.core.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/** Clock abstraction so date logic can be tested with fixed instants and zones. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId

    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun nowZoned(): ZonedDateTime = now().atZone(zone())

    /** Emits the current instant every [periodMillis]; used for "now" lines and midnight rollover. */
    fun ticker(periodMillis: Long = 30_000): Flow<Instant> = flow {
        while (true) {
            emit(now())
            delay(periodMillis)
        }
    }

    fun todayFlow(): Flow<LocalDate> = flow {
        ticker().collect { emit(it.atZone(zone()).toLocalDate()) }
    }.distinctUntilChanged()
}

class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
