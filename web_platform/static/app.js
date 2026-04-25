const config = window.APP_CONFIG || {};
const ledNames = config.ledNames || [];
const state = {
  leds: Array.from({ length: config.ledCount || 5 }, (_, index) => ({
    id: index + 1,
    name: ledNames[index] || `Home Zone ${index + 1}`,
    pin: null,
    state: false,
  })),
  mqtt_connected: false,
  device_online: false,
  device_status: "Waiting for ESP32 heartbeat",
  last_seen: null,
  automations: [],
  usage: null,
};

const ledGrid = document.querySelector("#ledGrid");
const connectionBadge = document.querySelector("#connectionBadge");
const connectionText = document.querySelector("#connectionText");
const lastSeenText = document.querySelector("#lastSeenText");
const statusText = document.querySelector("#statusText");
const toast = document.querySelector("#toast");
const pinField = document.querySelector("#pinField");
const pinInput = document.querySelector("#pinInput");
const bluetoothConnectButton = document.querySelector("#bluetoothConnectButton");
const usageGraphsButton = document.querySelector("#usageGraphsButton");
const bluetoothStatusText = document.querySelector("#bluetoothStatusText");
const assistantMessages = document.querySelector("#assistantMessages");
const assistantForm = document.querySelector("#assistantForm");
const assistantInput = document.querySelector("#assistantInput");
const automationList = document.querySelector("#automationList");
const voiceCommandButton = document.querySelector("#voiceCommandButton");
const voiceCommandLabel = document.querySelector("#voiceCommandLabel");
const voiceReplyButton = document.querySelector("#voiceReplyButton");
const voiceReplyLabel = document.querySelector("#voiceReplyLabel");
const voiceStatusText = document.querySelector("#voiceStatusText");
const todayBillText = document.querySelector("#todayBillText");
const todayUsageText = document.querySelector("#todayUsageText");
const weekBillText = document.querySelector("#weekBillText");
const weekUsageText = document.querySelector("#weekUsageText");
const monthBillText = document.querySelector("#monthBillText");
const monthUsageText = document.querySelector("#monthUsageText");
const predictedMonthBillText = document.querySelector("#predictedMonthBillText");
const predictionBasisText = document.querySelector("#predictionBasisText");
const zoneUsageList = document.querySelector("#zoneUsageList");
const usageGraphDrawer = document.querySelector("#usageGraphDrawer");
const closeGraphsButton = document.querySelector("#closeGraphsButton");
const weeklyUsageChart = document.querySelector("#weeklyUsageChart");
const monthlyUsageChart = document.querySelector("#monthlyUsageChart");
const zoneUsageChart = document.querySelector("#zoneUsageChart");
const weeklyGraphUnit = document.querySelector("#weeklyGraphUnit");
const monthlyGraphUnit = document.querySelector("#monthlyGraphUnit");
const zoneGraphUnit = document.querySelector("#zoneGraphUnit");

let toastTimer = null;
let chatHistory = [];
let bluetoothDevice = null;
let bluetoothCommandCharacteristic = null;
let bluetoothStatusCharacteristic = null;
let bluetoothScheduleCounter = 0;
let bluetoothAutomations = [];
let graphMode = "usage";
let speechRecognition = null;
let isVoiceListening = false;
let pendingVoiceCommand = "";
let voiceRepliesEnabled = localStorage.getItem("voiceRepliesEnabled") === "true";

const BLE_SERVICE_UUID = "7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a10";
const BLE_COMMAND_UUID = "7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a11";
const BLE_STATUS_UUID = "7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a12";

function controlPin() {
  return localStorage.getItem("controlPin") || "";
}

function setControlPin(value) {
  localStorage.setItem("controlPin", value);
}

function setVoiceRepliesEnabled(value) {
  voiceRepliesEnabled = value;
  localStorage.setItem("voiceRepliesEnabled", value ? "true" : "false");
  renderVoiceControls();
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("visible"), 2600);
}

