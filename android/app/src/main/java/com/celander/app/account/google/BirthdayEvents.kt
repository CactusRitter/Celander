package com.celander.app.account.google

import com.celander.app.account.RemoteEvent
import com.celander.app.core.ical.ICalEvent
import java.time.LocalDate

/** Where the app's birthdays come from, and what the provider needs to know about them for an account. */
interface BirthdaySource {
    /** Whether the user switched on "Birthdays from contacts" for [accountId]. */
    fun enabled(accountId: Long): Boolean

    /** The name of the birthdays calendar, in the language of the app. */
    fun calendarName(): String

    /** The People API refused ([code]: 401 or 403): the switch goes off and the reason is kept for the screen. */
    fun failed(accountId: Long, code: Int)
}

/**
 * The birthdays of the contacts as all-day events of a calendar of its own. A series would not do: the age differs
 * every year ("Ana · 34"), so each contact gets one event per year in a window around today; the daily refresh moves the window.
 */
object BirthdayEvents {
    /** The id of the calendar in the account, which Google does not know. */
    const val CALENDAR_ID = "celander.birthdays"

    /** The color of the calendar (the palette's pink). */
    const val COLOR = "#C94D91"

    /** The uid prefix that marks an event as a birthday (the views show a cake for it). */
    const val UID_PREFIX = "celander-birthday:"

    private const val DAY_MS = 86_400_000L
    private const val YEARS_BEFORE = 1
    private const val YEARS_AFTER = 3

    fun build(contacts: List<ContactBirthday>, today: LocalDate, updated: Long): List<RemoteEvent> =
        contacts.flatMap { contact ->
            (today.year - YEARS_BEFORE..today.year + YEARS_AFTER).map { year ->
                val id = "$UID_PREFIX${contact.contact}:$year"
                val day = date(contact, year).toEpochDay() * DAY_MS
                val ical = ICalEvent(uid = id, summary = title(contact, year), start = day, end = day + DAY_MS, allDay = true, timeZone = "UTC")
                RemoteEvent(id, etag = null, updated = updated, ical = ical, reminders = emptyList())
            }
        }

    /** "Ana · 34" when the contact has a year of birth, else just the name. */
    fun title(contact: ContactBirthday, year: Int): String {
        val age = contact.year?.let { year - it }?.takeIf { it >= 0 } ?: return contact.name
        return "${contact.name} · $age"
    }

    /** The day in [year]; 29 February falls on the 28th in years that have no 29th; 30 and 31 are clamped to the month. */
    fun date(contact: ContactBirthday, year: Int): LocalDate {
        val length = java.time.YearMonth.of(year, contact.month).lengthOfMonth()
        return LocalDate.of(year, contact.month, minOf(contact.day, length))
    }
}
