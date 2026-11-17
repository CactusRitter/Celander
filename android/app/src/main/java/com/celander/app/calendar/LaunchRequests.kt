package com.celander.app.calendar

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A view and day to show on the home screen; a null [epochDay] keeps the selected day. */
data class ViewRequest(val destination: HomeDestination, val epochDay: Long?)

/**
 * What to show on the home screen because the app was opened from outside: a sheet (a tapped
 * reminder or widget entry) or a view (a tapped widget).
 */
@Singleton
class LaunchRequests @Inject constructor() {
    private val _sheet = MutableStateFlow<HomeSheet?>(null)
    val sheet: StateFlow<HomeSheet?> = _sheet.asStateFlow()

    private val _view = MutableStateFlow<ViewRequest?>(null)
    val view: StateFlow<ViewRequest?> = _view.asStateFlow()

    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * One signal per request, so navigation returns to the home screen even when the home screen
     * consumed the request before navigation saw it.
     */
    val arrivals: SharedFlow<Unit> = _arrivals.asSharedFlow()

    fun open(sheet: HomeSheet) {
        _sheet.value = sheet
        _arrivals.tryEmit(Unit)
    }

    fun show(request: ViewRequest) {
        _view.value = request
        _arrivals.tryEmit(Unit)
    }

    fun consume() {
        _sheet.value = null
    }

    fun consumeView() {
        _view.value = null
    }
}