function headers() {
  const requestHeaders = {
    "Content-Type": "application/json",
  };

  const pin = controlPin();
  if (pin) {
    requestHeaders["X-Control-Pin"] = pin;
  }

  return requestHeaders;
}

async function postJson(url, payload) {
  const response = await fetch(url, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify(payload),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.ok === false) {
    throw new Error(body.error || "Command failed");
  }

  return body;
}

async function fetchStatus() {
  const response = await fetch("/api/status");
  if (!response.ok) {
    throw new Error("Status unavailable");
  }
  applyState(await response.json());
}

function formatTime(value) {
  if (!value) {
    return "Waiting for board status";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Last update received";
  }

  return `Last update ${date.toLocaleString()}`;
}

function formatMoney(value, currency) {
  const amount = Number(value || 0);
  return `${currency || "INR"} ${amount.toFixed(2)}`;
}

function formatKwh(value) {
  const amount = Number(value || 0);
  return `${amount.toFixed(4)} kWh`;
}

function graphValue(item) {
  return graphMode === "cost" ? Number(item.cost || 0) : Number(item.kwh || 0);
}

function graphValueLabel(value, currency) {
  return graphMode === "cost" ? formatMoney(value, currency) : formatKwh(value);
}

function graphUnitLabel(currency) {
  return graphMode === "cost" ? currency || "INR" : "kWh";
}

function speechRecognitionConstructor() {
  return window.SpeechRecognition || window.webkitSpeechRecognition || null;
}

function speechRecognitionSupported() {
  return Boolean(speechRecognitionConstructor());
}

function speechSynthesisSupported() {
  return Boolean(window.speechSynthesis && window.SpeechSynthesisUtterance);
}

function setVoiceStatus(message) {
  voiceStatusText.textContent = message;
}

function renderVoiceControls() {
  const recognitionSupported = speechRecognitionSupported();
  const synthesisSupported = speechSynthesisSupported();

  voiceCommandButton.disabled = !recognitionSupported;
  voiceReplyButton.disabled = !synthesisSupported;
  voiceCommandButton.classList.toggle("active", isVoiceListening);
  voiceReplyButton.classList.toggle("active", voiceRepliesEnabled);
  voiceCommandLabel.textContent = isVoiceListening ? "Listening" : "Voice";
  voiceReplyLabel.textContent = voiceRepliesEnabled ? "Speak On" : "Speak Off";

  if (!recognitionSupported) {
    setVoiceStatus("Voice input unavailable");
  } else if (!synthesisSupported) {
    setVoiceStatus("Voice replies unavailable");
  } else if (!isVoiceListening) {
    setVoiceStatus("Voice idle");
  }
}

function ensureSpeechRecognition() {
  if (speechRecognition || !speechRecognitionSupported()) {
    return speechRecognition;
  }

  const Recognition = speechRecognitionConstructor();
  speechRecognition = new Recognition();
  speechRecognition.lang = "en-IN";
  speechRecognition.continuous = false;
  speechRecognition.interimResults = true;
  speechRecognition.maxAlternatives = 1;

  speechRecognition.onstart = () => {
    isVoiceListening = true;
    pendingVoiceCommand = "";
    setVoiceStatus("Listening...");
    renderVoiceControls();
  };

  speechRecognition.onresult = (event) => {
    let interimText = "";
    let finalText = "";

    for (let index = event.resultIndex; index < event.results.length; index += 1) {
      const transcript = event.results[index][0].transcript.trim();
      if (event.results[index].isFinal) {
        finalText += transcript;
      } else {
        interimText += transcript;
      }
    }

    if (finalText) {
      pendingVoiceCommand = finalText;
      assistantInput.value = finalText;
      setVoiceStatus("Command captured");
      speechRecognition.stop();
    } else if (interimText) {
      assistantInput.value = interimText;
    }
  };

  speechRecognition.onerror = (event) => {
    pendingVoiceCommand = "";
    isVoiceListening = false;
    setVoiceStatus(event.error === "not-allowed" ? "Microphone blocked" : "Voice input failed");
    renderVoiceControls();
  };

  speechRecognition.onend = () => {
    const command = pendingVoiceCommand.trim();
    pendingVoiceCommand = "";
    isVoiceListening = false;
    renderVoiceControls();

    if (command) {
      assistantInput.value = "";
      askAssistant(command, { speakReply: true });
    }
  };

  return speechRecognition;
}

