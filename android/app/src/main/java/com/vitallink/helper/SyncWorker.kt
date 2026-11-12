package com.vitallink.helper

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Health Connect since the last sync (with 6 hours of overlap for late data) or the last 30 days, and uploads
 * it in batches. The server upserts buckets, so overlap and retries never double anything. Runs every 3 hours with
 * a network connection, and when the person presses Sync now.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val PERIODIC = "vitallink-sync"
        val FIRST_SYNC: Duration = Duration.ofDays(30)
        val OVERLAP: Duration = Duration.ofHours(6)

        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(NETWORK).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        fun now(context: Context): UUID {
            val work = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(NETWORK).build()
            WorkManager.getInstance(context).enqueue(work)
            return work.id
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        val token = store.token ?: return@withContext Result.success()
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            store.lastResult = "Health Connect isn't available on this phone."
            return@withContext Result.success()
        }
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val to = Instant.now()
        val from = store.syncedThrough?.minus(OVERLAP) ?: to.minus(FIRST_SYNC)
        try {
            val r = Reader(client).read(from, to)
            if (r.read.isEmpty()) {
                store.lastResult = "Nothing allowed yet: open the app and tap Allow access."
                return@withContext Result.success()
            }
            val api = Api(store.server)
            val name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Health Connect)"
            val metrics = r.buckets.map { it.metric }.distinct()
            var added = 0
            for (batch in r.buckets.chunked(Api.MAX_BATCH)) {
                val u = api.upload(token, store.deviceId, name, metrics, batch)
                added += u.inserted + u.updated
            }
            store.syncedThrough = to
            store.lastResult = when {
                r.buckets.isEmpty() -> "Up to date: no new readings."
                added == 0 -> "Up to date: ${r.buckets.size} readings checked, nothing new."
                else -> "Sent $added new or changed readings."
            } + (if (r.skipped > 0) " ${r.skipped} out-of-range readings were left out." else "")
            Result.success()
        } catch (e: Api.Problem) {
            if (e.status == 401) {
                store.unlink()
                cancel(applicationContext)
                store.lastResult = "This phone was removed on the website. Link it again with a new code."
                Result.success()
            } else {
                store.lastResult = "The server said: ${e.message}"
                if (e.status == 429 || e.status >= 500) Result.retry() else Result.failure()
            }
        } catch (e: IOException) {
            store.lastResult = "Couldn't reach VitalLink; it will try again."
            Result.retry()
        } catch (e: SecurityException) {
            store.lastResult = "Health Connect refused access: open the app and tap Allow access."
            Result.failure()
        }
    }
}
