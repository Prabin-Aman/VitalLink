package com.vitallink.helper

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketsTest {

    private val t0: Instant = Instant.parse("2026-11-12T09:00:00Z")

    @Test
    fun totalsSpreadOverTheBucketsTheyCover() {
        val b = Buckets()
        // 600 steps over 10 minutes starting at 9:02:30 → 150, 300, 150.
        b.total("steps", "phone", t0.plusSeconds(150), t0.plusSeconds(750), 600.0)
        val out = b.build()
        assertEquals(listOf(150.0, 300.0, 150.0), out.map { it.value })
        assertEquals(t0, out[0].start)
    }

    @Test
    fun totalsTakeTheLargestSourceAndAveragesPoolThem() {
        val b = Buckets()
        b.total("steps", "phone", t0, t0.plusSeconds(300), 100.0)
        b.total("steps", "watch", t0, t0.plusSeconds(300), 120.0)
        b.sample("heart_rate", "phone", t0.plusSeconds(10), 60.0)
        b.sample("heart_rate", "watch", t0.plusSeconds(20), 80.0)
        val out = b.build().associateBy { it.metric }
        assertEquals(120.0, out.getValue("steps").value, 0.0)
        assertEquals(70.0, out.getValue("heart_rate").value, 0.0)
        assertEquals(2, out.getValue("heart_rate").samples)
    }

    @Test
    fun sleepStagesBecomeMinutesAndOutOfRangeValuesAreLeftOut() {
        val b = Buckets()
        b.minutes("ring", t0, t0.plusSeconds(600), "sleep_deep", "sleep_asleep")
        b.sample("heart_rate", "watch", t0, 400.0)
        val out = b.build()
        assertEquals(4, out.size)
        assertTrue(out.all { it.value == 5.0 })
        assertEquals(1, b.skipped)
    }

    @Test
    fun theUploadBodyMatchesTheServer() {
        val bucket = Buckets.Bucket("steps", t0, 42.0, 1)
        val body = Api.ingestBody(UUID(0, 1), "hc-1", "Pixel 9 (Health Connect)", listOf("steps"), listOf(bucket))
        assertEquals("health_connect", body.getString("platform"))
        assertEquals("2026-11-12T09:00:00Z", body.getJSONArray("buckets").getJSONObject(0).getString("start"))
        assertEquals("phone", body.getJSONArray("devices").getJSONObject(0).getString("kind"))
    }
}
