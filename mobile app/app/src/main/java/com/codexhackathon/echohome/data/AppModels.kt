package com.codexhackathon.echohome.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

object EchoConfig {
    const val MQTT_HOST = "broker.emqx.io"
    const val MQTT_PORT = 1883
    const val MQTT_PREFIX = "echo_home_2026"
    const val MQTT_STATUS_TOPIC = "$MQTT_PREFIX/status"
    const val BLE_DEVICE_PREFIX = "EchoHome"

    val BleServiceUuid: UUID = UUID.fromString("7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a10")
    val BleCommandUuid: UUID = UUID.fromString("7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a11")
    val BleStatusUuid: UUID = UUID.fromString("7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a12")

    val TimeZone: ZoneId = ZoneId.of("Asia/Kolkata")
    val LedNames = listOf("Living Room", "Kitchen", "Bedroom", "Front Door", "Garage")
    val LedPins = listOf(19, 4, 16, 17, 18)
    val LedWattages = listOf(9.0, 9.0, 9.0, 9.0, 9.0)

    const val EnergyCurrency = "INR"
    const val EnergyRatePerKwh = 8.0
}

data class LedZone(
    val id: Int,
    val name: String,
    val pin: Int? = null,
    val isOn: Boolean = false
)

enum class GraphMode {
    Usage,
    Cost
}

enum class TransportMode {
    Mqtt,
    Bluetooth
}

data class ZoneUsage(
    val id: Int,
    val name: String,
    val watts: Double,
    val seconds: Double,
    val hours: Double,
    val kwh: Double,
    val cost: Double
)

data class UsagePeriod(
    val name: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val hours: Double,
    val kwh: Double,
    val cost: Double,
    val zones: List<ZoneUsage>
)

data class UsagePrediction(
    val period: String,
    val through: ZonedDateTime,
    val kwh: Double,
    val cost: Double,
    val basis: String = "current usage rate"
)

data class UsagePoint(
    val label: String,
    val kwh: Double,
    val cost: Double,
    val hours: Double
)

data class UsageSnapshot(
    val generatedAt: Instant,
    val currency: String,
    val ratePerKwh: Double,
    val installedWatts: Double,
    val today: UsagePeriod,
    val week: UsagePeriod,
    val month: UsagePeriod,
    val predictedMonth: UsagePrediction,
    val last7Days: List<UsagePoint>,
    val monthDays: List<UsagePoint>,
    val zones: List<ZoneUsage>
) {
    companion object {
        fun empty(now: ZonedDateTime = ZonedDateTime.now(EchoConfig.TimeZone)): UsageSnapshot {
            val zones = EchoConfig.LedNames.mapIndexed { index, name ->
                ZoneUsage(
                    id = index + 1,
                    name = name,
                    watts = EchoConfig.LedWattages.getOrElse(index) { 9.0 },
                    seconds = 0.0,
                    hours = 0.0,
                    kwh = 0.0,
                    cost = 0.0
                )
            }
            val period = UsagePeriod(
                name = "empty",
                start = now,
                end = now,
                hours = 0.0,
                kwh = 0.0,
                cost = 0.0,
                zones = zones
            )
            return UsageSnapshot(
                generatedAt = Instant.now(),
                currency = EchoConfig.EnergyCurrency,
                ratePerKwh = EchoConfig.EnergyRatePerKwh,
                installedWatts = EchoConfig.LedWattages.sum(),
                today = period.copy(name = "today"),
                week = period.copy(name = "week"),
                month = period.copy(name = "month"),
                predictedMonth = UsagePrediction("month", now, 0.0, 0.0),
                last7Days = emptyList(),
                monthDays = emptyList(),
                zones = zones
            )
        }
    }
}

data class AutomationItem(
    val id: String,
    val label: String,
    val runAt: ZonedDateTime,
    val status: String,
    val error: String? = null
)

data class ChatMessage(
    val role: String,
    val content: String
)

enum class AssistantActionType {
    SetAll,
    SetLed,
    ScheduleAll,
    ScheduleLed,
    Status
}

data class AssistantAction(
    val type: AssistantActionType,
    val state: Boolean? = null,
    val ledId: Int? = null,
    val runAt: ZonedDateTime? = null,
    val reason: String = ""
)

data class AssistantPlan(
    val reply: String,
    val actions: List<AssistantAction>
)

data class AssistantServerResult(
    val reply: String,
    val mode: String,
    val actions: List<AssistantAction>,
    val leds: List<LedZone>?
)

data class EchoHomeUiState(
    val leds: List<LedZone> = EchoConfig.LedNames.mapIndexed { index, name ->
        LedZone(
            id = index + 1,
            name = name,
            pin = EchoConfig.LedPins.getOrNull(index),
            isOn = false
        )
    },
    val mqttConnected: Boolean = false,
    val bluetoothConnected: Boolean = false,
    val bluetoothScanning: Boolean = false,
    val deviceOnline: Boolean = false,
    val deviceStatus: String = "Waiting for ESP32 heartbeat",
    val lastSeen: Instant? = null,
    val usage: UsageSnapshot = UsageSnapshot.empty(),
    val automations: List<AutomationItem> = emptyList(),
    val chat: List<ChatMessage> = listOf(
        ChatMessage("assistant", "Chat with Echo Home. I can talk normally and decide light actions when you ask.")
    ),
    val assistantServerUrl: String = "",
    val controlPin: String = "",
    val assistantMode: String = "Local",
    val commandBusy: Boolean = false,
    val toastMessage: String? = null
) {
    val activeTransport: TransportMode
        get() = if (bluetoothConnected) TransportMode.Bluetooth else TransportMode.Mqtt
}