function toggleVoiceListening() {
  if (!speechRecognitionSupported()) {
    showToast("Voice input is not supported in this browser");
    return;
  }

  const recognition = ensureSpeechRecognition();
  if (isVoiceListening) {
    pendingVoiceCommand = "";
    recognition.stop();
    return;
  }

  try {
    recognition.start();
  } catch {
    showToast("Voice input is already starting");
  }
}

function speakAssistantReply(text, force = false) {
  if (!force && !voiceRepliesEnabled) {
    return;
  }

  if (!speechSynthesisSupported()) {
    showToast("Spoken replies are not supported in this browser");
    return;
  }

  window.speechSynthesis.cancel();
  const utterance = new window.SpeechSynthesisUtterance(text);
  utterance.lang = "en-IN";
  utterance.rate = 0.95;
  utterance.pitch = 1;
  window.speechSynthesis.speak(utterance);
}

function applyState(nextState) {
  Object.assign(state, nextState);

  if (Array.isArray(nextState.leds)) {
    state.leds = nextState.leds.map((led, index) => ({
      ...(state.leds[index] || {}),
      ...led,
      name: led.name || ledNames[index] || (state.leds[index] && state.leds[index].name) || `Home Zone ${index + 1}`,
    }));
  }

  if (Array.isArray(nextState.automations)) {
    state.automations = nextState.automations;
  }

  render();
}

function isBluetoothConnected() {
  return Boolean(bluetoothCommandCharacteristic && bluetoothDevice && bluetoothDevice.gatt.connected);
}

function renderBluetoothStatus() {
  if (!bluetoothStatusText) return;
  bluetoothStatusText.textContent = isBluetoothConnected() ? "Bluetooth connected" : "Bluetooth idle";
}

function updateLocalLedState(ledId, desiredState) {
  state.leds = state.leds.map((led) => (led.id === ledId ? { ...led, state: desiredState } : led));
  render();
}

function updateLocalAllStates(desiredState) {
  state.leds = state.leds.map((led) => ({ ...led, state: desiredState }));
  render();
}

async function sendBluetoothCommand(command) {
  if (!isBluetoothConnected()) {
    throw new Error("Bluetooth is not connected");
  }

  const payload = new TextEncoder().encode(command);
  const properties = bluetoothCommandCharacteristic.properties || {};
  if (properties.write && bluetoothCommandCharacteristic.writeValueWithResponse) {
    await bluetoothCommandCharacteristic.writeValueWithResponse(payload);
  } else if (properties.writeWithoutResponse && bluetoothCommandCharacteristic.writeValueWithoutResponse) {
    await bluetoothCommandCharacteristic.writeValueWithoutResponse(payload);
  } else {
    await bluetoothCommandCharacteristic.writeValue(payload);
  }

  if (bluetoothStatusCharacteristic) {
    try {
      const statusValue = await bluetoothStatusCharacteristic.readValue();
      handleBluetoothStatus({ target: { value: statusValue } });
    } catch {
      // Notifications will still refresh the UI when the ESP32 publishes status.
    }
  }
}

function handleBluetoothStatus(event) {
  const text = new TextDecoder().decode(event.target.value);
  try {
    const parsed = JSON.parse(text);
    applyState({
      ...parsed,
      device_online: true,
      device_status: "ESP32 online by Bluetooth",
      last_seen: new Date().toISOString(),
    });
  } catch {
    // Ignore partial or diagnostic BLE status payloads.
  }
}

