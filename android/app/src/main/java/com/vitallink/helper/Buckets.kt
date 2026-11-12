package com.vitallink.helper

import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Turns Health Connect samples into VitalLink's 5-minute buckets, with the same rules as the server's Apple Health
 * import: totals (steps, minutes) spread over the buckets a sample covers and take one source per bucket (the
 * largest, so a phone and a watch counting the same walk don't double it); averages pool every source. Values
 * outside the server's accepted range are left out, because the server rejects a whole batch over one bad bucket.
 *
 * Pure Kotlin, so it runs in JVM unit tests.
 */
class Buckets {

    enum class Agg { SUM, AVG }

    /** Catalogue code → (aggregation, min, max), matching backend health/Catalogue.java. */
    data class Rule(val agg: Agg, val min: Double, val max: Double)

    data class Bucket(val metric: String, val start: Instant, val value: Double, val samples: Int)

    private class Acc {
        var sum = 0.0
        var count = 0
    }

    private data class Key(val metric: String, val epoch: Long, val source: String)

    private val acc = HashMap<Key, Acc>()
    var skipped = 0
        private set

    companion object {
        const val SECONDS = 300L

        val RULES: Map<String, Rule> = mapOf(
            "steps" to Rule(Agg.SUM, 0.0, 5000.0),
            "distance" to Rule(Agg.SUM, 0.0, 6000.0),
            "active_energy" to Rule(Agg.SUM, 0.0, 250.0),
            "flights_climbed" to Rule(Agg.SUM, 0.0, 100.0),
            "heart_rate" to Rule(Agg.AVG, 25.0, 250.0),
            "resting_heart_rate" to Rule(Agg.AVG, 25.0, 150.0),
            "hrv" to Rule(Agg.AVG, 1.0, 300.0),
            "oxygen_saturation" to Rule(Agg.AVG, 50.0, 100.0),
            "respiratory_rate" to Rule(Agg.AVG, 4.0, 60.0),
            "bp_systolic" to Rule(Agg.AVG, 60.0, 260.0),
            "bp_diastolic" to Rule(Agg.AVG, 30.0, 160.0),
            "glucose" to Rule(Agg.AVG, 20.0, 600.0),
            "body_temperature" to Rule(Agg.AVG, 30.0, 45.0),
            "weight" to Rule(Agg.AVG, 20.0, 400.0),
            "body_fat" to Rule(Agg.AVG, 2.0, 75.0),
            "sleep_asleep" to Rule(Agg.SUM, 0.0, 5.0),
            "sleep_deep" to Rule(Agg.SUM, 0.0, 5.0),
            "sleep_core" to Rule(Agg.SUM, 0.0, 5.0),
            "sleep_rem" to Rule(Agg.SUM, 0.0, 5.0),
            "sleep_awake" to Rule(Agg.SUM, 0.0, 5.0),
        )

        fun bucketOf(t: Instant): Long = Math.floorDiv(t.epochSecond, SECONDS) * SECONDS
    }

    /** A total over [start, end): spread over the buckets by overlap. A zero-length total goes to its bucket. */
    fun total(metric: String, source: String, start: Instant, end: Instant, value: Double) {
        if (value.isNaN() || value < 0) return
        val s = start.epochSecond
        val e = end.epochSecond
        if (e <= s) {
            put(metric, bucketOf(start), source, value)
            return
        }
        var b = bucketOf(start)
        while (b < e) {
            val overlap = min(b + SECONDS, e) - max(b, s)
            if (overlap > 0) put(metric, b, source, value * overlap / (e - s))
            b += SECONDS
        }
    }

    /** A point reading (heart rate, weight…): goes to the bucket it falls in. */
    fun sample(metric: String, source: String, at: Instant, value: Double) {
        if (value.isNaN()) return
        put(metric, bucketOf(at), source, value)
    }

    /** Minutes of a sleep stage in each bucket it covers, for every code given (e.g. deep and asleep). */
    fun minutes(source: String, start: Instant, end: Instant, vararg metrics: String) {
        val s = start.epochSecond
        val e = end.epochSecond
        var b = bucketOf(start)
        while (b < e) {
            val overlap = min(b + SECONDS, e) - max(b, s)
            if (overlap > 0) for (m in metrics) put(m, b, source, overlap / 60.0)
            b += SECONDS
        }
    }

    private fun put(metric: String, epoch: Long, source: String, v: Double) {
        val a = acc.getOrPut(Key(metric, epoch, source)) { Acc() }
        a.sum += v
        a.count++
    }

    /** One bucket per (metric, start), sorted by time, ready to upload. */
    fun build(): List<Bucket> {
        val merged = HashMap<Pair<String, Long>, DoubleArray>() // {value, samples}
        for ((k, a) in acc) {
            val rule = RULES[k.metric] ?: continue
            val id = k.metric to k.epoch
            val cur = merged[id]
            if (rule.agg == Agg.SUM) {
                if (cur == null || a.sum > cur[0]) merged[id] = doubleArrayOf(a.sum, a.count.toDouble())
            } else if (cur == null) {
                merged[id] = doubleArrayOf(a.sum, a.count.toDouble())
            } else {
                cur[0] += a.sum
                cur[1] += a.count
            }
        }
        val out = ArrayList<Bucket>(merged.size)
        for ((id, v) in merged) {
            val rule = RULES.getValue(id.first)
            var value = if (rule.agg == Agg.SUM) v[0] else v[0] / v[1]
            value = (value * 1000).roundToLong() / 1000.0
            // Rounding can leave 5.0000001 minutes in a 5-minute bucket.
            if (rule.agg == Agg.SUM && value > rule.max && value < rule.max + 0.01) value = rule.max
            if (value < rule.min || value > rule.max) {
                skipped++
                continue
            }
            out.add(Bucket(id.first, Instant.ofEpochSecond(id.second), value, v[1].toInt().coerceIn(1, 32767)))
        }
        out.sortWith(compareBy<Bucket>({ it.start }, { it.metric }))
        return out
    }
}
