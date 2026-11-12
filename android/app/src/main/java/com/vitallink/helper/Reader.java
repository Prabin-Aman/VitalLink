package com.vitallink.helper;

import android.content.Context;
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord;
import androidx.health.connect.client.records.BloodGlucoseRecord;
import androidx.health.connect.client.records.BloodPressureRecord;
import androidx.health.connect.client.records.BodyFatRecord;
import androidx.health.connect.client.records.BodyTemperatureRecord;
import androidx.health.connect.client.records.DistanceRecord;
import androidx.health.connect.client.records.FloorsClimbedRecord;
import androidx.health.connect.client.records.HeartRateRecord;
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord;
import androidx.health.connect.client.records.OxygenSaturationRecord;
import androidx.health.connect.client.records.Record;
import androidx.health.connect.client.records.RespiratoryRateRecord;
import androidx.health.connect.client.records.RestingHeartRateRecord;
import androidx.health.connect.client.records.SleepSessionRecord;
import androidx.health.connect.client.records.StepsRecord;
import androidx.health.connect.client.records.WeightRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads what the person allowed from Health Connect between two instants and fills {@link Buckets}. Totals (steps,
 * distance, calories, floors) use Health Connect's own 5-minute aggregation, which already removes overlap between
 * apps by the priority the person set; the rest are read record by record. Types without permission are skipped.
 * Blocking: call it off the main thread.
 */
public final class Reader {

    static final List<Class<? extends Record>> RECORD_TYPES = Arrays.asList(
            StepsRecord.class, DistanceRecord.class, ActiveCaloriesBurnedRecord.class, FloorsClimbedRecord.class,
            HeartRateRecord.class, RestingHeartRateRecord.class, HeartRateVariabilityRmssdRecord.class,
            OxygenSaturationRecord.class, RespiratoryRateRecord.class, BloodPressureRecord.class,
            BloodGlucoseRecord.class, BodyTemperatureRecord.class, WeightRecord.class, BodyFatRecord.class,
            SleepSessionRecord.class);

    /** Every read permission the app asks for. */
    public static Set<String> readPermissions() {
        Set<String> out = new HashSet<>();
        for (Class<? extends Record> type : RECORD_TYPES) {
            out.add(HealthConnectBridge.readPermission(type));
        }
        return out;
    }

    /** The read permissions plus background and history reading. */
    public static Set<String> allPermissions() {
        Set<String> out = readPermissions();
        out.add(HealthConnectBridge.BACKGROUND);
        out.add(HealthConnectBridge.HISTORY);
        return out;
    }

    /** What one read produced. */
    public static final class Result {
        public final List<Buckets.Bucket> buckets;
        public final List<String> read;
        public final int skipped;

        Result(List<Buckets.Bucket> buckets, List<String> read, int skipped) {
            this.buckets = buckets;
            this.read = read;
            this.skipped = skipped;
        }
    }

    private final Context context;
    private Set<String> granted;

    public Reader(Context context) {
        this.context = context.getApplicationContext();
    }

    private boolean allowed(Class<? extends Record> type) {
        return granted.contains(HealthConnectBridge.readPermission(type));
    }

    private static String source(Record r) {
        return r.getMetadata().getDataOrigin().getPackageName();
    }