async function connectBluetooth() {
  if (!navigator.bluetooth) {
    showToast("Web Bluetooth is not supported in this browser");
    return;
  }

  try {
    bluetoothStatusText.textContent = "Bluetooth pairing";
    bluetoothDevice = await navigator.bluetooth.requestDevice({
      filters: [{ namePrefix: "EchoHome" }],
      optionalServices: [BLE_SERVICE_UUID],
    });
    bluetoothDevice.addEventListener("gattserverdisconnected", () => {
      bluetoothCommandCharacteristic = null;
      bluetoothStatusCharacteristic = null;
      renderBluetoothStatus();
      showToast("Bluetooth disconnected");
    });

    const server = await bluetoothDevice.gatt.connect();
    const service = await server.getPrimaryService(BLE_SERVICE_UUID);
    bluetoothCommandCharacteristic = await service.getCharacteristic(BLE_COMMAND_UUID);
    bluetoothStatusCharacteristic = await service.getCharacteristic(BLE_STATUS_UUID);
    bluetoothStatusCharacteristic.addEventListener("characteristicvaluechanged", handleBluetoothStatus);
    await bluetoothStatusCharacteristic.startNotifications();
    const statusValue = await bluetoothStatusCharacteristic.readValue();
    handleBluetoothStatus({ target: { value: statusValue } });
    renderBluetoothStatus();
    showToast("Bluetooth connected");
  } catch (error) {
    bluetoothCommandCharacteristic = null;
    bluetoothStatusCharacteristic = null;
    renderBluetoothStatus();
    showToast(error.message || "Bluetooth connection failed");
  }
}

function renderConnection() {
  const deviceOnline = Boolean(state.device_online || isBluetoothConnected());
  const detail = isBluetoothConnected()
    ? "ESP32 online by Bluetooth"
    : state.device_status || state.last_error || "Waiting for ESP32 heartbeat";

  connectionBadge.classList.toggle("online", deviceOnline);
  connectionBadge.classList.toggle("offline", !deviceOnline);
  connectionText.textContent = deviceOnline ? "Device Online" : "Device Offline";
  statusText.textContent = detail;
}

function renderMeta() {
  lastSeenText.textContent = formatTime(state.last_seen);
}

function renderLedCard(led) {
  const card = document.createElement("article");
  card.className = `led-card ${led.state ? "is-on" : ""}`;

  const stateText = led.state ? "ON" : "OFF";
  const zoneName = led.name || ledNames[led.id - 1] || `LED ${led.id}`;

  card.innerHTML = `
    <div class="led-card-header">
      <span class="led-title">${zoneName}</span>
      <span class="state-label">${stateText}</span>
    </div>
    <div class="led-visual" aria-hidden="true">
      <div class="led-ring"></div>
    </div>
    <div class="switch-row">
      <label class="toggle">
        <input type="checkbox" ${led.state ? "checked" : ""} aria-label="Toggle LED ${led.id}">
        <span class="slider"></span>
      </label>
    </div>
  `;

  const input = card.querySelector("input");
  input.addEventListener("change", async () => {
    const desiredState = input.checked;
    input.disabled = true;
    try {
      await setLed(led.id, desiredState);
    } catch (error) {
      input.checked = !desiredState;
      showToast(error.message);
    } finally {
      input.disabled = false;
    }
  });

  return card;
}

function renderLeds() {
  ledGrid.replaceChildren(...state.leds.map(renderLedCard));
}

function renderAutomations() {
  if (!automationList) {
    return;
  }

  const scheduled = [...(state.automations || []), ...bluetoothAutomations].filter((item) => item.status === "scheduled");

  if (!scheduled.length) {
    automationList.replaceChildren();
    return;
  }

  automationList.replaceChildren(
    ...scheduled.map((item) => {
      const element = document.createElement("div");
      element.className = "automation-item";
      element.innerHTML = `<strong>${item.label}</strong><span>${new Date(item.run_at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</span>`;
      return element;
    })
  );
}

