package com.vitallink.helper;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Reads Health Connect since the last sync (with 6 hours of overlap for late data) or the last 30 days, and uploads
 * it in batches. The server upserts buckets, so overlap and retries never double anything. Runs every 3 hours with
 * a network connection, and when the person presses Sync now. WorkManager runs it on a background thread.
 */
public final class SyncWorker extends Worker {

    private static final String PERIODIC = "vitallink-sync";
    static final Duration FIRST_SYNC = Duration.ofDays(30);
    static final Duration OVERLAP = Duration.ofHours(6);

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static Constraints network() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(SyncWorker.class, 3, TimeUnit.HOURS)
                .setConstraints(network())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    public static UUID now(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(network()).build();
        WorkManager.getInstance(context).enqueue(work);
        return work.getId();
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC);
    }

    static String deviceName() {
        String maker = Build.MANUFACTURER == null || Build.MANUFACTURER.isEmpty()
                ? "Android"
                : Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
        return maker + " " + Build.MODEL;
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Store store = new Store(context);
        String token = store.token();
        if (token == null) {
            return Result.success();
        }
        if (HealthConnectBridge.status(context) != HealthConnectBridge.AVAILABLE) {
            store.lastResult("Health Connect isn't available on this phone.");
            return Result.success();
        }
        Instant to = Instant.now();
        Instant through = store.syncedThrough();
        // Start on a 5-minute boundary: the server replaces a bucket with what's sent, so a read that began partway
        // through one would overwrite its earlier, complete value with part of it.
        Instant from = Instant.ofEpochSecond(
                Buckets.bucketOf(through != null ? through.minus(OVERLAP) : to.minus(FIRST_SYNC)));
        try {
            Reader.Result r = new Reader(context).read(from, to);
            if (r.read.isEmpty()) {
                store.lastResult("Nothing allowed yet: open the app and tap Allow access.");
                return Result.success();
            }
            Set<String> codes = new LinkedHashSet<>();
            for (Buckets.Bucket b : r.buckets) {
                codes.add(b.metric);
            }
            List<String> metrics = new ArrayList<>(codes);
            Api api = new Api(store.server());
            int added = 0;
            for (int i = 0; i < r.buckets.size(); i += Api.MAX_BATCH) {
                List<Buckets.Bucket> batch = r.buckets.subList(i, Math.min(r.buckets.size(), i + Api.MAX_BATCH));
                Api.Uploaded u = api.upload(token, store.deviceId(), deviceName() + " (Health Connect)", metrics, batch);
                added += u.inserted + u.updated;
            }
            store.syncedThrough(to);
            String result;
            if (r.buckets.isEmpty()) {
                result = "Up to date: no new readings.";
            } else if (added == 0) {
                result = "Up to date: " + r.buckets.size() + " readings checked, nothing new.";
            } else {
                result = "Sent " + added + " new or changed readings.";
            }
            if (r.skipped > 0) {
                result += " " + r.skipped + " out-of-range readings were left out.";
            }
            store.lastResult(result);
            return Result.success();
        } catch (Api.Problem e) {
            if (e.status == 401) {
                store.unlink();
                cancel(context);
                store.lastResult("This phone was removed on the website. Link it again with a new code.");
                return Result.success();
            }
            store.lastResult("The server said: " + e.getMessage());
            return e.status == 429 || e.status >= 500 ? Result.retry() : Result.failure();
        } catch (IOException e) {
            store.lastResult("Couldn't reach VitalLink; it will try again.");
            return Result.retry();
        } catch (SecurityException e) {
            store.lastResult("Health Connect refused access: open the app and tap Allow access.");
            return Result.failure();
        } catch (RuntimeException e) {
            // Health Connect's own failures (busy, its read quota used up): say so and let the next run try.
            store.lastResult("Health Connect couldn't be read; it will try again.");
            return Result.retry();
        }
    }
}
