package com.vitallink.helper

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The three VitalLink calls the app makes. Plain HttpURLConnection and org.json (both part of Android), so the app
 * has no networking library. The token from [link] can only upload readings and say whose account it is.
 */
class Api(private val server: String) {

    class Problem(val status: Int, val code: String, message: String) : IOException(message)

    data class Linked(val token: String, val email: String)

    data class Uploaded(val inserted: Int, val updated: Int, val unchanged: Int)

    fun link(code: String, deviceName: String): Linked {
        val body = JSONObject().put("code", code).put("device_name", deviceName)
        val r = call("POST", "/v1/phone/link", null, body)
        return Linked(r.getString("token"), r.getString("email"))
    }

    fun status(token: String): String = call("GET", "/v1/phone/status", token, null).getString("email")

    /** One batch (at most 5,000 buckets). The idempotency key makes a retried upload count once. */
    fun upload(token: String, deviceId: String, deviceName: String, metrics: List<String>, buckets: List<Buckets.Bucket>): Uploaded {
        val body = ingestBody(UUID.randomUUID(), deviceId, deviceName, metrics, buckets)
        val r = call("POST", "/v1/phone/ingest", token, body)
        return Uploaded(r.getInt("inserted"), r.getInt("updated"), r.getInt("unchanged"))
    }

    companion object {
        const val MAX_BATCH = 5000

        fun ingestBody(key: UUID, deviceId: String, deviceName: String, metrics: List<String>, buckets: List<Buckets.Bucket>): JSONObject {
            val arr = JSONArray()
            for (b in buckets) {
                arr.put(
                    JSONObject()
                        .put("metric", b.metric)
                        .put("start", b.start.toString())
                        .put("value", b.value)
                        .put("samples", b.samples),
                )
            }
            val device = JSONObject()
                .put("external_id", deviceId)
                .put("name", deviceName)
                .put("kind", "phone")
                .put("metrics", JSONArray(metrics))
            return JSONObject()
                .put("idempotency_key", key.toString())
                .put("platform", "health_connect")
                .put("devices", JSONArray().put(device))
                .put("buckets", arr)
        }
    }

    private fun call(method: String, path: String, token: String?, body: JSONObject?): JSONObject {
        val c = URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            // Render's free server can take about a minute to wake up.
            c.connectTimeout = 30_000
            c.readTimeout = 90_000
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Phone $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status >= 400) {
                val e = json.optJSONObject("error")
                throw Problem(status, e?.optString("code") ?: "error", e?.optString("message")?.ifBlank { null } ?: "The server answered $status.")
            }
            return json
        } finally {
            c.disconnect()
        }
    }
}
