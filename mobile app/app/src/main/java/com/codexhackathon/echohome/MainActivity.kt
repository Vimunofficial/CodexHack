package com.codexhackathon.echohome

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexhackathon.echohome.data.AutomationItem
import com.codexhackathon.echohome.data.ChatMessage
import com.codexhackathon.echohome.data.EchoConfig
import com.codexhackathon.echohome.data.EchoHomeUiState
import com.codexhackathon.echohome.data.GraphMode
import com.codexhackathon.echohome.data.LedZone
import com.codexhackathon.echohome.data.UsagePoint
import com.codexhackathon.echohome.data.UsageSnapshot
import com.codexhackathon.echohome.data.ZoneUsage
import com.codexhackathon.echohome.ui.theme.EchoHomeTheme
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: EchoHomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EchoHomeTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {}
                val voiceLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val text = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            viewModel.askAssistant(text)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(requiredRuntimePermissions())
                }

                EchoHomeScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onSetAll = viewModel::setAll,
                    onSetLed = viewModel::setLed,
                    onBluetooth = {
                        if (state.bluetoothConnected) {
                            viewModel.disconnectBluetooth()
                        } else {
                            viewModel.connectBluetooth()
                        }
                    },
                    onAskAssistant = viewModel::askAssistant,
                    onSendDisplay = viewModel::sendDisplayMessage,
                    onAssistantServerUrlChange = viewModel::setAssistantServerUrl,
                    onControlPinChange = viewModel::setControlPin,
                    onVoice = {
                        runCatching {
                            voiceLauncher.launch(voiceIntent())
                        }.onFailure {
                            Toast.makeText(context, "Voice input is not available", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onToastConsumed = viewModel::consumeToast
                )
            }
        }
    }

    private fun voiceIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an Echo Home command")
        }

    private fun requiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.toTypedArray()
    }
}