function renderUsage() {
  const usage = state.usage;
  if (!usage || !usage.periods || !usage.predictions) {
    return;
  }

  const currency = usage.currency || "INR";
  const today = usage.periods.today || {};
  const week = usage.periods.week || {};
  const month = usage.periods.month || {};
  const monthPrediction = usage.predictions.month || {};

  todayBillText.textContent = formatMoney(today.cost, currency);
  todayUsageText.textContent = formatKwh(today.kwh);
  weekBillText.textContent = formatMoney(week.cost, currency);
  weekUsageText.textContent = formatKwh(week.kwh);
  monthBillText.textContent = formatMoney(month.cost, currency);
  monthUsageText.textContent = formatKwh(month.kwh);
  predictedMonthBillText.textContent = formatMoney(monthPrediction.cost, currency);
  predictionBasisText.textContent = `${formatKwh(monthPrediction.kwh)} projected`;

  const zones = (usage.zones || []).filter((zone) => Number(zone.kwh || 0) > 0).slice(0, 5);
  if (!zones.length) {
    zoneUsageList.replaceChildren();
    return;
  }

  zoneUsageList.replaceChildren(
    ...zones.map((zone) => {
      const item = document.createElement("div");
      item.className = "zone-usage-item";
      item.innerHTML = `
        <span>${zone.name}</span>
        <strong>${formatMoney(zone.cost, currency)}</strong>
        <small>${formatKwh(zone.kwh)} - ${Number(zone.hours || 0).toFixed(2)} h</small>
      `;
      return item;
    })
  );

  renderGraphs();
}

function renderBarChart(container, points, currency) {
  if (!container) {
    return;
  }

  const values = points.map(graphValue);
  const maxValue = Math.max(...values, 0.0001);

  container.replaceChildren(
    ...points.map((point) => {
      const value = graphValue(point);
      const item = document.createElement("div");
      item.className = "bar-item";
      item.innerHTML = `
        <div class="bar-track">
          <div class="bar-fill"></div>
        </div>
        <strong>${graphValueLabel(value, currency)}</strong>
        <span>${point.label}</span>
      `;
      const fill = item.querySelector(".bar-fill");
      fill.style.height = `${Math.max(3, (value / maxValue) * 100)}%`;
      return item;
    })
  );
}

function renderZoneChart(container, zones, currency) {
  if (!container) {
    return;
  }

  const visibleZones = zones.filter((zone) => graphValue(zone) > 0).slice(0, 8);
  if (!visibleZones.length) {
    container.replaceChildren();
    return;
  }

  const maxValue = Math.max(...visibleZones.map(graphValue), 0.0001);
  container.replaceChildren(
    ...visibleZones.map((zone) => {
      const value = graphValue(zone);
      const item = document.createElement("div");
      item.className = "zone-chart-row";
      item.innerHTML = `
        <span>${zone.name}</span>
        <div class="zone-chart-track">
          <div class="zone-chart-fill"></div>
        </div>
        <strong>${graphValueLabel(value, currency)}</strong>
      `;
      const fill = item.querySelector(".zone-chart-fill");
      fill.style.width = `${Math.max(3, (value / maxValue) * 100)}%`;
      return item;
    })
  );
}

function renderGraphs() {
  const usage = state.usage;
  if (!usage || !usage.charts) {
    return;
  }

  const currency = usage.currency || "INR";
  const unit = graphUnitLabel(currency);
  weeklyGraphUnit.textContent = unit;
  monthlyGraphUnit.textContent = unit;
  zoneGraphUnit.textContent = unit;

  renderBarChart(weeklyUsageChart, usage.charts.last_7_days || [], currency);
  renderBarChart(monthlyUsageChart, usage.charts.month_days || [], currency);
  renderZoneChart(zoneUsageChart, usage.charts.zones || [], currency);
}

