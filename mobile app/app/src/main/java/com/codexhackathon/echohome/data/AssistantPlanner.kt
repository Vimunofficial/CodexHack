package com.codexhackathon.echohome.data

import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AssistantPlanner {
    fun buildPlan(
        message: String,
        leds: List<LedZone>,
        usage: UsageSnapshot,
        now: ZonedDateTime = ZonedDateTime.now(EchoConfig.TimeZone)
    ): AssistantPlan {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            return AssistantPlan("Enter a home command.", emptyList())
        }

        if (isCapabilityQuery(trimmed)) {
            return AssistantPlan(capabilitiesReply(), emptyList())
        }

        if (isUsageQuery(trimmed)) {
            return AssistantPlan(usageReply(trimmed, usage), emptyList())
        }

        val normalized = trimmed.lowercase()
        if (listOf("status", "which", "what", "show").any { normalized.containsWord(it) }) {
            return AssistantPlan(statusSummary(leds), emptyList())
        }

        var desiredState = parseDesiredLightState(trimmed)
        val contextualIntent = parseContextualLightIntent(trimmed)
        var scheduleTime = parseScheduleTime(trimmed, now)
        var contextualScheduleNote = ""

        if (contextualIntent != null && desiredState == null) {
            desiredState = contextualIntent.first
        }

        if (contextualIntent != null && scheduleTime != null) {
            scheduleTime = scheduleTime.plusMinutes(2)
            contextualScheduleNote = ", two minutes after ${contextualIntent.second}"
        }

        val zoneIds = resolveZoneIds(trimmed)
        val isAllTarget = listOf("home", "house", "all", "everything", "entire", "whole")
            .any { normalized.containsWord(it) }

        if (desiredState == null) {
            return AssistantPlan(
                "I can switch home zones on or off, or schedule them. Try: home closes at 6 pm, turn on Living Room, or switch off Kitchen.",
                emptyList()
            )
        }

        val targetAll = isAllTarget || zoneIds.isEmpty()
        val stateWord = if (desiredState) "on" else "off"

        if (scheduleTime != null) {
            return if (targetAll) {
                AssistantPlan(
                    "Scheduled all home lights to turn $stateWord ${formatScheduleTime(scheduleTime, now)}$contextualScheduleNote.",
                    listOf(
                        AssistantAction(
                            type = AssistantActionType.ScheduleAll,
                            state = desiredState,
                            runAt = scheduleTime,
                            reason = trimmed
                        )
                    )
                )
            } else {
                AssistantPlan(
                    "Scheduled ${zoneIds.joinToString(", ") { zoneName(it) }} to turn $stateWord ${formatScheduleTime(scheduleTime, now)}$contextualScheduleNote.",
                    zoneIds.map { ledId ->
                        AssistantAction(
                            type = AssistantActionType.ScheduleLed,
                            state = desiredState,
                            ledId = ledId,
                            runAt = scheduleTime,
                            reason = trimmed
                        )
                    }
                )
            }
        }

        return if (targetAll) {
            AssistantPlan(
                "Turning $stateWord all home lights.",
                listOf(AssistantAction(AssistantActionType.SetAll, state = desiredState, reason = trimmed))
            )
        } else {
            AssistantPlan(
                "Turning $stateWord ${zoneIds.joinToString(", ") { zoneName(it) }}.",
                zoneIds.map { ledId ->
                    AssistantAction(
                        type = AssistantActionType.SetLed,
                        state = desiredState,
                        ledId = ledId,
                        reason = trimmed
                    )
                }
            )
        }
    }

    private fun isCapabilityQuery(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf(
            "what can you do",
            "what ai can do",
            "what are your features",
            "what are the features",
            "help",
            "commands",
            "capabilities"
        ).any { normalized.contains(it) }
    }

    private fun isUsageQuery(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf(
            "bill",
            "billing",
            "cost",
            "spend",
            "spent",
            "usage",
            "energy",
            "electricity",
            "unit",
            "units",
            "kwh",
            "predict",
            "prediction",
            "forecast",
            "estimate",
            "statistics",
            "stats"
        ).any { normalized.containsWord(it) }
    }

    private fun usageReply(text: String, usage: UsageSnapshot): String {
        val normalized = text.lowercase()
        val period = when {
            listOf("today", "day", "daily").any { normalized.containsWord(it) } -> usage.today
            listOf("week", "weekly").any { normalized.containsWord(it) } -> usage.week
            else -> usage.month
        }
        val periodLabel = if (period.name == "today") "today" else "this ${period.name}"
        val topZones = usage.zones.filter { it.kwh > 0.0 }.take(3)
        val zoneText = if (topZones.isNotEmpty()) {
            " Highest usage zones this month: " + topZones.joinToString(", ") {
                "${it.name} ${formatKwh(it.kwh)}"
            } + "."
        } else {
            ""
        }

        return "Estimated usage for $periodLabel: ${formatKwh(period.kwh)}, ${period.hours.format(2)} light-hours, current bill ${formatMoney(period.cost)}. Projected month bill: ${formatMoney(usage.predictedMonth.cost)} at ${EchoConfig.EnergyRatePerKwh.format(2)} ${EchoConfig.EnergyCurrency}/kWh.$zoneText This is an estimate from on-time and configured wattage, not a meter reading."
    }

    private fun capabilitiesReply(): String =
        "I can control individual zones or all lights, schedule lights, answer status questions, estimate day/week/month energy usage and bill, predict usage and bill from current patterns, and suggest energy-saving actions such as turning off zones when nobody is home."

    private fun statusSummary(leds: List<LedZone>): String {
        val onZones = leds.filter { it.isOn }.map { it.name }
        val offZones = leds.filterNot { it.isOn }.map { it.name }
        if (onZones.isEmpty()) return "All home lights are currently off."
        if (offZones.isEmpty()) return "All home lights are currently on."
        return "On: ${onZones.joinToString(", ")}. Off: ${offZones.joinToString(", ")}."
    }

    private fun parseDesiredLightState(text: String): Boolean? {
        val normalized = text.lowercase()
        val offPatterns = listOf(
            "\\b(turn|switch|put|power)\\s+off\\b",
            "\\blights?\\s+off\\b",
            "\\bleds?\\s+off\\b",
            "\\ball\\s+off\\b",
            "\\boff\\s+(all|lights?|leds?)\\b",
            "\\b(close|closed|closing|shutdown|shut down|power down)\\b"
        )
        val onPatterns = listOf(
            "\\b(turn|switch|put|power)\\s+on\\b",
            "\\blights?\\s+on\\b",
            "\\bleds?\\s+on\\b",
            "\\ball\\s+on\\b",
            "\\bon\\s+(all|lights?|leds?)\\b",
            "\\b(open|opened|start|power up)\\b"
        )

        if (offPatterns.any { Regex(it).containsMatchIn(normalized) } || normalized.containsWord("off")) {
            return false
        }
        if (onPatterns.any { Regex(it).containsMatchIn(normalized) } || normalized.containsWord("on")) {
            return true
        }
        return null
    }

    private fun parseContextualLightIntent(text: String): Pair<Boolean, String>? {
        val normalized = text.lowercase()
        val patterns = listOf(
            "\\bnot\\s+available\\b",
            "\\bnot\\s+at\\s+home\\b",
            "\\bnot\\s+home\\b",
            "\\baway\\s+from\\s+home\\b",
            "\\bi\\s*(am|'m)?\\s*away\\b",
            "\\bi\\s*(am|'m)?\\s*leaving\\b",
            "\\bi\\s*(will|would|am\\s+going\\s+to|plan\\s+to|have\\s+to|need\\s+to)?\\s*leave\\s+(the\\s+)?(home|house)\\b",
            "\\bleave\\s+(the\\s+)?(home|house)\\b",
            "\\bleaving\\s+(the\\s+)?(home|house)\\b",
            "\\bout\\s+of\\s+(home|house)\\b",
            "\\bno\\s+one\\s+(is\\s+)?home\\b",
            "\\bnobody\\s+(is\\s+)?home\\b",
            "\\bempty\\s+(home|house)\\b"
        )
        return if (patterns.any { Regex(it).containsMatchIn(normalized) }) {
            false to "you said you will not be available"
        } else {
            null
        }
    }

    private fun parseScheduleTime(text: String, now: ZonedDateTime): ZonedDateTime? {
        val normalized = text.lowercase()
        val relativeMatch = Regex("\\b(?:in|after)\\s+(\\d{1,3})\\s*(minute|minutes|min|hour|hours|hr|hrs)\\b")
            .find(normalized)
        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLong()
            val unit = relativeMatch.groupValues[2]
            return if (unit.startsWith("hour") || unit.startsWith("hr")) {
                now.plusHours(amount)
            } else {
                now.plusMinutes(amount)
            }
        }

        fun scheduledDateTime(hour: Int, minute: Int): ZonedDateTime {
            var date = now.toLocalDate()
            if (normalized.contains("tomorrow")) {
                date = date.plusDays(1)
            }
            var runAt = ZonedDateTime.of(date, LocalTime.of(hour, minute), EchoConfig.TimeZone)
            if (!normalized.contains("today") && !normalized.contains("tomorrow") && !runAt.isAfter(now)) {
                runAt = runAt.plusDays(1)
            }
            return runAt
        }

        val clockMatch = Regex("\\b(\\d{1,2})(?:\\s*:\\s*(\\d{1,2}))?\\s*(am|pm)\\b").find(normalized)
        if (clockMatch != null) {
            var hour = clockMatch.groupValues[1].toInt()
            val minute = clockMatch.groupValues[2].ifBlank { "0" }.toInt()
            val meridiem = clockMatch.groupValues[3]
            if (hour !in 1..12 || minute !in 0..59) return null
            if (meridiem == "pm" && hour != 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            return scheduledDateTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        }

        val clock24Match = Regex("\\b(?:at|by|around|on)\\s+([01]?\\d|2[0-3])\\s*:\\s*([0-5]?\\d)\\b").find(normalized)
        if (clock24Match != null) {
            return scheduledDateTime(
                clock24Match.groupValues[1].toInt(),
                clock24Match.groupValues[2].toInt()
            )
        }

        val bareHourMatch = Regex("\\b(?:at|by|around)\\s+(\\d{1,2})(?:\\s*o'?clock)?\\b").find(normalized)
        if (bareHourMatch != null) {
            val hour = bareHourMatch.groupValues[1].toInt()
            if (hour !in 1..23) return null
            val candidates = buildList {
                add(hour)
                if (hour <= 11) add(hour + 12)
            }.map { scheduledDateTime(it, 0) }
            return candidates.minByOrNull { java.time.Duration.between(now, it).seconds }
        }

        return null
    }

    private fun resolveZoneIds(text: String): List<Int> {
        val normalized = text.lowercase()
        return zoneAliases()
            .filter { (alias, _) -> Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(normalized) }
            .map { it.value }
            .distinct()
    }

    private fun zoneAliases(): Map<String, Int> {
        val numberWords = listOf("one", "two", "three", "four", "five")
        val aliases = linkedMapOf<String, Int>()

        EchoConfig.LedNames.forEachIndexed { index, name ->
            val ledId = index + 1
            val normalizedName = name.lowercase()
            aliases[normalizedName] = ledId
            aliases["led $ledId"] = ledId
            aliases["light $ledId"] = ledId
            aliases["zone $ledId"] = ledId
            aliases["room $ledId"] = ledId
            if (index < numberWords.size) {
                aliases["led ${numberWords[index]}"] = ledId
                aliases["light ${numberWords[index]}"] = ledId
                aliases["zone ${numberWords[index]}"] = ledId
                aliases["room ${numberWords[index]}"] = ledId
            }
            Regex("[a-z0-9]+").findAll(normalizedName).forEach { match ->
                val token = match.value
                if (token.length >= 4) {
                    aliases[token] = ledId
                }
            }
        }

        aliases["gate"] = 1
        aliases["living"] = 1
        aliases["hall"] = 1
        aliases["kitchen"] = 2
        aliases["bedroom"] = 3
        aliases["bed"] = 3
        aliases["front"] = 4
        aliases["door"] = 4
        aliases["porch"] = 4
        aliases["garage"] = 5
        aliases["parking"] = 5

        return aliases
    }

    private fun zoneName(ledId: Int): String =
        EchoConfig.LedNames.getOrElse(ledId - 1) { "LED $ledId" }

    private fun formatScheduleTime(value: ZonedDateTime, now: ZonedDateTime): String {
        val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val localValue = value.withZoneSameInstant(EchoConfig.TimeZone)
        return when (localValue.toLocalDate()) {
            now.toLocalDate() -> "at ${localValue.format(formatter)}"
            now.toLocalDate().plusDays(1) -> "tomorrow at ${localValue.format(formatter)}"
            else -> "on ${localValue.dayOfMonth} ${localValue.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${localValue.year} at ${localValue.format(formatter)}"
        }
    }

    private fun formatMoney(value: Double): String =
        "${EchoConfig.EnergyCurrency} ${value.format(2)}"

    private fun formatKwh(value: Double): String =
        "${value.format(4)} kWh"

    private fun Double.format(digits: Int): String =
        "%.${digits}f".format(Locale.US, this)

    private fun String.containsWord(word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(this)
}