private enum class MainMenu(val label: String) {
    Assistant("Chat"),
    Lights("Lights"),
    Usage("Usage"),
    Tools("Tools")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EchoHomeScreen(
    state: EchoHomeUiState,
    onRefresh: () -> Unit,
    onSetAll: (Boolean) -> Unit,
    onSetLed: (Int, Boolean) -> Unit,
    onBluetooth: () -> Unit,
    onAskAssistant: (String) -> Unit,
    onSendDisplay: (String) -> Unit,
    onAssistantServerUrlChange: (String) -> Unit,
    onControlPinChange: (String) -> Unit,
    onVoice: () -> Unit,
    onToastConsumed: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showGraphs by rememberSaveable { mutableStateOf(false) }
    var assistantText by rememberSaveable { mutableStateOf("") }
    var displayText by rememberSaveable { mutableStateOf("") }
    var selectedMenuName by rememberSaveable { mutableStateOf(MainMenu.Assistant.name) }
    val selectedMenu = runCatching { MainMenu.valueOf(selectedMenuName) }.getOrDefault(MainMenu.Assistant)

    LaunchedEffect(state.toastMessage) {
        val message = state.toastMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onToastConsumed()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomMenu(
                selected = selectedMenu,
                onSelect = { selectedMenuName = it.name }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header(state = state, onRefresh = onRefresh)

            when (selectedMenu) {
                MainMenu.Assistant -> AssistantHomePage(
                    messages = state.chat,
                    assistantMode = state.assistantMode,
                    serverConfigured = state.assistantServerUrl.isNotBlank(),
                    input = assistantText,
                    onInputChange = { assistantText = it },
                    onPrompt = { assistantText = it },
                    onVoice = onVoice,
                    onSend = {
                        val text = assistantText.trim()
                        assistantText = ""
                        onAskAssistant(text)
                    }
                )

                MainMenu.Lights -> LightsPage(
                    state = state,
                    onSetAll = onSetAll,
                    onSetLed = onSetLed,
                    onBluetooth = onBluetooth
                )

                MainMenu.Usage -> UsagePage(
                    usage = state.usage,
                    onGraphs = { showGraphs = true }
                )

                MainMenu.Tools -> ToolsPage(
                    state = state,
                    value = displayText,
                    onValueChange = { displayText = it },
                    onSend = {
                        val text = displayText.trim()
                        displayText = ""
                        onSendDisplay(text)
                    },
                    onRefresh = onRefresh,
                    onBluetooth = onBluetooth,
                    assistantServerUrl = state.assistantServerUrl,
                    controlPin = state.controlPin,
                    onAssistantServerUrlChange = onAssistantServerUrlChange,
                    onControlPinChange = onControlPinChange
                )
            }
        }
    }

    if (showGraphs) {
        UsageGraphsDialog(
            usage = state.usage,
            onDismiss = { showGraphs = false }
        )
    }
}

@Composable
private fun BottomMenu(selected: MainMenu, onSelect: (MainMenu) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MainMenu.entries.forEach { item ->
                if (item == selected) {
                    Button(
                        onClick = { onSelect(item) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(item) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantHomePage(
    messages: List<ChatMessage>,
    assistantMode: String,
    serverConfigured: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onPrompt: (String) -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit
) {
    SurfacePanel(modifier = Modifier.fillMaxSize(), fillContent = true) {
        SectionTitle(
            "AI Assistant",
            if (serverConfigured) "ChatGPT mode: $assistantMode" else "Local mode: add server URL in Tools"
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(10.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            messages.takeLast(5).forEach { message ->
                ChatBubble(message)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "Living Room on",
                "Status",
                "Month bill",
                "Close at 6 PM"
            ).forEach { label ->
                val prompt = when (label) {
                    "Living Room on" -> "Turn on Living Room"
                    "Status" -> "What lights are on?"
                    "Month bill" -> "What is this month's bill?"
                    else -> "Home closes at 6 PM"
                }
                FilterChip(
                    selected = false,
                    onClick = { onPrompt(prompt) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 3,
            placeholder = { Text("Tell Echo Home what to do") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onVoice,
                modifier = Modifier
                    .height(44.dp)
                    .weight(1f)
            ) {
                Text("Voice")
            }
            Button(
                onClick = onSend,
                modifier = Modifier
                    .height(44.dp)
                    .weight(1f)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun LightsPage(
    state: EchoHomeUiState,
    onSetAll: (Boolean) -> Unit,
    onSetLed: (Int, Boolean) -> Unit,
    onBluetooth: () -> Unit
) {
    SurfacePanel(modifier = Modifier.fillMaxSize(), fillContent = true) {
        SectionTitle("LED Zones", formatLastSeen(state.lastSeen))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSetAll(true) },
                enabled = !state.commandBusy,
                modifier = Modifier
                    .height(42.dp)
                    .weight(1f)
            ) {
                Text("All On")
            }
            OutlinedButton(
                onClick = { onSetAll(false) },
                enabled = !state.commandBusy,
                modifier = Modifier
                    .height(42.dp)
                    .weight(1f)
            ) {
                Text("All Off")
            }
        }
        OutlinedButton(
            onClick = onBluetooth,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        ) {
            Text(
                when {
                    state.bluetoothScanning -> "Scanning for EchoHome"
                    state.bluetoothConnected -> "Disconnect Bluetooth"
                    else -> "Connect Bluetooth"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.leds.forEach { led ->
                CompactLedRow(
                    led = led,
                    enabled = !state.commandBusy,
                    onToggle = { onSetLed(led.id, it) }
                )
            }
        }
    }
}

@Composable
private fun CompactLedRow(led: LedZone, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (led.isOn) Color(0xFFF3FBF7) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (led.isOn) Color(0xFFD99A28) else Color(0xFF9DAAA6))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(led.name, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                led.pin?.let { "Pin $it" } ?: "ESP32",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            if (led.isOn) "ON" else "OFF",
            color = if (led.isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black
        )
        Switch(checked = led.isOn, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
private fun UsagePage(usage: UsageSnapshot, onGraphs: () -> Unit) {
    val topZones = usage.zones.filter { it.kwh > 0.0 }.take(3)
    SurfacePanel(modifier = Modifier.fillMaxSize(), fillContent = true) {
        SectionTitle("Usage and Bill", "Today, week, month")
        ResponsiveGrid(
            items = listOf(
                Triple("Today", formatMoney(usage.today.cost), formatKwh(usage.today.kwh)),
                Triple("Week", formatMoney(usage.week.cost), formatKwh(usage.week.kwh)),
                Triple("Month", formatMoney(usage.month.cost), formatKwh(usage.month.kwh)),
                Triple("Predicted", formatMoney(usage.predictedMonth.cost), formatKwh(usage.predictedMonth.kwh))
            ),
            columnsWhenWide = 2
        ) { card ->
            UsageCard(card.first, card.second, card.third)
        }
        Button(
            onClick = onGraphs,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Text("Open Graphs")
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Top Zones", fontWeight = FontWeight.Black)
            if (topZones.isEmpty()) {
                EmptyState("No usage recorded yet")
            } else {
                topZones.forEach { zone ->
                    ZoneUsageRow(zone)
                }
            }
        }
    }
}

@Composable
private fun ZoneUsageRow(zone: ZoneUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(zone.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatKwh(zone.kwh), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(formatMoney(zone.cost), fontWeight = FontWeight.Black, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToolsPage(
    state: EchoHomeUiState,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onBluetooth: () -> Unit,
    assistantServerUrl: String,
    controlPin: String,
    onAssistantServerUrlChange: (String) -> Unit,
    onControlPinChange: (String) -> Unit
) {
    SurfacePanel(modifier = Modifier.fillMaxSize(), fillContent = true) {
        SectionTitle("Tools", "ChatGPT server, display, schedules")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier
                    .height(42.dp)
                    .weight(1f)
            ) {
                Text("Refresh")
            }
            OutlinedButton(
                onClick = onBluetooth,
                modifier = Modifier
                    .height(42.dp)
                    .weight(1f)
            ) {
                Text(if (state.bluetoothConnected) "BT Off" else "BT On")
            }
        }
        OutlinedTextField(
            value = assistantServerUrl,
            onValueChange = onAssistantServerUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("AI server URL") },
            placeholder = { Text("http://192.168.1.10:5000") }
        )
        OutlinedTextField(
            value = controlPin,
            onValueChange = onControlPinChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Control PIN") },
            placeholder = { Text("Optional") }
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("OLED message") }
        )
        Button(
            onClick = onSend,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Text("Send To OLED")
        }
        AutomationCompactList(
            automations = state.automations,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AutomationCompactList(automations: List<AutomationItem>, modifier: Modifier = Modifier) {
    val visible = automations.filter { it.status in setOf("scheduled", "running", "failed") }.takeLast(4)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Schedules", fontWeight = FontWeight.Black)
        if (visible.isEmpty()) {
            EmptyState("No active schedules")
        } else {
            visible.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(item.label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(item.runAt), fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Header(state: EchoHomeUiState, onRefresh: () -> Unit) {
    SurfacePanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE6F4EE))
                    .border(1.dp, Color(0xFFB9D3C7), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("EH", color = Color(0xFF0F6B44), fontWeight = FontWeight.Black)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Echo Home",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Home Lighting",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.deviceStatus,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusBadge(state.deviceOnline)
            OutlinedButton(
                onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun StatusBadge(online: Boolean) {
    val color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            if (online) "Online" else "Offline",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SurfacePanel(
    modifier: Modifier = Modifier.fillMaxWidth(),
    fillContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        val contentModifier = if (fillContent) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        Column(
            modifier = contentModifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun UsageSummary(usage: UsageSnapshot) {
    val cards = listOf(
        Triple("Today", formatMoney(usage.today.cost), formatKwh(usage.today.kwh)),
        Triple("This Week", formatMoney(usage.week.cost), formatKwh(usage.week.kwh)),
        Triple("This Month", formatMoney(usage.month.cost), formatKwh(usage.month.kwh)),
        Triple("Predicted Month", formatMoney(usage.predictedMonth.cost), "${formatKwh(usage.predictedMonth.kwh)} projected")
    )

    SurfacePanel {
        SectionTitle("Usage and Bill", "Estimated from LED on-time")
        ResponsiveGrid(items = cards, columnsWhenWide = 2) { card ->
            UsageCard(card.first, card.second, card.third)
        }
    }
}

@Composable
private fun UsageCard(title: String, value: String, detail: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoneUsageStrip(zones: List<ZoneUsage>) {
    val visibleZones = zones.filter { it.kwh > 0.0 }.take(5)
    if (visibleZones.isEmpty()) return

    SurfacePanel {
        SectionTitle("Top Zones", "This month's highest usage")
        ResponsiveGrid(items = visibleZones, columnsWhenWide = 2) { zone ->
            UsageCard(zone.name, formatMoney(zone.cost), "${formatKwh(zone.kwh)} - ${zone.hours.format(2)} h")
        }
    }
}

@Composable
private fun LedGrid(
    leds: List<LedZone>,
    commandBusy: Boolean,
    onSetLed: (Int, Boolean) -> Unit
) {
    SurfacePanel {
        ResponsiveGrid(items = leds, columnsWhenWide = 2) { led ->
            LedCard(
                led = led,
                enabled = !commandBusy,
                onToggle = { onSetLed(led.id, it) }
            )
        }
    }
}

@Composable
private fun LedCard(led: LedZone, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val activeColor = Color(0xFFD99A28)
    val mutedColor = Color(0xFF9DAAA6)
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (led.isOn) Color(0xFFF3FBF7) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    led.name,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (led.isOn) "ON" else "OFF",
                    color = if (led.isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(if (led.isOn) Color(0xFFFFF2CF) else Color(0xFFE8EEEC))
                    .border(
                        1.dp,
                        if (led.isOn) activeColor.copy(alpha = 0.68f) else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (led.isOn) activeColor else mutedColor)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    led.pin?.let { "Pin $it" } ?: "ESP32",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Switch(
                    checked = led.isOn,
                    onCheckedChange = onToggle,
                    enabled = enabled
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantPanel(
    messages: List<ChatMessage>,
    input: String,
    onInputChange: (String) -> Unit,
    onPrompt: (String) -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit
) {
    SurfacePanel {
        SectionTitle("AI Assistant", "Home decisions")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            messages.takeLast(8).forEach { message ->
                ChatBubble(message)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "Home closes at 6 PM",
                "Turn on Living Room",
                "What lights are on?",
                "What is this month's bill?",
                "Predict this month's usage"
            ).forEach { prompt ->
                FilterChip(
                    selected = false,
                    onClick = { onPrompt(prompt) },
                    label = { Text(prompt, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            placeholder = { Text("Home closes at 6 PM") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onVoice,
                modifier = Modifier
                    .height(44.dp)
                    .weight(1f)
            ) {
                Text("Voice")
            }
            Button(
                onClick = onSend,
                modifier = Modifier
                    .height(44.dp)
                    .weight(1f)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DisplayPanel(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    SurfacePanel {
        SectionTitle("OLED Display", "Send a short message to the ESP32 screen")
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Welcome home") }
        )
        Button(
            onClick = onSend,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Text("Send Display Message")
        }
    }
}

@Composable
private fun AutomationPanel(automations: List<AutomationItem>) {
    val visible = automations.filter { it.status in setOf("scheduled", "running", "failed") }
    if (visible.isEmpty()) return

    SurfacePanel {
        SectionTitle("Automations", "Scheduled commands")
        visible.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.label, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.status.replaceFirstChar { it.uppercase() },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(formatTime(item.runAt), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun UsageGraphsDialog(usage: UsageSnapshot, onDismiss: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(GraphMode.Usage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Energy and Cost", fontWeight = FontWeight.Black) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TabRow(selectedTabIndex = if (mode == GraphMode.Usage) 0 else 1) {
                    Tab(
                        selected = mode == GraphMode.Usage,
                        onClick = { mode = GraphMode.Usage },
                        text = { Text("Usage") }
                    )
                    Tab(
                        selected = mode == GraphMode.Cost,
                        onClick = { mode = GraphMode.Cost },
                        text = { Text("Cost") }
                    )
                }
                GraphCard("Last 7 Days", graphUnit(mode), mode) {
                    BarChart(points = usage.last7Days, mode = mode)
                }
                GraphCard("This Month", graphUnit(mode), mode) {
                    BarChart(points = usage.monthDays.takeLast(12), mode = mode)
                }
                GraphCard("Zone Breakdown", graphUnit(mode), mode) {
                    ZoneChart(zones = usage.zones, mode = mode)
                }
            }
        }
    )
}

@Composable
private fun GraphCard(title: String, unit: String, mode: GraphMode, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Black)
                Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            content()
            if (mode == GraphMode.Cost) {
                Text(
                    "Cost uses ${EchoConfig.EnergyRatePerKwh.format(2)} ${EchoConfig.EnergyCurrency}/kWh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BarChart(points: List<UsagePoint>, mode: GraphMode) {
    if (points.isEmpty()) {
        Text("No usage yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val maxValue = points.maxOf { graphValue(it, mode) }.coerceAtLeast(0.0001)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        points.forEach { point ->
            val value = graphValue(point, mode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(graphLabel(value, mode), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .fillMaxHeight((value / maxValue).toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(
                    point.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ZoneChart(zones: List<ZoneUsage>, mode: GraphMode) {
    val visible = zones.filter { graphValue(it, mode) > 0.0 }.take(8)
    if (visible.isEmpty()) {
        Text("No zone usage yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val maxValue = visible.maxOf { graphValue(it, mode) }.coerceAtLeast(0.0001)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { zone ->
            val value = graphValue(zone, mode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    zone.name,
                    modifier = Modifier.width(92.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFFE8EEEC))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((value / maxValue).toFloat().coerceIn(0.03f, 1f))
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }
                Text(graphLabel(value, mode), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun <T> ResponsiveGrid(
    items: List<T>,
    columnsWhenWide: Int,
    itemContent: @Composable (T) -> Unit
) {
    BoxWithConstraints {
        val columns = if (maxWidth < 430.dp) 1 else columnsWhenWide
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            itemContent(item)
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun graphValue(point: UsagePoint, mode: GraphMode): Double =
    if (mode == GraphMode.Cost) point.cost else point.kwh

private fun graphValue(zone: ZoneUsage, mode: GraphMode): Double =
    if (mode == GraphMode.Cost) zone.cost else zone.kwh

private fun graphLabel(value: Double, mode: GraphMode): String =
    if (mode == GraphMode.Cost) formatMoney(value) else formatKwh(value)

private fun graphUnit(mode: GraphMode): String =
    if (mode == GraphMode.Cost) EchoConfig.EnergyCurrency else "kWh"

private fun formatMoney(value: Double): String =
    "${EchoConfig.EnergyCurrency} ${value.format(2)}"

private fun formatKwh(value: Double): String =
    "${value.format(4)} kWh"

private fun formatLastSeen(value: Instant?): String {
    if (value == null) return "Waiting for board status"
    val formatter = DateTimeFormatter.ofPattern("dd MMM, h:mm a", Locale.US).withZone(EchoConfig.TimeZone)
    return "Last update ${formatter.format(value)}"
}

private fun formatTime(value: ZonedDateTime): String =
    value.withZoneSameInstant(EchoConfig.TimeZone)
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

private fun Double.format(digits: Int): String =
    "%.${digits}f".format(Locale.US, this)
