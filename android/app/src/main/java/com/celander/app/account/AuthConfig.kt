package com.celander.app.account

/** Celander's values for the shared sign-in code in com.ce.kit.auth. Do not change alias/prefs: it would sign everybody out. */
object AuthConfig {
    /** Asked only when the user switches on "Birthdays from contacts" (incremental consent). */
    const val CONTACTS_SCOPE = "https://www.googleapis.com/auth/contacts.readonly"
    const val GOOGLE_SCOPES = "openid email profile " +
        "https://www.googleapis.com/auth/calendar.events " +
        "https://www.googleapis.com/auth/calendar.calendarlist.readonly " +
        "https://www.googleapis.com/auth/tasks"
    const val MICROSOFT_SCOPES = "openid profile email offline_access " +
        "https://graph.microsoft.com/Calendars.ReadWrite https://graph.microsoft.com/User.Read"
    const val MICROSOFT_REDIRECT = "com.celander.app://oauth2redirect"
    const val TOKEN_ALIAS = "celander_tokens"
    const val TOKEN_PREFS = "account_tokens"
}
