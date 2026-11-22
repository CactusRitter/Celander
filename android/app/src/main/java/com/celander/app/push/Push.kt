package com.celander.app.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.celander.app.BuildConfig
import com.celander.app.account.AccountRepository
import com.celander.app.account.AccountTokens
import com.ce.kit.auth.GoogleAuth
import com.ce.kit.auth.MicrosoftAuth
import com.ce.kit.auth.TokenStore
import com.celander.app.core.database.AccountDao
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountType
import com.celander.app.settings.SettingsRepository
import com.ce.kit.log.AppLog
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Instant calendar updates ("push"), see Ce/docs/PUSH-CELANDER-PLAN.md. Google Calendar (`events.watch`, one channel per
 * calendar) and Microsoft Graph (one subscription to the events) call a small relay (a Cloudflare Worker) when something
 * changes; the relay sends this phone a Firebase Cloud Messaging wake-up and [PushService] syncs that account. The relay
 * only learns the account's address, channel and subscription ids and this phone's FCM token, never an event.
 */
object Push {
    /** All the keys are in local.properties; without them (or without Play Services) the app polls as before. */
    val available: Boolean
        get() = BuildConfig.PUSH_URL.isNotBlank() && BuildConfig.PUSH_APP_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()

    /** Starts Firebase from BuildConfig (no google-services file, so no keys in git). */
    fun initFirebase(context: Context) {
        if (!available || FirebaseApp.getApps(context).isNotEmpty()) return
        try {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        } catch (e: Exception) {
            AppLog.e("push", "could not start Firebase", e)
        }
    }

    internal fun relay(path: String) = BuildConfig.PUSH_URL.trimEnd('/') + path
}

/** What was set up at the providers for one account, kept between runs (the ids are needed to stop or renew). */
private class GoogleChannel(val calendarId: String, val channelId: String, val resourceId: String, val token: String, val expiresAt: Long)

private class MicrosoftSubscription(val id: String, val clientState: String, val expiresAt: Long)

