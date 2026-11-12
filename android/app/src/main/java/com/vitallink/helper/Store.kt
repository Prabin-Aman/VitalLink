package com.vitallink.helper

import android.content.Context
import java.time.Instant
import java.util.UUID

/**
 * What the app remembers, in its private preferences: the server, the upload token (it can only upload, and
 * removing the phone on the website voids it), the account's email, and how far syncing has got.
 */
class Store(context: Context) {
    private val p = context.getSharedPreferences("vitallink", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()

    var email: String?
        get() = p.getString("email", null)
        set(v) = p.edit().putString("email", v).apply()

    /** Readings up to here have been sent. */
    var syncedThrough: Instant?
        get() = p.getLong("synced_through", 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        set(v) = p.edit().putLong("synced_through", v?.epochSecond ?: 0L).apply()

    /** The last sync's outcome, shown on the main screen. */
    var lastResult: String?
        get() = p.getString("last_result", null)
        set(v) = p.edit().putString("last_result", v).apply()

    /** Names this install as one device on the website. */
    val deviceId: String
        get() = p.getString("device_id", null) ?: ("health-connect-" + UUID.randomUUID()).also {
            p.edit().putString("device_id", it).apply()
        }

    val linked: Boolean get() = token != null

    fun unlink() {
        p.edit().remove("token").remove("email").remove("synced_through").remove("last_result").apply()
    }
}