function openGraphDrawer() {
  usageGraphDrawer.classList.add("visible");
  usageGraphDrawer.setAttribute("aria-hidden", "false");
  renderGraphs();
}

function closeGraphDrawer() {
  usageGraphDrawer.classList.remove("visible");
  usageGraphDrawer.setAttribute("aria-hidden", "true");
}

function setGraphMode(mode) {
  graphMode = mode === "cost" ? "cost" : "usage";
  document.querySelectorAll("[data-graph-mode]").forEach((button) => {
    button.classList.toggle("active", button.dataset.graphMode === graphMode);
  });
  renderGraphs();
}

function render() {
  renderConnection();
  renderBluetoothStatus();
  renderMeta();
  renderUsage();
  renderLeds();
  renderAutomations();
}

async function setLed(ledId, desiredState) {
  if (isBluetoothConnected()) {
    await sendBluetoothCommand(`led:${ledId}:${desiredState ? "on" : "off"}`);
    updateLocalLedState(ledId, desiredState);
    return;
  }

  await postJson(`/api/led/${ledId}`, { state: desiredState });
}

async function setAll(desiredState) {
  try {
    if (isBluetoothConnected()) {
      await sendBluetoothCommand(`all:${desiredState ? "on" : "off"}`);
      updateLocalAllStates(desiredState);
      return;
    }

    await postJson("/api/all", { state: desiredState });
  } catch (error) {
    showToast(error.message);
  }
}

async function executeBluetoothAssistantAction(action) {
  if (typeof action.state !== "boolean") {
    return false;
  }

  if (action.type === "set_all") {
    await sendBluetoothCommand(`all:${action.state ? "on" : "off"}`);
    updateLocalAllStates(action.state);
    return true;
  }

  if (action.type === "set_led" && Number.isInteger(action.led_id)) {
    await sendBluetoothCommand(`led:${action.led_id}:${action.state ? "on" : "off"}`);
    updateLocalLedState(action.led_id, action.state);
    return true;
  }

  return false;
}

function bluetoothScheduleLabel(action, runAt) {
  if (action.automation) {
    return `${action.automation} by Bluetooth`;
  }

  const stateWord = action.state ? "on" : "off";
  const timeText = runAt.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  if (action.type === "schedule_all") {
    return `Turn all lights ${stateWord} at ${timeText} by Bluetooth`;
  }

  const zoneName = ledNames[action.led_id - 1] || `LED ${action.led_id}`;
  return `Turn ${zoneName} ${stateWord} at ${timeText} by Bluetooth`;
}

function updateBluetoothAutomation(id, status, error = "") {
  bluetoothAutomations = bluetoothAutomations.map((automation) =>
    automation.id === id ? { ...automation, status, error } : automation
  );
  render();
}

function scheduleBluetoothAssistantAction(action) {
  const runAt = new Date(action.run_at);
  if (Number.isNaN(runAt.getTime())) {
    throw new Error("Schedule time was not understood");
  }

  const delay = runAt.getTime() - Date.now();
  if (delay <= 0) {
    throw new Error("Schedule time has already passed");
  }

  bluetoothScheduleCounter += 1;
  const id = `ble-auto-${Date.now()}-${bluetoothScheduleCounter}`;
  const automation = {
    id,
    label: bluetoothScheduleLabel(action, runAt),
    run_at: runAt.toISOString(),
    status: "scheduled",
  };

  bluetoothAutomations = [...bluetoothAutomations, automation];
  render();

  window.setTimeout(async () => {
    updateBluetoothAutomation(id, "running");
    const immediateAction = {
      type: action.type === "schedule_all" ? "set_all" : "set_led",
      led_id: action.led_id,
      state: action.state,
    };

    try {
      await executeBluetoothAssistantAction(immediateAction);
      updateBluetoothAutomation(id, "completed");
      showToast("Bluetooth schedule completed");
    } catch (error) {
      updateBluetoothAutomation(id, "failed", error.message);
      showToast(error.message || "Bluetooth schedule failed");
    }
  }, delay);
}

