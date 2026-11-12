package com.vitallink.helper;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

public class BucketsTest {

    private final Instant t0 = Instant.parse("2026-11-12T09:00:00Z");

    @Test
    public void totalsSpreadOverTheBucketsTheyCover() {
        Buckets b = new Buckets();
        // 600 steps over 10 minutes starting at 9:02:30 → 150, 300, 150.
        b.total("steps", "phone", t0.plusSeconds(150), t0.plusSeconds(750), 600);
        List<Buckets.Bucket> out = b.build();
        assertEquals(3, out.size());
        assertEquals(150.0, out.get(0).value, 0.0);
        assertEquals(300.0, out.get(1).value, 0.0);
        assertEquals(150.0, out.get(2).value, 0.0);
        assertEquals(t0, out.get(0).start);
    }

    @Test
    public void totalsTakeTheLargestSourceAndAveragesPoolThem() {
        Buckets b = new Buckets();
        b.total("steps", "phone", t0, t0.plusSeconds(300), 100);
        b.total("steps", "watch", t0, t0.plusSeconds(300), 120);
        b.sample("heart_rate", "phone", t0.plusSeconds(10), 60);
        b.sample("heart_rate", "watch", t0.plusSeconds(20), 80);
        Map<String, Buckets.Bucket> out = new HashMap<>();
        for (Buckets.Bucket x : b.build()) {
            out.put(x.metric, x);
        }
        assertEquals(120.0, out.get("steps").value, 0.0);
        assertEquals(70.0, out.get("heart_rate").value, 0.0);
        assertEquals(2, out.get("heart_rate").samples);
    }

    @Test
    public void sleepStagesBecomeMinutesAndOutOfRangeValuesAreLeftOut() {
        Buckets b = new Buckets();
        b.minutes("ring", t0, t0.plusSeconds(600), "sleep_deep", "sleep_asleep");
        b.sample("heart_rate", "watch", t0, 400);
        List<Buckets.Bucket> out = b.build();
        assertEquals(4, out.size());
        for (Buckets.Bucket x : out) {
            assertEquals(5.0, x.value, 0.0);
        }
        assertEquals(1, b.skipped());
    }

    @Test
    public void theUploadBodyMatchesTheServer() throws Exception {
        Buckets.Bucket bucket = new Buckets.Bucket("steps", t0, 42, 1);
        JSONObject body = Api.ingestBody(
                new UUID(0, 1), "hc-1", "Pixel 9 (Health Connect)", Collections.singletonList("steps"),
                Collections.singletonList(bucket));
        assertEquals("health_connect", body.getString("platform"));
        assertEquals("2026-11-12T09:00:00Z", body.getJSONArray("buckets").getJSONObject(0).getString("start"));
        assertEquals("phone", body.getJSONArray("devices").getJSONObject(0).getString("kind"));
    }
}
