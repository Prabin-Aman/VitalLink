package com.vitallink.helper

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads what the person allowed from Health Connect between two instants and fills [Buckets]. Totals (steps,
 * distance, calories, floors) use Health Connect's own 5-minute aggregation, which already removes overlap between
 * apps by the priority the person set; the rest are read record by record. Types without permission are skipped.
 */
class Reader(private val client: HealthConnectClient) {

    companion object {
        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, FloorsClimbedRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
            OxygenSaturationRecord::class, RespiratoryRateRecord::class, BloodPressureRecord::class,
            BloodGlucoseRecord::class, BodyTemperatureRecord::class, WeightRecord::class, BodyFatRecord::class,
            SleepSessionRecord::class,
        )

        /** Every read permission the app asks for, plus background and history reading where available. */
        val READ_PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()
        const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        const val HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

        /** Aggregation windows are asked for a day at a time. */
        private val CHUNK: Duration = Duration.ofDays(1)
    }

    /** Which data types were read, and which were skipped for lack of permission. */
    data class Result(val buckets: List<Buckets.Bucket>, val read: List<String>, val skipped: Int)

    suspend fun read(from: Instant, to: Instant): Result {
        val granted = client.permissionController.getGrantedPermissions()
        fun allowed(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in granted
        val b = Buckets()
        val read = mutableListOf<String>()

        suspend fun total(k: KClass<out Record>, metric: AggregateMetric<*>, code: String, toValue: (Any) -> Double) {
            if (!allowed(k)) return
            var start = from
            while (start.isBefore(to)) {
                val end = minOf(start.plus(CHUNK), to)
                val groups = client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(
                        metrics = setOf(metric),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        timeRangeSlicer = Duration.ofSeconds(Buckets.SECONDS),
                    ),
                )
                for (g in groups) {
                    val v = g.result[metric] ?: continue
                    b.total(code, "health_connect", g.startTime, g.endTime, toValue(v))
                }
                start = end
            }
            read += code
        }

        total(StepsRecord::class, StepsRecord.COUNT_TOTAL, "steps") { (it as Long).toDouble() }
        total(DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL, "distance") {
            (it as androidx.health.connect.client.units.Length).inMeters
        }
        total(ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, "active_energy") {
            (it as androidx.health.connect.client.units.Energy).inKilocalories
        }
        total(FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, "flights_climbed") { it as Double }

        if (allowed(HeartRateRecord::class)) {
            records(HeartRateRecord::class, from, to).forEach { r ->
                val src = r.metadata.dataOrigin.packageName
                r.samples.forEach { s -> b.sample("heart_rate", src, s.time, s.beatsPerMinute.toDouble()) }
            }
            read += "heart_rate"
        }
        if (allowed(RestingHeartRateRecord::class)) {
            records(RestingHeartRateRecord::class, from, to).forEach {
                b.sample("resting_heart_rate", it.metadata.dataOrigin.packageName, it.time, it.beatsPerMinute.toDouble())
            }
            read += "resting_heart_rate"
        }
        if (allowed(HeartRateVariabilityRmssdRecord::class)) {
            records(HeartRateVariabilityRmssdRecord::class, from, to).forEach {
                b.sample("hrv", it.metadata.dataOrigin.packageName, it.time, it.heartRateVariabilityMillis)
            }
            read += "hrv"
        }
        if (allowed(OxygenSaturationRecord::class)) {
            records(OxygenSaturationRecord::class, from, to).forEach {
                b.sample("oxygen_saturation", it.metadata.dataOrigin.packageName, it.time, it.percentage.value)
            }
            read += "oxygen_saturation"
        }
        if (allowed(RespiratoryRateRecord::class)) {
            records(RespiratoryRateRecord::class, from, to).forEach {
                b.sample("respiratory_rate", it.metadata.dataOrigin.packageName, it.time, it.rate)
            }
            read += "respiratory_rate"
        }
        if (allowed(BloodPressureRecord::class)) {
            records(BloodPressureRecord::class, from, to).forEach {
                val src = it.metadata.dataOrigin.packageName
                b.sample("bp_systolic", src, it.time, it.systolic.inMillimetersOfMercury)
                b.sample("bp_diastolic", src, it.time, it.diastolic.inMillimetersOfMercury)
            }
            read += "blood_pressure"
        }
        if (allowed(BloodGlucoseRecord::class)) {
            records(BloodGlucoseRecord::class, from, to).forEach {
                b.sample("glucose", it.metadata.dataOrigin.packageName, it.time, it.level.inMilligramsPerDeciliter)
            }
            read += "glucose"
        }
        if (allowed(BodyTemperatureRecord::class)) {
            records(BodyTemperatureRecord::class, from, to).forEach {
                b.sample("body_temperature", it.metadata.dataOrigin.packageName, it.time, it.temperature.inCelsius)
            }
            read += "body_temperature"
        }
        if (allowed(WeightRecord::class)) {
            records(WeightRecord::class, from, to).forEach {
                b.sample("weight", it.metadata.dataOrigin.packageName, it.time, it.weight.inKilograms)
            }
            read += "weight"
        }
        if (allowed(BodyFatRecord::class)) {
            records(BodyFatRecord::class, from, to).forEach {
                b.sample("body_fat", it.metadata.dataOrigin.packageName, it.time, it.percentage.value)
            }
            read += "body_fat"
        }
        if (allowed(SleepSessionRecord::class)) {
            records(SleepSessionRecord::class, from, to).forEach { r -> sleep(b, r) }
            read += "sleep"
        }
        val buckets = b.build()
        return Result(buckets, read, b.skipped)
    }

    /** Sleep stages as minutes per bucket. A session with no stages counts as asleep throughout. */
    private fun sleep(b: Buckets, r: SleepSessionRecord) {
        val src = r.metadata.dataOrigin.packageName
        if (r.stages.isEmpty()) {
            b.minutes(src, r.startTime, r.endTime, "sleep_asleep")
            return
        }
        for (s in r.stages) {
            when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> b.minutes(src, s.startTime, s.endTime, "sleep_deep", "sleep_asleep")
                SleepSessionRecord.STAGE_TYPE_LIGHT -> b.minutes(src, s.startTime, s.endTime, "sleep_core", "sleep_asleep")
                SleepSessionRecord.STAGE_TYPE_REM -> b.minutes(src, s.startTime, s.endTime, "sleep_rem", "sleep_asleep")
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> b.minutes(src, s.startTime, s.endTime, "sleep_asleep")
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED ->
                    b.minutes(src, s.startTime, s.endTime, "sleep_awake")
                else -> {} // out of bed, unknown: not sleep
            }
        }
    }

    /** Every record of a type in the range, page by page. */
    private suspend fun <T : Record> records(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 1000,
                    pageToken = token,
                ),
            )
            out += res.records
            token = res.pageToken
        } while (token != null)
        return out
    }
}
