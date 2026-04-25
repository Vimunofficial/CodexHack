package com.codexhackathon.echohome

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codexhackathon.echohome.ble.EchoBleController
import com.codexhackathon.echohome.data.AssistantAction
import com.codexhackathon.echohome.data.AssistantActionType
import com.codexhackathon.echohome.data.AssistantPlanner
import com.codexhackathon.echohome.data.AssistantServerClient
import com.codexhackathon.echohome.data.AutomationItem
import com.codexhackathon.echohome.data.ChatMessage
import com.codexhackathon.echohome.data.EchoConfig
import com.codexhackathon.echohome.data.EchoHomeUiState
import com.codexhackathon.echohome.data.LedZone
import com.codexhackathon.echohome.data.UsageTracker
import com.codexhackathon.echohome.mqtt.EchoMqttController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class EchoHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.applicationContext.getSharedPreferences("echo_home_settings", Context.MODE_PRIVATE)
    private val usageTracker = UsageTracker(application.applicationContext)
    private val assistantPlanner = AssistantPlanner()
    private val assistantServerClient = AssistantServerClient()
    private val scheduledJobs = mutableMapOf<String, Job>()

    private val _uiState = MutableStateFlow(
        EchoHomeUiState(
            usage = usageTracker.snapshot(),
            assistantServerUrl = settings.getString("assistant_server_url", "") ?: "",
            controlPin = settings.getString("control_pin", "") ?: "",
            assistantMode = if ((settings.getString("assistant_server_url", "") ?: "").isBlank()) "Local" else "Server"
        )
    )
    val uiState: StateFlow<EchoHomeUiState> = _uiState.asStateFlow()

    private val mqttController = EchoMqttController(
        onConnectionChanged = { connected, status ->
            _uiState.update {
                it.copy(
                    mqttConnected = connected,
                    deviceStatus = if (it.bluetoothConnected) it.deviceStatus else status,
                    deviceOnline = it.bluetoothConnected || (connected && it.lastSeen.isRecent())
                )
            }
        },
        onStatusReceived = { leds ->
            handleRemoteStatus(leds, "ESP32 online")
        }
    )

    private val bleController = EchoBleController(
        context = application.applicationContext,
        onConnectionChanged = { connected, scanning, status ->
            _uiState.update {
                it.copy(
                    bluetoothConnected = connected,
                    bluetoothScanning = scanning,
                    deviceOnline = connected || (it.mqttConnected && it.lastSeen.isRecent()),
                    deviceStatus = status
                )
            }
        },
        onStatusReceived = { leds ->
            handleRemoteStatus(leds, "ESP32 online by Bluetooth")
        }
    )

    init {
        connectMqtt()
        viewModelScope.launch {
            while (true) {
                refreshPresenceAndUsage()
                delay(5_000)
            }
        }
    }

    fun connectMqtt() {
        mqttController.connect()
    }

    fun connectBluetooth() {
        bleController.connect()
    }

    fun disconnectBluetooth() {
        bleController.disconnect()
    }

    fun refresh() {
        connectMqtt()
        if (_uiState.value.bluetoothConnected) {
            bleController.readStatus()
        }
        refreshPresenceAndUsage()
        showToast("Status refreshed")
    }

    fun setLed(ledId: Int, isOn: Boolean) {
        viewModelScope.launch {
            setLedInternal(ledId, isOn, showSuccess = true)
        }
    }

    fun setAll(isOn: Boolean) {
        viewModelScope.launch {
            setAllInternal(isOn, showSuccess = true)
        }
    }

    fun sendDisplayMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            showToast("Enter a display message")
            return
        }

        viewModelScope.launch {
            setBusy(true)
            try {
                if (_uiState.value.bluetoothConnected) {
                    showToast("Display text uses MQTT. Bluetooth firmware accepts LED commands only.")
                } else {
                    mqttController.publishDisplayMessage(trimmed)
                    showToast("Display message sent")
                }
            } catch (error: Throwable) {
                showToast(error.message ?: "Display message failed")
            } finally {
                setBusy(false)
            }
        }
    }

    fun askAssistant(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            showToast("Enter a home command")
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(chat = it.chat + ChatMessage("user", trimmed))
            }

            val snapshot = _uiState.value
            if (snapshot.assistantServerUrl.isNotBlank()) {
                runServerAssistant(trimmed, snapshot)
            } else {
                runLocalAssistant(trimmed, snapshot)
            }
        }
    }

    fun setAssistantServerUrl(value: String) {
        val trimmed = value.trim()
        settings.edit().putString("assistant_server_url", trimmed).apply()
        _uiState.update {
            it.copy(
                assistantServerUrl = trimmed,
                assistantMode = if (trimmed.isBlank()) "Local" else it.assistantMode
            )
        }
    }

    fun setControlPin(value: String) {
        val trimmed = value.trim()
        settings.edit().putString("control_pin", trimmed).apply()
        _uiState.update { it.copy(controlPin = trimmed) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private suspend fun runServerAssistant(message: String, snapshot: EchoHomeUiState) {
        setBusy(true)
        try {
            val result = assistantServerClient.chat(
                serverUrl = snapshot.assistantServerUrl,
                controlPin = snapshot.controlPin,
                message = message,
                history = snapshot.chat,
                transport = snapshot.activeTransport
            )
            _uiState.update {
                it.copy(
                    chat = it.chat + ChatMessage("assistant", result.reply),
                    assistantMode = result.mode
                )
            }

            result.leds?.let { leds ->
                handleRemoteStatus(leds, "ESP32 online by ${result.mode}")
            }

            if (_uiState.value.bluetoothConnected) {
                runAssistantActions(result.actions)
            }
        } catch (error: Throwable) {
            val current = _uiState.value
            val fallbackPlan = assistantPlanner.buildPlan(message, current.leds, current.usage)
            val reply = "I could not reach the ChatGPT server, so I used local control. ${fallbackPlan.reply}"
            _uiState.update {
                it.copy(
                    chat = it.chat + ChatMessage("assistant", reply),
                    assistantMode = "Local fallback"
                )
            }
            runAssistantActions(fallbackPlan.actions)
            showToast(error.message ?: "AI server unavailable")
        } finally {
            setBusy(false)
        }
    }

    private suspend fun runLocalAssistant(message: String, snapshot: EchoHomeUiState) {
        val plan = assistantPlanner.buildPlan(message, snapshot.leds, snapshot.usage)
        _uiState.update {
            it.copy(
                chat = it.chat + ChatMessage("assistant", plan.reply),
                assistantMode = "Local"
            )
        }
        runAssistantActions(plan.actions)
    }

    private fun handleRemoteStatus(incomingLeds: List<LedZone>, status: String) {
        val now = Instant.now()
        val current = _uiState.value.leds.associateBy { it.id }
        val byId = incomingLeds.associateBy { it.id }
        val complete = EchoConfig.LedNames.mapIndexed { index, name ->
            val ledId = index + 1
            val incoming = byId[ledId]
            val existing = current[ledId]
            LedZone(
                id = ledId,
                name = incoming?.name ?: existing?.name ?: name,
                pin = incoming?.pin ?: existing?.pin ?: EchoConfig.LedPins.getOrNull(index),
                isOn = incoming?.isOn ?: existing?.isOn ?: false
            )
        }

        usageTracker.recordLedStates(complete, now)
        _uiState.update {
            it.copy(
                leds = complete,
                deviceOnline = true,
                deviceStatus = status,
                lastSeen = now,
                usage = usageTracker.snapshot()
            )
        }
    }

    private suspend fun setLedInternal(ledId: Int, isOn: Boolean, showSuccess: Boolean): Boolean {
        setBusy(true)
        return try {
            if (_uiState.value.bluetoothConnected) {
                val sent = bleController.sendCommand("led:$ledId:${if (isOn) "on" else "off"}")
                if (!sent) error("Bluetooth command failed")
                bleController.readStatus()
            } else {
                mqttController.publishLed(ledId, isOn)
            }
            applyLocalLedState(ledId, isOn)
            if (showSuccess) showToast("${zoneName(ledId)} ${if (isOn) "on" else "off"}")
            true
        } catch (error: Throwable) {
            showToast(error.message ?: "Light command failed")
            false
        } finally {
            setBusy(false)
        }
    }

    private suspend fun setAllInternal(isOn: Boolean, showSuccess: Boolean): Boolean {
        setBusy(true)
        return try {
            if (_uiState.value.bluetoothConnected) {
                val sent = bleController.sendCommand("all:${if (isOn) "on" else "off"}")
                if (!sent) error("Bluetooth command failed")
                bleController.readStatus()
            } else {
                mqttController.publishAll(isOn)
            }
            applyLocalAllStates(isOn)
            if (showSuccess) showToast("All lights ${if (isOn) "on" else "off"}")
            true
        } catch (error: Throwable) {
            showToast(error.message ?: "All lights command failed")
            false
        } finally {
            setBusy(false)
        }
    }

    private fun applyLocalLedState(ledId: Int, isOn: Boolean) {
        val nextLeds = _uiState.value.leds.map { led ->
            if (led.id == ledId) led.copy(isOn = isOn) else led
        }
        usageTracker.recordLedState(ledId, zoneName(ledId), isOn)
        _uiState.update {
            it.copy(
                leds = nextLeds,
                usage = usageTracker.snapshot(),
                lastSeen = Instant.now(),
                deviceOnline = true
            )
        }
    }

    private fun applyLocalAllStates(isOn: Boolean) {
        val nextLeds = _uiState.value.leds.map { led -> led.copy(isOn = isOn) }
        nextLeds.forEach { led ->
            usageTracker.recordLedState(led.id, led.name, led.isOn)
        }
        _uiState.update {
            it.copy(
                leds = nextLeds,
                usage = usageTracker.snapshot(),
                lastSeen = Instant.now(),
                deviceOnline = true
            )
        }
    }

    private suspend fun runAssistantActions(actions: List<AssistantAction>) {
        for (action in actions) {
            when (action.type) {
                AssistantActionType.SetAll -> action.state?.let {
                    setAllInternal(it, showSuccess = false)
                }

                AssistantActionType.SetLed -> if (action.state != null && action.ledId != null) {
                    setLedInternal(action.ledId, action.state, showSuccess = false)
                }

                AssistantActionType.ScheduleAll,
                AssistantActionType.ScheduleLed -> scheduleAssistantAction(action)

                AssistantActionType.Status -> Unit
            }
        }
    }

    private fun scheduleAssistantAction(action: AssistantAction) {
        val runAt = action.runAt ?: run {
            showToast("Schedule time was not understood")
            return
        }
        val desiredState = action.state ?: run {
            showToast("Schedule light state was not understood")
            return
        }
        val now = ZonedDateTime.now(EchoConfig.TimeZone)
        val delayMillis = Duration.between(now, runAt).toMillis()
        if (delayMillis <= 0) {
            showToast("Schedule time has already passed")
            return
        }

        val id = "auto-${UUID.randomUUID()}"
        val item = AutomationItem(
            id = id,
            label = automationLabel(action, desiredState, runAt),
            runAt = runAt,
            status = "scheduled"
        )

        _uiState.update { it.copy(automations = (it.automations + item).takeLast(12)) }
        scheduledJobs[id] = viewModelScope.launch {
            delay(max(0L, delayMillis))
            updateAutomation(id, "running")
            val ok = when (action.type) {
                AssistantActionType.ScheduleAll -> setAllInternal(desiredState, showSuccess = false)
                AssistantActionType.ScheduleLed -> setLedInternal(action.ledId ?: 0, desiredState, showSuccess = false)
                else -> true
            }
            updateAutomation(id, if (ok) "completed" else "failed")
            showToast(if (ok) "Schedule completed" else "Schedule failed")
        }
        showToast("Schedule saved")
    }

    private fun updateAutomation(id: String, status: String, error: String? = null) {
        _uiState.update {
            it.copy(
                automations = it.automations.map { item ->
                    if (item.id == id) item.copy(status = status, error = error) else item
                }
            )
        }
    }

    private fun automationLabel(
        action: AssistantAction,
        desiredState: Boolean,
        runAt: ZonedDateTime
    ): String {
        val stateWord = if (desiredState) "on" else "off"
        val timeText = formatScheduleTime(runAt)
        return when (action.type) {
            AssistantActionType.ScheduleAll -> "Turn all lights $stateWord $timeText"
            AssistantActionType.ScheduleLed -> "Turn ${zoneName(action.ledId ?: 0)} $stateWord $timeText"
            else -> "Lighting action $timeText"
        }
    }

    private fun formatScheduleTime(value: ZonedDateTime): String {
        val now = ZonedDateTime.now(EchoConfig.TimeZone)
        val localValue = value.withZoneSameInstant(EchoConfig.TimeZone)
        val time = localValue.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return when (localValue.toLocalDate()) {
            now.toLocalDate() -> "at $time"
            now.toLocalDate().plusDays(1) -> "tomorrow at $time"
            else -> "on ${localValue.dayOfMonth} ${localValue.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${localValue.year} at $time"
        }
    }

    private fun refreshPresenceAndUsage() {
        _uiState.update { current ->
            val mqttFresh = current.lastSeen.isRecent() && current.mqttConnected
            val online = current.bluetoothConnected || mqttFresh
            val status = when {
                current.bluetoothConnected -> "ESP32 online by Bluetooth"
                !current.mqttConnected -> "MQTT broker disconnected"
                current.lastSeen == null -> "Waiting for ESP32 heartbeat"
                !mqttFresh -> "ESP32 offline; no heartbeat for 15s"
                else -> "ESP32 online"
            }
            current.copy(
                deviceOnline = online,
                deviceStatus = status,
                usage = usageTracker.snapshot()
            )
        }
    }

    private fun setBusy(value: Boolean) {
        _uiState.update { it.copy(commandBusy = value) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    private fun zoneName(ledId: Int): String =
        EchoConfig.LedNames.getOrElse(ledId - 1) { "LED $ledId" }

    private fun Instant?.isRecent(maxAgeSeconds: Long = 15): Boolean {
        if (this == null) return false
        return Duration.between(this, Instant.now()).seconds <= maxAgeSeconds
    }

    override fun onCleared() {
        mqttController.disconnect()
        bleController.disconnect(closeStatus = false)
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
        super.onCleared()
    }
}
