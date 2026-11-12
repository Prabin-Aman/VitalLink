package com.vitallink.helper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns Health Connect samples into VitalLink's 5-minute buckets, with the same rules as the server's Apple Health
 * import: totals (steps, minutes) spread over the buckets a sample covers and take one source per bucket (the
 * largest, so a phone and a watch counting the same walk don't double it); averages pool every source. Values
 * outside the server's accepted range are left out, because the server rejects a whole batch over one bad bucket.
 *
 * <p>Plain Java, so it runs in JVM unit tests.
 */
public final class Buckets {

    public static final long SECONDS = 300;

    enum Agg { SUM, AVG }

    /** Aggregation and accepted range, matching backend health/Catalogue.java. */
    static final class Rule {
        final Agg agg;
        final double min;
        final double max;

        Rule(Agg agg, double min, double max) {
            this.agg = agg;
            this.min = min;
            this.max = max;
        }
    }

    /** One upload row. */
    public static final class Bucket {
        public final String metric;
        public final Instant start;
        public final double value;
        public final int samples;

        public Bucket(String metric, Instant start, double value, int samples) {
            this.metric = metric;
            this.start = start;
            this.value = value;
            this.samples = samples;
        }
    }

    // Android 8 has no Map.of, so the table is filled by hand.
    static final Map<String, Rule> RULES = new HashMap<>();

    static {
        RULES.put("steps", new Rule(Agg.SUM, 0, 5000));
        RULES.put("distance", new Rule(Agg.SUM, 0, 6000));
        RULES.put("active_energy", new Rule(Agg.SUM, 0, 250));
        RULES.put("flights_climbed", new Rule(Agg.SUM, 0, 100));
        RULES.put("heart_rate", new Rule(Agg.AVG, 25, 250));
        RULES.put("resting_heart_rate", new Rule(Agg.AVG, 25, 150));
        RULES.put("hrv", new Rule(Agg.AVG, 1, 300));
        RULES.put("oxygen_saturation", new Rule(Agg.AVG, 50, 100));
        RULES.put("respiratory_rate", new Rule(Agg.AVG, 4, 60));
        RULES.put("bp_systolic", new Rule(Agg.AVG, 60, 260));
        RULES.put("bp_diastolic", new Rule(Agg.AVG, 30, 160));
        RULES.put("glucose", new Rule(Agg.AVG, 20, 600));
        RULES.put("body_temperature", new Rule(Agg.AVG, 30, 45));
        RULES.put("weight", new Rule(Agg.AVG, 20, 400));
        RULES.put("body_fat", new Rule(Agg.AVG, 2, 75));
        for (String sleep : new String[] {"sleep_asleep", "sleep_deep", "sleep_core", "sleep_rem", "sleep_awake"}) {
            RULES.put(sleep, new Rule(Agg.SUM, 0, 5));
        }
    }

    /** "metric@epoch@source" keys keep the accumulator a plain map. */
    private static String key(String metric, long epoch, String source) {
        return metric + "@" + epoch + "@" + source;
    }

    private static final class Acc {
        double sum;
        int count;
    }

    private final Map<String, Acc> acc = new HashMap<>();
    private int skipped;

    public static long bucketOf(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), SECONDS) * SECONDS;
    }

    /** Values left out by the last {@link #build()} for being outside the accepted range. */
    public int skipped() {
        return skipped;
    }

    /** A total over [start, end): spread over the buckets by overlap. A zero-length total goes to its bucket. */
    public void total(String metric, String source, Instant start, Instant end, double value) {
        if (Double.isNaN(value) || value < 0) {
            return;
        }
        long s = start.getEpochSecond();
        long e = end.getEpochSecond();
        if (e <= s) {
            put(metric, bucketOf(start), source, value);
            return;
        }
        for (long b = bucketOf(start); b < e; b += SECONDS) {
            long overlap = Math.min(b + SECONDS, e) - Math.max(b, s);
            if (overlap > 0) {
                put(metric, b, source, value * overlap / (e - s));
            }
        }
    }

    /** A point reading (heart rate, weight…): goes to the bucket it falls in. */
    public void sample(String metric, String source, Instant at, double value) {
        if (!Double.isNaN(value)) {
            put(metric, bucketOf(at), source, value);
        }
    }

    /** Minutes of a sleep stage in each bucket it covers, for every code given (e.g. deep and asleep). */
    public void minutes(String source, Instant start, Instant end, String... metrics) {
        long s = start.getEpochSecond();
        long e = end.getEpochSecond();
        for (long b = bucketOf(start); b < e; b += SECONDS) {
            long overlap = Math.min(b + SECONDS, e) - Math.max(b, s);
            if (overlap > 0) {
                for (String m : metrics) {
                    put(m, b, source, overlap / 60.0);
                }
            }
        }
    }

    private void put(String metric, long epoch, String source, double v) {
        Acc a = acc.get(key(metric, epoch, Objects.toString(source, "")));
        if (a == null) {
            a = new Acc();
            acc.put(key(metric, epoch, Objects.toString(source, "")), a);
        }
        a.sum += v;
        a.count++;
    }

    /** One bucket per (metric, start), sorted by time, ready to upload. */
    public List<Bucket> build() {
        Map<String, double[]> merged = new HashMap<>(); // "metric@epoch" → {value, samples}
        for (Map.Entry<String, Acc> en : acc.entrySet()) {
            String[] k = en.getKey().split("@", 3);
            Acc a = en.getValue();
            Rule rule = RULES.get(k[0]);
            if (rule == null) {
                continue;
            }
            String id = k[0] + "@" + k[1];
            double[] cur = merged.get(id);
            if (rule.agg == Agg.SUM) {
                if (cur == null || a.sum > cur[0]) {
                    merged.put(id, new double[] {a.sum, a.count});
                }
            } else if (cur == null) {
                merged.put(id, new double[] {a.sum, a.count});
            } else {
                cur[0] += a.sum;
                cur[1] += a.count;
            }
        }
        skipped = 0;
        List<Bucket> out = new ArrayList<>(merged.size());
        for (Map.Entry<String, double[]> en : merged.entrySet()) {
            int at = en.getKey().lastIndexOf('@');
            String metric = en.getKey().substring(0, at);
            long epoch = Long.parseLong(en.getKey().substring(at + 1));
            Rule rule = RULES.get(metric);
            double[] v = en.getValue();
            double value = rule.agg == Agg.SUM ? v[0] : v[0] / v[1];
            value = Math.round(value * 1000) / 1000.0;
            // Rounding can leave 5.0000001 minutes in a 5-minute bucket.
            if (rule.agg == Agg.SUM && value > rule.max && value < rule.max + 0.01) {
                value = rule.max;
            }
            if (value < rule.min || value > rule.max) {
                skipped++;
                continue;
            }
            int samples = (int) Math.max(1, Math.min(32767, v[1]));
            out.add(new Bucket(metric, Instant.ofEpochSecond(epoch), value, samples));
        }
        Collections.sort(out, new Comparator<Bucket>() {
            @Override
            public int compare(Bucket x, Bucket y) {
                int c = x.start.compareTo(y.start);
                return c != 0 ? c : x.metric.compareTo(y.metric);
            }
        });
        return out;
    }
}