async function runBluetoothAssistantActions(actions) {
  const result = { sent: 0, scheduled: 0 };
  const bluetoothActions = (actions || []).filter(
    (action) => action.ok !== false && ["set_all", "set_led", "schedule_all", "schedule_led"].includes(action.type)
  );

  for (const action of bluetoothActions) {
    if (typeof action.state !== "boolean") {
      continue;
    }

    if (action.type === "schedule_all" || action.type === "schedule_led") {
      scheduleBluetoothAssistantAction(action);
      result.scheduled += 1;
      continue;
    }

    if (await executeBluetoothAssistantAction(action)) {
      result.sent += 1;
    }
  }

  return result;
}

function parseImmediateBluetoothAssistantActions(message) {
  const normalized = message.toLowerCase();
  const hasTime =
    /\b(?:in|after)\s+\d{1,3}\s*(minute|minutes|min|hour|hours|hr|hrs)\b/.test(normalized) ||
    /\b\d{1,2}:\d{2}\s*(am|pm)\b/.test(normalized) ||
    /\b(?:at|by|around|on)\s+([01]?\d|2[0-3]):([0-5]\d)\b/.test(normalized) ||
    /\b(?:at|by|around)\s+\d{1,2}\s*(am|pm)?\b/.test(normalized);

  if (hasTime || /\b(tomorrow|schedule|scheduled)\b/.test(normalized)) {
    return [];
  }

  let desiredState = null;
  if (/\b(turn|switch|put|power)\s+on\b|\blights?\s+on\b|\bleds?\s+on\b|\bon\b/.test(normalized)) {
    desiredState = true;
  }
  if (/\b(turn|switch|put|power)\s+off\b|\blights?\s+off\b|\bleds?\s+off\b|\boff\b|\bclose\b|\bshutdown\b|\bshut down\b/.test(normalized)) {
    desiredState = false;
  }

  if (typeof desiredState !== "boolean") {
    return [];
  }

  const zoneIds = [];
  ledNames.forEach((name, index) => {
    const words = name.toLowerCase().match(/[a-z0-9]+/g) || [];
    const matchesName = words.some((word) => word.length >= 3 && normalized.includes(word));
    const matchesNumber = new RegExp(`\\b(led|light)\\s*${index + 1}\\b`).test(normalized);
    if ((matchesName || matchesNumber) && !zoneIds.includes(index + 1)) {
      zoneIds.push(index + 1);
    }
  });

  const allRequested = /\b(all|every|everything|home|house|lights|leds)\b/.test(normalized);
  if (allRequested || zoneIds.length === 0) {
    return [{ type: "set_all", state: desiredState }];
  }

  return zoneIds.map((ledId) => ({ type: "set_led", led_id: ledId, state: desiredState }));
}

function bluetoothAssistantReply(actions) {
  if (actions.length === 1 && actions[0].type === "set_all") {
    return `Turning ${actions[0].state ? "on" : "off"} all home lights by Bluetooth.`;
  }

  const names = actions.map((action) => ledNames[action.led_id - 1] || `LED ${action.led_id}`);
  return `Turning ${actions[0].state ? "on" : "off"} ${names.join(", ")} by Bluetooth.`;
}

function connectEvents() {
  if (!window.EventSource) {
    fetchStatus().catch((error) => showToast(error.message));
    setInterval(() => fetchStatus().catch(() => {}), 5000);
    return;
  }

  const events = new EventSource("/api/events");
  events.onmessage = (event) => {
    applyState(JSON.parse(event.data));
  };
  events.onerror = () => {
    connectionBadge.classList.remove("online");
    connectionBadge.classList.add("offline");
    connectionText.textContent = "Reconnecting";
    statusText.textContent = "Reconnecting";
  };
}

