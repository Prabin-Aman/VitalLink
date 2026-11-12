package com.vitallink.helper

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * The only Kotlin in the app. Health Connect's client is Kotlin-only (every read is a suspend function), so this
 * wraps the few calls the Java code needs as ordinary blocking methods. Call the blocking ones off the main thread.
 */
object HealthConnectBridge {

    const val AVAILABLE = HealthConnectClient.SDK_AVAILABLE
    const val UPDATE_REQUIRED = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    const val HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** One 5-minute window from Health Connect's own aggregation (it removes overlap between apps). */
    data class Window(val start: Instant, val end: Instant, val value: Double)

    @JvmStatic
    fun status(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    @JvmStatic
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    @JvmStatic
    fun readPermission(type: Class<out Record>): String = HealthPermission.getReadPermission(type.kotlin)

    @JvmStatic
    fun granted(context: Context): Set<String> = runBlocking {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    }

    /** Every record of a type in [from, to), page by page. */
    @JvmStatic
    fun <T : Record> records(context: Context, type: Class<T>, from: Instant, to: Instant): List<T> = runBlocking {
        val client = HealthConnectClient.getOrCreate(context)
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(type.kotlin, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token),
            )
            out += res.records
            token = res.pageToken
        } while (token != null)
        out
    }

    /**
     * 5-minute totals for "steps", "distance" (m), "active_energy" (kcal) or "flights_climbed", asked for a day at a
     * time. Windows without data are left out.
     */
    @JvmStatic
    fun totals(context: Context, code: String, from: Instant, to: Instant): List<Window> = runBlocking {
        val client = HealthConnectClient.getOrCreate(context)
        val out = ArrayList<Window>()
        var start = from
        while (start.isBefore(to)) {
            val end = minOf(start.plus(Duration.ofDays(1)), to)
            val filter = TimeRangeFilter.between(start, end)
            val slice = Duration.ofMinutes(5)
            when (code) {
                "steps" -> client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), filter, slice),
                ).forEach { g -> g.result[StepsRecord.COUNT_TOTAL]?.let { out += Window(g.startTime, g.endTime, it.toDouble()) } }
                "distance" -> client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(setOf(DistanceRecord.DISTANCE_TOTAL), filter, slice),
                ).forEach { g -> g.result[DistanceRecord.DISTANCE_TOTAL]?.let { out += Window(g.startTime, g.endTime, it.inMeters) } }
                "active_energy" -> client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), filter, slice),
                ).forEach { g ->
                    g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { out += Window(g.startTime, g.endTime, it.inKilocalories) }
                }
                "flights_climbed" -> client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL), filter, slice),
                ).forEach { g -> g.result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let { out += Window(g.startTime, g.endTime, it) } }
                else -> throw IllegalArgumentException("Not a total: $code")
            }
            start = end
        }
        out
    }
}
