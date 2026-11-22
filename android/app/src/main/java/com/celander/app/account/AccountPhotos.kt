package com.celander.app.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ce.kit.log.AppLog
import com.celander.app.account.google.AccessTokens
import com.celander.app.core.database.AccountEntity
import com.celander.app.core.model.AccountType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The picture of each account, as in Cemail: the one the account has at Google or Microsoft, fetched once and checked
 * again every few days. Kept as a small square JPEG in the app's files; [version] changes whenever any picture does, so
 * the screens redraw. Subscriptions and accounts without a picture have none (the screens show the initial).
 */
class AccountPhotos(
    private val context: Context,
    private val client: OkHttpClient,
    private val tokens: AccessTokens,
) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun file(accountId: Long): File = File(context.filesDir, "avatars/$accountId.jpg")

    private fun prefs() = context.getSharedPreferences("avatars", Context.MODE_PRIVATE)

    /** Fetches the provider's picture unless it was checked lately. */
    suspend fun refreshIfStale(account: AccountEntity) {
        if (account.type != AccountType.GOOGLE.name && account.type != AccountType.MICROSOFT.name) return
        val checked = prefs().getLong(checkedKey(account.id), 0L)
        if (System.currentTimeMillis() - checked < RECHECK_MILLIS && (checked > 0 || file(account.id).exists())) return
        fetch(account)
    }

    /** Deletes the picture of a removed account. */
    fun remove(accountId: Long) {
        file(accountId).delete()
        prefs().edit().remove(checkedKey(accountId)).apply()
        _version.value++
    }

    private suspend fun fetch(account: AccountEntity) = withContext(Dispatchers.IO) {
        val bytes = try {
            val token = tokens.token(account, forceRefresh = false) ?: return@withContext
            if (account.type == AccountType.GOOGLE.name) googlePicture(token) else microsoftPicture(token)
        } catch (e: Exception) {
            AppLog.e("photos", "picture of account ${account.id} not fetched", e)
            return@withContext // offline, say: tried again next time
        }
        // Found: look again in a week. Not found (a refusal or a failed answer, not just "no picture"): try again in a few hours.
        val now = System.currentTimeMillis()
        prefs().edit().putLong(checkedKey(account.id), if (bytes != null) now else now - (RECHECK_MILLIS - RETRY_MILLIS)).apply()
        AppLog.i("photos", "picture of account ${account.id}: ${if (bytes != null) "${bytes.size} bytes" else "none"}")
        if (bytes != null) save(account.id, bytes)
    }

    private fun googlePicture(token: String): ByteArray? {
        val info = get("https://openidconnect.googleapis.com/v1/userinfo", token) ?: return null
        val url = JSONObject(String(info)).optString("picture").takeIf { it.isNotBlank() } ?: return null
        // Google's picture addresses end with the size ("=s96-c"): a bigger one looks sharp on large screens.
        return get(url.replace(Regex("=s\\d+(-c)?$"), "=s$SIZE-c"), null)
    }

    private fun microsoftPicture(token: String): ByteArray? =
        get("https://graph.microsoft.com/v1.0/me/photos/240x240/\$value", token)
            ?: get("https://graph.microsoft.com/v1.0/me/photo/\$value", token)

    /** The body, or null when the server says there is nothing (404 and the like). Network failures throw. */
    private fun get(url: String, token: String?): ByteArray? {
        val request = Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) AppLog.i("photos", "${request.url.host}: ${response.code}")
            return if (response.isSuccessful) response.body?.bytes() else null
        }
    }

    /** Crops the picture to a centred square, shrinks it and keeps it as JPEG. */
    private fun save(accountId: Long, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        val target = file(accountId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        temp.renameTo(target)
        _version.value++
    }

    private companion object {
        const val SIZE = 256
        const val RECHECK_MILLIS = 7L * 24 * 3600 * 1000
        const val RETRY_MILLIS = 6L * 3600 * 1000

        /** A new key (v2) so every account is looked at again once with the retry rule; the old key could hold a failed check. */
        fun checkedKey(accountId: Long) = "checked_v2_$accountId"
    }
}