document.querySelector("#allOnButton").addEventListener("click", () => setAll(true));
document.querySelector("#allOffButton").addEventListener("click", () => setAll(false));
document.querySelector("#refreshButton").addEventListener("click", () => {
  fetchStatus()
    .then(() => showToast("Status refreshed"))
    .catch((error) => showToast(error.message));
});

bluetoothConnectButton.addEventListener("click", connectBluetooth);
usageGraphsButton.addEventListener("click", openGraphDrawer);
voiceCommandButton.addEventListener("click", toggleVoiceListening);
voiceReplyButton.addEventListener("click", () => setVoiceRepliesEnabled(!voiceRepliesEnabled));
closeGraphsButton.addEventListener("click", closeGraphDrawer);
usageGraphDrawer.addEventListener("click", (event) => {
  if (event.target === usageGraphDrawer) {
    closeGraphDrawer();
  }
});
document.querySelectorAll("[data-graph-mode]").forEach((button) => {
  button.addEventListener("click", () => setGraphMode(button.dataset.graphMode));
});

function appendAssistantMessage(role, content) {
  const element = document.createElement("div");
  element.className = `assistant-message ${role}`;
  const bubble = document.createElement("span");
  bubble.textContent = content;
  element.appendChild(bubble);
  assistantMessages.appendChild(element);
  assistantMessages.scrollTop = assistantMessages.scrollHeight;
}

async function askAssistant(message, options = {}) {
  appendAssistantMessage("user", message);
  chatHistory.push({ role: "user", content: message });

  try {
    const useBluetooth = isBluetoothConnected();
    if (useBluetooth) {
      const localBluetoothActions = parseImmediateBluetoothAssistantActions(message);
      if (localBluetoothActions.length > 0) {
        const reply = bluetoothAssistantReply(localBluetoothActions);
        const result = await runBluetoothAssistantActions(localBluetoothActions);
        appendAssistantMessage("assistant", reply);
        chatHistory.push({ role: "assistant", content: reply });
        speakAssistantReply(reply, Boolean(options.speakReply));
        if (result.sent > 0) {
          showToast("AI command sent by Bluetooth");
        }
        return;
      }
    }

    const response = await postJson("/api/assistant/chat", {
      message,
      history: chatHistory.slice(-8),
      transport: useBluetooth ? "bluetooth" : "server",
    });
    appendAssistantMessage("assistant", response.reply);
    chatHistory.push({ role: "assistant", content: response.reply });
    speakAssistantReply(response.reply, Boolean(options.speakReply));
    if (response.state) {
      applyState(response.state);
    }
    if (useBluetooth) {
      const result = await runBluetoothAssistantActions(response.actions);
      if (result.scheduled > 0) {
        showToast("Bluetooth schedule saved");
      } else if (result.sent > 0) {
        showToast("AI command sent by Bluetooth");
      }
    }
  } catch (error) {
    const messageText = error.message || "Assistant command failed";
    appendAssistantMessage("assistant", messageText);
    speakAssistantReply(messageText, Boolean(options.speakReply));
  }
}

assistantForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = assistantInput.value.trim();
  if (!message) {
    showToast("Enter a home command");
    return;
  }

  assistantInput.value = "";
  await askAssistant(message);
});

document.querySelectorAll("[data-prompt]").forEach((button) => {
  button.addEventListener("click", () => {
    assistantInput.value = button.dataset.prompt;
    assistantInput.focus();
  });
});

if (config.pinRequired) {
  pinField.classList.add("visible");
  pinInput.value = controlPin();
  pinInput.addEventListener("input", () => setControlPin(pinInput.value.trim()));
}

renderVoiceControls();
render();
connectEvents();
