package com.vitallink.helper;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;
import java.util.UUID;

/**
 * What the app remembers, in its private preferences: the server, the upload token (it can only upload, and
 * removing the phone on the website voids it), the account's email, and how far syncing has got.
 */
public final class Store {

    private final SharedPreferences p;

    public Store(Context context) {
        p = context.getApplicationContext().getSharedPreferences("vitallink", Context.MODE_PRIVATE);
    }

    public String server() {
        String s = p.getString("server", null);
        return s != null ? s : BuildConfig.DEFAULT_SERVER;
    }

    public void server(String v) {
        String s = v.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        p.edit().putString("server", s).apply();
    }

    public String token() {
        return p.getString("token", null);
    }

    public String email() {
        return p.getString("email", null);
    }

    public void linked(String token, String email) {
        p.edit().putString("token", token).putString("email", email).apply();
    }

    public boolean isLinked() {
        return token() != null;
    }

    /** Readings up to here have been sent; null before the first sync. */
    public Instant syncedThrough() {
        long s = p.getLong("synced_through", 0);
        return s > 0 ? Instant.ofEpochSecond(s) : null;
    }

    public void syncedThrough(Instant t) {
        p.edit().putLong("synced_through", t.getEpochSecond()).apply();
    }

    /** The last sync's outcome, shown on the main screen. */
    public String lastResult() {
        return p.getString("last_result", null);
    }

    public void lastResult(String v) {
        p.edit().putString("last_result", v).apply();
    }

    /** Names this install as one device on the website. */
    public String deviceId() {
        String id = p.getString("device_id", null);
        if (id == null) {
            id = "health-connect-" + UUID.randomUUID();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public void unlink() {
        p.edit().remove("token").remove("email").remove("synced_through").remove("last_result").apply();
    }
}