    public Result read(Instant from, Instant to) {
        granted = HealthConnectBridge.granted(context);
        Buckets b = new Buckets();
        List<String> read = new ArrayList<>();

        total(b, read, StepsRecord.class, "steps", from, to);
        total(b, read, DistanceRecord.class, "distance", from, to);
        total(b, read, ActiveCaloriesBurnedRecord.class, "active_energy", from, to);
        total(b, read, FloorsClimbedRecord.class, "flights_climbed", from, to);

        if (allowed(HeartRateRecord.class)) {
            for (HeartRateRecord r : HealthConnectBridge.records(context, HeartRateRecord.class, from, to)) {
                for (HeartRateRecord.Sample s : r.getSamples()) {
                    b.sample("heart_rate", source(r), s.getTime(), s.getBeatsPerMinute());
                }
            }
            read.add("heart_rate");
        }
        if (allowed(RestingHeartRateRecord.class)) {
            for (RestingHeartRateRecord r : HealthConnectBridge.records(context, RestingHeartRateRecord.class, from, to)) {
                b.sample("resting_heart_rate", source(r), r.getTime(), r.getBeatsPerMinute());
            }
            read.add("resting_heart_rate");
        }
        if (allowed(HeartRateVariabilityRmssdRecord.class)) {
            for (HeartRateVariabilityRmssdRecord r :
                    HealthConnectBridge.records(context, HeartRateVariabilityRmssdRecord.class, from, to)) {
                b.sample("hrv", source(r), r.getTime(), r.getHeartRateVariabilityMillis());
            }
            read.add("hrv");
        }
        if (allowed(OxygenSaturationRecord.class)) {
            for (OxygenSaturationRecord r : HealthConnectBridge.records(context, OxygenSaturationRecord.class, from, to)) {
                b.sample("oxygen_saturation", source(r), r.getTime(), HealthConnectBridge.percent(r.getPercentage()));
            }
            read.add("oxygen_saturation");
        }
        if (allowed(RespiratoryRateRecord.class)) {
            for (RespiratoryRateRecord r : HealthConnectBridge.records(context, RespiratoryRateRecord.class, from, to)) {
                b.sample("respiratory_rate", source(r), r.getTime(), r.getRate());
            }
            read.add("respiratory_rate");
        }
        if (allowed(BloodPressureRecord.class)) {
            for (BloodPressureRecord r : HealthConnectBridge.records(context, BloodPressureRecord.class, from, to)) {
                b.sample("bp_systolic", source(r), r.getTime(), HealthConnectBridge.mmHg(r.getSystolic()));
                b.sample("bp_diastolic", source(r), r.getTime(), HealthConnectBridge.mmHg(r.getDiastolic()));
            }
            read.add("blood_pressure");
        }
        if (allowed(BloodGlucoseRecord.class)) {
            for (BloodGlucoseRecord r : HealthConnectBridge.records(context, BloodGlucoseRecord.class, from, to)) {
                b.sample("glucose", source(r), r.getTime(), HealthConnectBridge.mgPerDl(r.getLevel()));
            }
            read.add("glucose");
        }
        if (allowed(BodyTemperatureRecord.class)) {
            for (BodyTemperatureRecord r : HealthConnectBridge.records(context, BodyTemperatureRecord.class, from, to)) {
                b.sample("body_temperature", source(r), r.getTime(), HealthConnectBridge.celsius(r.getTemperature()));
            }
            read.add("body_temperature");
        }
        if (allowed(WeightRecord.class)) {
            for (WeightRecord r : HealthConnectBridge.records(context, WeightRecord.class, from, to)) {
                b.sample("weight", source(r), r.getTime(), HealthConnectBridge.kg(r.getWeight()));
            }
            read.add("weight");
        }
        if (allowed(BodyFatRecord.class)) {
            for (BodyFatRecord r : HealthConnectBridge.records(context, BodyFatRecord.class, from, to)) {
                b.sample("body_fat", source(r), r.getTime(), HealthConnectBridge.percent(r.getPercentage()));
            }
            read.add("body_fat");
        }
        if (allowed(SleepSessionRecord.class)) {
            for (SleepSessionRecord r : HealthConnectBridge.records(context, SleepSessionRecord.class, from, to)) {
                sleep(b, r);
            }
            read.add("sleep");
        }
        List<Buckets.Bucket> buckets = b.build();
        return new Result(buckets, read, b.skipped());
    }

    private void total(Buckets b, List<String> read, Class<? extends Record> type, String code, Instant from, Instant to) {
        if (!allowed(type)) {
            return;
        }
        for (HealthConnectBridge.Window w : HealthConnectBridge.totals(context, code, from, to)) {
            b.total(code, "health_connect", w.getStart(), w.getEnd(), w.getValue());
        }
        read.add(code);
    }

    /** Sleep stages as minutes per bucket. A session with no stages counts as asleep throughout. */
    static void sleep(Buckets b, SleepSessionRecord r) {
        String src = source(r);
        if (r.getStages().isEmpty()) {
            b.minutes(src, r.getStartTime(), r.getEndTime(), "sleep_asleep");
            return;
        }
        for (SleepSessionRecord.Stage s : r.getStages()) {
            int stage = s.getStage();
            if (stage == SleepSessionRecord.STAGE_TYPE_DEEP) {
                b.minutes(src, s.getStartTime(), s.getEndTime(), "sleep_deep", "sleep_asleep");
            } else if (stage == SleepSessionRecord.STAGE_TYPE_LIGHT) {
                b.minutes(src, s.getStartTime(), s.getEndTime(), "sleep_core", "sleep_asleep");
            } else if (stage == SleepSessionRecord.STAGE_TYPE_REM) {
                b.minutes(src, s.getStartTime(), s.getEndTime(), "sleep_rem", "sleep_asleep");
            } else if (stage == SleepSessionRecord.STAGE_TYPE_SLEEPING) {
                b.minutes(src, s.getStartTime(), s.getEndTime(), "sleep_asleep");
            } else if (stage == SleepSessionRecord.STAGE_TYPE_AWAKE || stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED) {
                b.minutes(src, s.getStartTime(), s.getEndTime(), "sleep_awake");
            }
            // out of bed, unknown: not sleep
        }
    }
}
