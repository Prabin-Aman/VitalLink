package com.vitallink.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The three VitalLink calls the app makes. Plain HttpURLConnection and org.json (both part of Android), so the app
 * has no networking library. The token from {@link #link} can only upload readings and say whose account it is.
 * Blocking: call it off the main thread.
 */
public final class Api {

    public static final int MAX_BATCH = 5000;

    /** An error answer from the server, with its status and code. */
    public static final class Problem extends IOException {
        public final int status;
        public final String code;

        Problem(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public static final class Linked {
        public final String token;
        public final String email;

        Linked(String token, String email) {
            this.token = token;
            this.email = email;
        }
    }

    public static final class Uploaded {
        public final int inserted;
        public final int updated;
        public final int unchanged;

        Uploaded(int inserted, int updated, int unchanged) {
            this.inserted = inserted;
            this.updated = updated;
            this.unchanged = unchanged;
        }
    }

    private final String server;

    public Api(String server) {
        this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
    }

    public Linked link(String code, String deviceName) throws IOException {
        try {
            JSONObject body = new JSONObject().put("code", code).put("device_name", deviceName);
            JSONObject r = call("POST", "/v1/phone/link", null, body);
            return new Linked(r.getString("token"), r.getString("email"));
        } catch (JSONException e) {
            throw new IOException("The server's answer couldn't be read.", e);
        }
    }

    public String status(String token) throws IOException {
        try {
            return call("GET", "/v1/phone/status", token, null).getString("email");
        } catch (JSONException e) {
            throw new IOException("The server's answer couldn't be read.", e);
        }
    }

    /** One batch (at most 5,000 buckets). The idempotency key makes a retried upload count once. */
    public Uploaded upload(String token, String deviceId, String deviceName, List<String> metrics, List<Buckets.Bucket> buckets)
            throws IOException {
        try {
            JSONObject body = ingestBody(UUID.randomUUID(), deviceId, deviceName, metrics, buckets);
            JSONObject r = call("POST", "/v1/phone/ingest", token, body);
            return new Uploaded(r.getInt("inserted"), r.getInt("updated"), r.getInt("unchanged"));
        } catch (JSONException e) {
            throw new IOException("The server's answer couldn't be read.", e);
        }
    }

    static JSONObject ingestBody(UUID key, String deviceId, String deviceName, List<String> metrics, List<Buckets.Bucket> buckets)
            throws JSONException {
        JSONArray arr = new JSONArray();
        for (Buckets.Bucket b : buckets) {
            arr.put(new JSONObject()
                    .put("metric", b.metric)
                    .put("start", b.start.toString())
                    .put("value", b.value)
                    .put("samples", b.samples));
        }
        JSONArray codes = new JSONArray();
        for (String m : metrics) {
            codes.put(m);
        }
        JSONObject device = new JSONObject()
                .put("external_id", deviceId)
                .put("name", deviceName)
                .put("kind", "phone")
                .put("metrics", codes);
        return new JSONObject()
                .put("idempotency_key", key.toString())
                .put("platform", "health_connect")
                .put("devices", new JSONArray().put(device))
                .put("buckets", arr);
    }

    private JSONObject call(String method, String path, String token, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        try {
            c.setRequestMethod(method);
            // Render's free server can take about a minute to wake up.
            c.setConnectTimeout(30_000);
            c.setReadTimeout(90_000);
            c.setRequestProperty("Accept", "application/json");
            if (token != null) {
                c.setRequestProperty("Authorization", "Phone " + token);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            String text = read(status < 400 ? c.getInputStream() : c.getErrorStream());
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException e) {
                json = new JSONObject();
            }
            if (status >= 400) {
                JSONObject e = json.optJSONObject("error");
                String code = e == null ? "error" : e.optString("code", "error");
                String message = e == null ? "" : e.optString("message", "");
                throw new Problem(status, code, message.isEmpty() ? "The server answered " + status + "." : message);
            }
            return json;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
