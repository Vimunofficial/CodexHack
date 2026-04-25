package com.codexhackathon.echohome.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

private data class UsageEvent(
    val ledId: Int,
    val name: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val watts: Double
)

private data class UsageTotals(
    var seconds: Double = 0.0,
    var kwh: Double = 0.0,
    var cost: Double = 0.0,
    val zones: MutableMap<Int, ZoneUsageMutable> = mutableMapOf()
)

private data class ZoneUsageMutable(
    val id: Int,
    val name: String,
    val watts: Double,
    var seconds: Double = 0.0,
    var kwh: Double = 0.0,
    var cost: Double = 0.0
)

class UsageTracker(context: Context) {
    private val preferences = context.getSharedPreferences("echo_home_usage", Context.MODE_PRIVATE)
    private val events = mutableListOf<UsageEvent>()
    private val currentStates = mutableMapOf<Int, Boolean>()
    private val liveStarts = mutableMapOf<Int, Long?>()

    init {
        EchoConfig.LedNames.indices.forEach { index ->
            currentStates[index + 1] = false
            liveStarts[index + 1] = null
        }
        load()
    }

    @Synchronized
    fun recordLedStates(leds: List<LedZone>, at: Instant = Instant.now()) {
        leds.forEach { led ->
            recordLedState(led.id, led.name, led.isOn, at)
        }
    }

    @Synchronized
    fun recordLedState(
        ledId: Int,
        name: String = zoneName(ledId),
        isOn: Boolean,
        at: Instant = Instant.now()
    ) {
        if (ledId !in 1..EchoConfig.LedNames.size) return

        val previous = currentStates[ledId] ?: false
        if (previous == isOn) return

        val nowMillis = at.toEpochMilli()
        if (previous) {
            val startedAt = liveStarts[ledId]
            if (startedAt != null && nowMillis > startedAt) {
                events.add(
                    UsageEvent(
                        ledId = ledId,
                        name = name,
                        startedAtMillis = startedAt,
                        endedAtMillis = nowMillis,
                        watts = wattsFor(ledId)
                    )
                )
                save()
            }
        }

        currentStates[ledId] = isOn
        liveStarts[ledId] = if (isOn) nowMillis else null
    }

    @Synchronized
    fun snapshot(now: ZonedDateTime = ZonedDateTime.now(EchoConfig.TimeZone)): UsageSnapshot {
        val dayStart = now.toLocalDate().atStartOfDay(EchoConfig.TimeZone)
        val weekStart = now.toLocalDate()
            .minusDays((now.dayOfWeek.value - 1).toLong())
            .atStartOfDay(EchoConfig.TimeZone)
        val monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay(EchoConfig.TimeZone)
        val nextMonthStart = monthStart.plusMonths(1)
        val last7Start = dayStart.minusDays(6)

        val today = roundedPeriod("today", dayStart, now)
        val week = roundedPeriod("week", weekStart, now)
        val month = roundedPeriod("month", monthStart, now)
        val monthZones = month.zones.sortedByDescending { it.cost }

        return UsageSnapshot(
            generatedAt = Instant.now(),
            currency = EchoConfig.EnergyCurrency,
            ratePerKwh = EchoConfig.EnergyRatePerKwh,
            installedWatts = roundTo(EchoConfig.LedWattages.sum(), 2),
            today = today,
            week = week,
            month = month.copy(zones = monthZones),
            predictedMonth = predictionFor("month", monthStart, nextMonthStart, month, now),
            last7Days = usageSeriesForDays(last7Start, 7, now),
            monthDays = usageSeriesForDays(monthStart, now.dayOfMonth, now),
            zones = monthZones
        )
    }

    private fun usageSeriesForDays(
        firstDayStart: ZonedDateTime,
        dayCount: Int,
        now: ZonedDateTime
    ): List<UsagePoint> {
        return (0 until dayCount).mapNotNull { offset ->
            val dayStart = firstDayStart.plusDays(offset.toLong())
            if (dayStart > now) {
                null
            } else {
                val dayEnd = minOf(dayStart.plusDays(1), now)
                val period = roundedPeriod("day", dayStart, dayEnd)
                UsagePoint(
                    label = "${dayStart.dayOfMonth} ${dayStart.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}",
                    kwh = period.kwh,
                    cost = period.cost,
                    hours = period.hours
                )
            }
        }
    }