/** Brings the providers and the relay in line with the connected accounts and the switch in Settings. */
@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AccountDao,
    private val accounts: AccountRepository,
    private val store: TokenStore,
    private val google: GoogleAuth,
    private val microsoft: MicrosoftAuth,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val lock = Mutex()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val prefs get() = context.getSharedPreferences("push_calendar", Context.MODE_PRIVATE)
    private val tokens = AccountTokens(store, google, microsoft)

    /** Follows the switch and the accounts: every change runs [sync], and the daily renewal exists only while it is on. */
    fun start(scope: CoroutineScope) {
        Push.initFirebase(context)
        scope.launch {
            dao.observeAccounts().map { list -> list.map { it.id to it.identifier } }
                .distinctUntilChanged()
                .collect {
                    PushRenewWorker.schedule(context, Push.available)
                    runCatching { sync(true) }
                }
        }
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) AppLog.e("push", "no FCM token", task.exception)
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        } catch (e: Exception) {
            AppLog.e("push", "no FCM token (Play Services missing?)", e)
            continuation.resume(null)
        }
    }

    /**
     * Safe to call as often as wanted. With [enabled] every Google calendar gets a watch channel and every Microsoft
     * account a subscription (kept while they have more than two days left), and the relay learns where to send the
     * wake-ups; without it everything is stopped and forgotten. True when nothing needs a second try.
     */
    suspend fun sync(enabled: Boolean): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!Push.available) return@withContext true
            Push.initFirebase(context)
            val connected = dao.accounts().filter { it.type == AccountType.GOOGLE.name || it.type == AccountType.MICROSOFT.name }
            val saved = loadState()
            val previous = saved.keys.toList()
            var ok = true
            val relayAccounts = JSONArray()
            val newState = JSONObject()
            // Accounts that are gone: their channels and subscriptions simply run out (the relay ignores them).
            for (account in connected) {
                val email = account.identifier.lowercase()
                val before = saved[email]
                try {
                    val entry = when (AccountType.valueOf(account.type)) {
                        AccountType.GOOGLE -> googleSetup(account, before, enabled)
                        else -> microsoftSetup(account, before, enabled)
                    }
                    if (enabled && entry != null) {
                        newState.put(email, entry.state)
                        relayAccounts.put(entry.relay.put("email", email))
                    }
                } catch (e: Exception) {
                    ok = false
                    // What was there stays, so that the next run can renew or stop it.
                    before?.let { newState.put(email, it) }
                    AppLog.e("push", "...${account.id} ${account.type} could not be set up", e)
                }
            }
            val token = fcmToken() ?: return@withContext false
            val registered = if (enabled && relayAccounts.length() > 0) {
                post("/cal/register", JSONObject().put("token", token).put("accounts", relayAccounts).put("previous", JSONArray(previous.filter { p -> connected.none { it.identifier.equals(p, true) } })))
            } else if (previous.isNotEmpty()) {
                post("/cal/unregister", JSONObject().put("token", token).put("emails", JSONArray(previous)))
            } else {
                true
            }
            if (registered) saveState(newState) else ok = false
            ok
        }
    }

    private class Entry(val state: JSONObject, val relay: JSONObject)

    private suspend fun googleSetup(account: AccountEntity, before: JSONObject?, enabled: Boolean): Entry? {
        val old = before?.optJSONArray("channels").toList { GoogleChannel(it.getString("calendarId"), it.getString("channelId"), it.getString("resourceId"), it.getString("token"), it.getLong("expiresAt")) }
        if (!enabled) {
            old.forEach { stopChannel(account, it) }
            return null
        }
        val now = System.currentTimeMillis()
        // The birthdays calendar is made here from the contacts; Google has no such calendar to watch.
        val calendars = dao.remoteCalendars(account.id).filter { it.remoteId != com.celander.app.account.google.BirthdayEvents.CALENDAR_ID }
        val channels = mutableListOf<GoogleChannel>()
        for (calendar in calendars) {
            val existing = old.firstOrNull { it.calendarId == calendar.remoteId }
            if (existing != null && existing.expiresAt - now > RENEW_MARGIN_MILLIS) {
                channels += existing
                continue
            }
            val id = UUID.randomUUID().toString()
            val secret = UUID.randomUUID().toString()
            val body = JSONObject().put("id", id).put("type", "web_hook").put("address", Push.relay("/gcal")).put("token", secret)
                .put("params", JSONObject().put("ttl", "604800"))
            val url = "https://www.googleapis.com/calendar/v3/calendars/" + java.net.URLEncoder.encode(calendar.remoteId, "UTF-8").replace("+", "%20") + "/events/watch"
            val answer = try {
                authorized(account) { token -> send(token, "POST", url, body) }
            } catch (e: HttpFailure) {
                // A calendar that cannot be watched (holidays, birthdays…) is left to the polling; the others go on.
                if (e.code == 400 || e.code == 403 || e.code == 404) {
                    AppLog.i("push", "calendar ...${calendar.calendarId} not watched: ${e.message}")
                    continue
                }
                throw e
            }
            channels += GoogleChannel(calendar.remoteId, id, answer.getString("resourceId"), secret, answer.optString("expiration").toLongOrNull() ?: (now + 6 * DAY_MILLIS))
            // The channel it replaces is no longer needed.
            existing?.let { stopChannel(account, it) }
        }
        if (channels.isEmpty()) return null
        // Calendars that are gone from the account.
        old.filter { o -> calendars.none { it.remoteId == o.calendarId } }.forEach { stopChannel(account, it) }
        val state = JSONObject().put("channels", JSONArray(channels.map { JSONObject().put("calendarId", it.calendarId).put("channelId", it.channelId).put("resourceId", it.resourceId).put("token", it.token).put("expiresAt", it.expiresAt) }))
        val relay = JSONObject().put("provider", "google").put("channels", JSONArray(channels.map { JSONObject().put("id", it.channelId).put("token", it.token) }))
        return Entry(state, relay)
    }

    private suspend fun stopChannel(account: AccountEntity, channel: GoogleChannel) {
        try {
            authorized(account) { token ->
                send(token, "POST", "https://www.googleapis.com/calendar/v3/channels/stop", JSONObject().put("id", channel.channelId).put("resourceId", channel.resourceId))
            }
        } catch (e: Exception) {
            // Already expired or unknown: nothing to stop.
            AppLog.i("push", "channel not stopped: ${e.message}")
        }
    }

    private suspend fun microsoftSetup(account: AccountEntity, before: JSONObject?, enabled: Boolean): Entry? {
        val old = before?.optJSONObject("subscription")?.let { MicrosoftSubscription(it.getString("id"), it.getString("clientState"), it.getLong("expiresAt")) }
        if (!enabled) {
            old?.let { deleteSubscription(account, it) }
            return null
        }
        val expiration = java.time.Instant.now().plus(SUBSCRIPTION_MINUTES, java.time.temporal.ChronoUnit.MINUTES)
        var current: MicrosoftSubscription? = null
        if (old != null) {
            // Renewed every run: it lasts less than three days.
            current = try {
                authorized(account) { token -> send(token, "PATCH", GRAPH + "subscriptions/" + old.id, JSONObject().put("expirationDateTime", expiration.toString())) }
                MicrosoftSubscription(old.id, old.clientState, expiration.toEpochMilli())
            } catch (e: HttpFailure) {
                if (e.code == 404) null else throw e
            }
        }
        if (current == null) {
            val clientState = UUID.randomUUID().toString()
            val body = JSONObject().put("changeType", "created,updated,deleted").put("notificationUrl", Push.relay("/graph"))
                .put("resource", "me/events").put("expirationDateTime", expiration.toString()).put("clientState", clientState)
            val answer = authorized(account) { token -> send(token, "POST", GRAPH + "subscriptions", body) }
            current = MicrosoftSubscription(answer.getString("id"), clientState, expiration.toEpochMilli())
        }
        val state = JSONObject().put("subscription", JSONObject().put("id", current.id).put("clientState", current.clientState).put("expiresAt", current.expiresAt))
        val relay = JSONObject().put("provider", "microsoft").put("subscription", JSONObject().put("id", current.id).put("clientState", current.clientState))
        return Entry(state, relay)
    }

    private suspend fun deleteSubscription(account: AccountEntity, subscription: MicrosoftSubscription) {
        try {
            authorized(account) { token -> send(token, "DELETE", GRAPH + "subscriptions/" + subscription.id, null) }
        } catch (e: Exception) {
            AppLog.i("push", "subscription not deleted: ${e.message}")
        }
    }

    /** Runs [call] with the account's access token, once more with a fresh token when the first was refused. */
    private suspend fun <T> authorized(account: AccountEntity, call: (String) -> T): T {
        val first = tokens.token(account, false) ?: throw IOException("no token")
        return try {
            call(first)
        } catch (e: HttpFailure) {
            if (e.code != 401) throw e
            call(tokens.token(account, true) ?: throw IOException("no token"))
        }
    }

    private class HttpFailure(val code: Int, val reason: String? = null) : IOException("HTTP $code" + (reason?.let { " · $it" } ?: ""))

    private fun send(token: String, method: String, url: String, body: JSONObject?): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .method(method, if (method == "GET" || method == "DELETE") null else (body ?: JSONObject()).toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(text).getJSONObject("error").let { e -> e.optString("message") + " [" + (e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason") ?: e.optString("status")) + "]" } }.getOrNull()
                throw HttpFailure(response.code, reason)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun post(path: String, body: JSONObject): Boolean = try {
        val request = Request.Builder().url(Push.relay(path)).header("X-Celander-Key", BuildConfig.PUSH_APP_KEY)
            .post(body.toString().toRequestBody(json)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) AppLog.i("push", "relay answered ${response.code} for $path")
            response.isSuccessful
        }
    } catch (e: Exception) {
        AppLog.e("push", "could not reach the relay", e)
        false
    }

    private fun loadState(): Map<String, JSONObject> {
        val text = prefs.getString("state", null) ?: return emptyMap()
        return try {
            val all = JSONObject(text)
            all.keys().asSequence().associateWith { all.getJSONObject(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveState(state: JSONObject) = prefs.edit().putString("state", state.toString()).apply()

    /** The account (by address) a wake-up names; its sync is the quick one the app does anyway. */
    suspend fun syncAccountOf(email: String) {
        val account = dao.accounts().firstOrNull { it.identifier.equals(email, ignoreCase = true) } ?: return
        accounts.syncAccount(account.id)
    }

    /**
     * Accounts are about to be removed: their channels and subscriptions are stopped at Google/Microsoft (the tokens are
     * still there) and the relay forgets them (`/cal/unregister`). Never throws; what could not be told to the relay stays
     * in the saved state and the next [sync] sends it as "previous".
     */
    suspend fun forget(accountIds: List<Long>) = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Push.available) return@runCatching
                val saved = loadState()
                val gone = dao.accounts().filter { it.id in accountIds && (it.type == AccountType.GOOGLE.name || it.type == AccountType.MICROSOFT.name) }
                val emails = mutableListOf<String>()
                for (account in gone) {
                    val email = account.identifier.lowercase()
                    val before = saved[email] ?: continue
                    try {
                        if (AccountType.valueOf(account.type) == AccountType.GOOGLE) googleSetup(account, before, false) else microsoftSetup(account, before, false)
                    } catch (e: Exception) {
                        AppLog.e("push", "...${account.id} could not be stopped", e)
                    }
                    emails += email
                }
                if (emails.isEmpty()) return@runCatching
                val token = fcmToken() ?: return@runCatching
                if (post("/cal/unregister", JSONObject().put("token", token).put("emails", JSONArray(emails)))) {
                    val rest = JSONObject()
                    saved.forEach { (k, v) -> if (k !in emails) rest.put(k, v) }
                    saveState(rest)
                }
            }.onFailure { AppLog.e("push", "forget failed", it) }
        }
    }

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).map { map(getJSONObject(it)) }

    private companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0/me/"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val RENEW_MARGIN_MILLIS = 2 * DAY_MILLIS
        /** A little under the three days Graph allows for event subscriptions. */
        const val SUBSCRIPTION_MINUTES = 4200L
    }
}

/** Hilt access for the pieces Android creates itself (the messaging service and the worker). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PushEntryPoint {
    fun push(): PushManager
}

/** Receives the wake-up and syncs the account it names. */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun manager() = EntryPointAccessors.fromApplication(applicationContext, PushEntryPoint::class.java).push()

    override fun onNewToken(token: String) {
        val push = manager()
        scope.launch { runCatching { push.sync(true) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
        if (type != "gcal" && type != "mscal") return
        val email = message.data["email"].orEmpty()
        AppLog.i("push", "wake-up received ($type)")
        // A high-priority message gives the app a few seconds: the sync runs right here, with a limit.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(20_000) { runCatching { manager().syncAccountOf(email) } }
        }
    }
}

/** Once a day: renews the channels and subscriptions (they last a week and less than three days) and the registration. */
class PushRenewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val push = EntryPointAccessors.fromApplication(applicationContext, PushEntryPoint::class.java).push()
        return if (push.sync(true)) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "push-renew"

        fun schedule(context: Context, on: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!on) {
                manager.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PushRenewWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