    private fun roundedPeriod(
        name: String,
        windowStart: ZonedDateTime,
        windowEnd: ZonedDateTime
    ): UsagePeriod {
        val totals = totalsBetween(windowStart, windowEnd)
        val zones = totals.zones.values.map { zone ->
            ZoneUsage(
                id = zone.id,
                name = zone.name,
                watts = zone.watts,
                seconds = roundTo(zone.seconds, 3),
                hours = roundTo(zone.seconds / 3600.0, 2),
                kwh = roundTo(zone.kwh, 4),
                cost = roundTo(zone.cost, 2)
            )
        }

        return UsagePeriod(
            name = name,
            start = windowStart,
            end = windowEnd,
            hours = roundTo(totals.seconds / 3600.0, 2),
            kwh = roundTo(totals.kwh, 4),
            cost = roundTo(totals.cost, 2),
            zones = zones
        )
    }

    private fun totalsBetween(windowStart: ZonedDateTime, windowEnd: ZonedDateTime): UsageTotals {
        val totals = emptyTotals()
        val windowStartMillis = windowStart.toInstant().toEpochMilli()
        val windowEndMillis = windowEnd.toInstant().toEpochMilli()
        val nowMillis = Instant.now().toEpochMilli()

        events.forEach { event ->
            val seconds = overlapSeconds(
                event.startedAtMillis,
                event.endedAtMillis,
                windowStartMillis,
                windowEndMillis
            )
            addUsage(totals, event.ledId, seconds)
        }

        liveStarts.forEach { (ledId, startedAt) ->
            if (currentStates[ledId] == true && startedAt != null) {
                val seconds = overlapSeconds(startedAt, nowMillis, windowStartMillis, windowEndMillis)
                addUsage(totals, ledId, seconds)
            }
        }

        return totals
    }

    private fun predictionFor(
        period: String,
        fullStart: ZonedDateTime,
        fullEnd: ZonedDateTime,
        currentPeriod: UsagePeriod,
        now: ZonedDateTime
    ): UsagePrediction {
        val elapsedSeconds = max(60.0, java.time.Duration.between(fullStart, now).seconds.toDouble())
        val fullSeconds = max(elapsedSeconds, java.time.Duration.between(fullStart, fullEnd).seconds.toDouble())
        val multiplier = fullSeconds / elapsedSeconds
        return UsagePrediction(
            period = period,
            through = fullEnd,
            kwh = roundTo(currentPeriod.kwh * multiplier, 4),
            cost = roundTo(currentPeriod.cost * multiplier, 2)
        )
    }

    private fun emptyTotals(): UsageTotals {
        val totals = UsageTotals()
        EchoConfig.LedNames.forEachIndexed { index, name ->
            val ledId = index + 1
            totals.zones[ledId] = ZoneUsageMutable(
                id = ledId,
                name = name,
                watts = wattsFor(ledId)
            )
        }
        return totals
    }

    private fun addUsage(totals: UsageTotals, ledId: Int, seconds: Double) {
        if (seconds <= 0.0 || ledId !in 1..EchoConfig.LedNames.size) return

        val watts = wattsFor(ledId)
        val kwh = watts * seconds / 3_600_000.0
        val cost = kwh * EchoConfig.EnergyRatePerKwh
        val zone = totals.zones[ledId] ?: return

        totals.seconds += seconds
        totals.kwh += kwh
        totals.cost += cost

        zone.seconds += seconds
        zone.kwh += kwh
        zone.cost += cost
    }

    private fun overlapSeconds(
        startMillis: Long,
        endMillis: Long,
        windowStartMillis: Long,
        windowEndMillis: Long
    ): Double {
        val latestStart = max(startMillis, windowStartMillis)
        val earliestEnd = min(endMillis, windowEndMillis)
        if (earliestEnd <= latestStart) return 0.0
        return (earliestEnd - latestStart) / 1000.0
    }

    private fun load() {
        val raw = preferences.getString("events", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                events.add(
                    UsageEvent(
                        ledId = item.getInt("led_id"),
                        name = item.optString("name", zoneName(item.getInt("led_id"))),
                        startedAtMillis = item.getLong("started_at"),
                        endedAtMillis = item.getLong("ended_at"),
                        watts = item.optDouble("watts", wattsFor(item.getInt("led_id")))
                    )
                )
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        events.takeLast(5000).forEach { event ->
            array.put(
                JSONObject()
                    .put("led_id", event.ledId)
                    .put("name", event.name)
                    .put("started_at", event.startedAtMillis)
                    .put("ended_at", event.endedAtMillis)
                    .put("duration_seconds", (event.endedAtMillis - event.startedAtMillis) / 1000.0)
                    .put("watts", event.watts)
            )
        }
        preferences.edit().putString("events", array.toString()).apply()
    }

    private fun zoneName(ledId: Int): String =
        EchoConfig.LedNames.getOrElse(ledId - 1) { "LED $ledId" }

    private fun wattsFor(ledId: Int): Double =
        EchoConfig.LedWattages.getOrElse(ledId - 1) { 9.0 }

    private fun roundTo(value: Double, digits: Int): Double {
        val factor = 10.0.pow(digits)
        return round(value * factor) / factor
    }
}
