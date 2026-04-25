#include <Arduino.h>
#include <WiFi.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <PubSubClient.h>
#include <NimBLEDevice.h>

// WiFi
const char* WIFI_SSID = "VIMUN";
const char* WIFI_PASSWORD = "12345678";

// MQTT
const char* mqtt_server = "broker.emqx.io";
const int mqtt_port = 1883;
const unsigned long WIFI_CONNECT_TIMEOUT_MS = 15000;

WiFiClient espClient;
PubSubClient mqttClient(espClient);

// Bluetooth Low Energy control
const char* BLE_DEVICE_NAME = "EchoHome";
#define BLE_SERVICE_UUID "7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a10"
#define BLE_COMMAND_UUID "7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a11"
#define BLE_STATUS_UUID "7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a12"

NimBLECharacteristic* bleStatusCharacteristic = nullptr;
bool bleClientConnected = false;

// OLED 0.91 inch 128x32 SSD1306 I2C
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 32
#define OLED_RESET U8X8_PIN_NONE
#define OLED_I2C_ADDRESS 0x3C

#define OLED_SDA_PIN 21
#define OLED_SCL_PIN 22

U8G2_SSD1306_128X32_UNIVISION_F_SW_I2C displayNormalPins(
  U8G2_R0,
  OLED_SCL_PIN,
  OLED_SDA_PIN,
  OLED_RESET
);

U8G2_SSD1306_128X32_UNIVISION_F_SW_I2C displaySwappedPins(
  U8G2_R0,
  OLED_SDA_PIN,
  OLED_SCL_PIN,
  OLED_RESET
);

U8G2 *activeDisplay = &displayNormalPins;
#define display (*activeDisplay)

bool oledReady = false;

// LEDs from your code
const bool LED_ACTIVE_HIGH = true;
const uint8_t ledPins[] = {19, 4, 16, 17, 18};
const size_t ledCount = sizeof(ledPins) / sizeof(ledPins[0]);
bool ledStates[ledCount] = {false, false, false, false, false};

String customMessage = "";
unsigned long customMessageTime = 0;

uint8_t ledSignal(bool isOn) {
  return (isOn == LED_ACTIVE_HIGH) ? HIGH : LOW;
}

void startI2cBus(uint8_t sdaPin, uint8_t sclPin) {
  Wire.end();
  Wire.begin(sdaPin, sclPin);
  Wire.setClock(100000);
  delay(50);
}

bool i2cAddressResponds(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

uint8_t detectOledAddress() {
  if (i2cAddressResponds(0x3C)) return 0x3C;
  if (i2cAddressResponds(0x3D)) return 0x3D;
  return OLED_I2C_ADDRESS;
}

bool scanI2cBus(const char* label) {
  bool foundAnyDevice = false;

  Serial.print("I2C scan start: ");
  Serial.println(label);
  for (uint8_t address = 1; address < 127; address++) {
    if (i2cAddressResponds(address)) {
      foundAnyDevice = true;
      Serial.print("I2C device found at 0x");
      if (address < 16) Serial.print("0");
      Serial.println(address, HEX);
    }
  }

  if (!foundAnyDevice) {
    Serial.println("I2C scan found no devices");
  }
  Serial.println("I2C scan end");

  return foundAnyDevice;
}

String displayMessageLine() {
  String line = customMessage;
  line.trim();

  if (line == "") {
    return "MSG: -";
  }

  if (line.length() > 18) {
    line = line.substring(0, 18);
  }

  return "MSG: " + line;
}

void showOledBootTest() {
  if (!oledReady) return;

  display.setPowerSave(0);
  display.setContrast(255);
  display.clearBuffer();
  display.setDrawColor(1);
  display.drawBox(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
  display.sendBuffer();
  delay(700);

  display.clearBuffer();
  display.setFont(u8g2_font_7x13B_tf);
  display.drawStr(0, 13, "OLED OK");
  display.setFont(u8g2_font_5x8_tf);
  display.drawStr(0, 29, "U8g2 SSD1306 128x32");
  display.sendBuffer();
  delay(1800);
}

void refreshDisplay() {
  if (!oledReady) return;

  String msgLine = displayMessageLine();
  String ledLine1 = String("L1:") + (ledStates[0] ? "ON " : "OFF") + " L2:" + (ledStates[1] ? "ON" : "OFF");
  String ledLine2 = String("L3:") + (ledStates[2] ? "ON " : "OFF") + " L4:" + (ledStates[3] ? "ON" : "OFF");
  String ledLine3 = String("L5:") + (ledStates[4] ? "ON" : "OFF");

  display.setPowerSave(0);
  display.clearBuffer();
  display.setFont(u8g2_font_5x8_tf);
  display.drawStr(0, 7, msgLine.c_str());
  display.drawStr(0, 15, ledLine1.c_str());
  display.drawStr(0, 23, ledLine2.c_str());
  display.drawStr(0, 31, ledLine3.c_str());
  display.sendBuffer();
}

String buildStatusJson() {
  String json = "{\"leds\":[";
  for (size_t i = 0; i < ledCount; i++) {
    if (i > 0) json += ",";
    json += "{\"id\":";
    json += String(i + 1);
    json += ",\"pin\":";
    json += String(ledPins[i]);
    json += ",\"state\":";
    json += ledStates[i] ? "true" : "false";
    json += "}";
  }
  json += "]}";
  return json;
}

void publishStatus() {
  String json = buildStatusJson();

  if (mqttClient.connected()) {
    mqttClient.publish("echo_home_2026/status", json.c_str(), true);
  }

  if (bleStatusCharacteristic != nullptr) {
    bleStatusCharacteristic->setValue(json.c_str());
    if (bleClientConnected) {
      bleStatusCharacteristic->notify();
    }
  }
}

void setLedState(size_t index, bool isOn) {
  if (index >= ledCount) return;

  ledStates[index] = isOn;
  digitalWrite(ledPins[index], ledSignal(isOn));
  refreshDisplay();
}

bool parseOnOff(String value, bool &state) {
  value.trim();
  value.toLowerCase();

  if (value == "1" || value == "true" || value == "on") {
    state = true;
    return true;
  }

  if (value == "0" || value == "false" || value == "off") {
    state = false;
    return true;
  }

  return false;
}

String processBleCommand(String command) {
  command.trim();
  String normalized = command;
  normalized.toLowerCase();

  Serial.print("BLE payload: ");
  Serial.println(command);

  if (normalized.startsWith("all:")) {
    bool state = false;
    if (!parseOnOff(normalized.substring(4), state)) {
      return "ERR bad all state";
    }

    for (size_t i = 0; i < ledCount; i++) {
      setLedState(i, state);
    }
    publishStatus();
    return state ? "OK all on" : "OK all off";
  }

  if (normalized.startsWith("led:")) {
    int firstColon = normalized.indexOf(':');
    int secondColon = normalized.indexOf(':', firstColon + 1);

    if (secondColon < 0) {
      return "ERR bad led command";
    }

    int id = normalized.substring(firstColon + 1, secondColon).toInt();
    bool state = false;

    if (id < 1 || id > ledCount || !parseOnOff(normalized.substring(secondColon + 1), state)) {
      return "ERR bad led value";
    }

    setLedState(id - 1, state);
    publishStatus();
    return String("OK led ") + id + (state ? " on" : " off");
  }

  if (normalized == "status") {
    publishStatus();
    return "OK status";
  }

  return "ERR unknown command";
}

class EchoBleServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server) {
    bleClientConnected = true;
    publishStatus();
    Serial.println("BLE client connected");
  }

  void onDisconnect(NimBLEServer* server) {
    bleClientConnected = false;
    NimBLEDevice::startAdvertising();
    Serial.println("BLE client disconnected");
  }
};

class EchoBleCommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic) {
    std::string value = characteristic->getValue();
    if (value.empty()) return;

    String command = String(value.c_str());
    String result = processBleCommand(command);

    if (bleStatusCharacteristic != nullptr) {
      bleStatusCharacteristic->setValue(buildStatusJson().c_str());
      if (bleClientConnected) {
        bleStatusCharacteristic->notify();
      }
    }

    Serial.print("BLE result: ");
    Serial.println(result);
  }
};

void setupBluetooth() {
  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new EchoBleServerCallbacks());

  NimBLEService* service = server->createService(BLE_SERVICE_UUID);

  NimBLECharacteristic* commandCharacteristic = service->createCharacteristic(
    BLE_COMMAND_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
  );
  commandCharacteristic->setCallbacks(new EchoBleCommandCallbacks());

  bleStatusCharacteristic = service->createCharacteristic(
    BLE_STATUS_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
  );
  bleStatusCharacteristic->setValue(buildStatusJson().c_str());

  service->start();

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  NimBLEDevice::startAdvertising();

  Serial.println("BLE ready: EchoHome");
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String topicStr = String(topic);
  String payloadStr = "";

  for (unsigned int i = 0; i < length; i++) {
    payloadStr += (char)payload[i];
  }

  String commandStr = payloadStr;
  commandStr.trim();
  commandStr.toLowerCase();

  Serial.print("MQTT topic: ");
  Serial.println(topicStr);
  Serial.print("MQTT payload: ");
  Serial.println(payloadStr);

  bool state = commandStr == "1" || commandStr == "true" || commandStr == "on";

  if (topicStr == "echo_home_2026/cmd/all") {
    for (size_t i = 0; i < ledCount; i++) {
      setLedState(i, state);
    }
    publishStatus();
  }

  else if (topicStr == "echo_home_2026/cmd/display") {
    payloadStr.trim();
    customMessage = payloadStr;
    customMessageTime = millis();
    refreshDisplay();
  }

  else if (topicStr.startsWith("echo_home_2026/cmd/led/")) {
    String idStr = topicStr.substring(String("echo_home_2026/cmd/led/").length());
    int id = idStr.toInt();

    if (id >= 1 && id <= ledCount) {
      setLedState(id - 1, state);
      publishStatus();
    }
  }
}

void reconnect() {
  if (WiFi.status() != WL_CONNECTED || mqttClient.connected()) {
    return;
  }

  Serial.print("Attempting MQTT connection...");

  String clientId = "ESP32Client-";
  clientId += String(random(0xffff), HEX);

  if (mqttClient.connect(clientId.c_str())) {
    Serial.println("connected");

    mqttClient.subscribe("echo_home_2026/cmd/all");
    mqttClient.subscribe("echo_home_2026/cmd/led/#");
    mqttClient.subscribe("echo_home_2026/cmd/display");

    publishStatus();
  } else {
    Serial.print("failed, rc=");
    Serial.println(mqttClient.state());
  }
}

void setup() {
  Serial.begin(115200);

  // OLED setup
  delay(250);

  startI2cBus(OLED_SDA_PIN, OLED_SCL_PIN);
  bool normalPinsFound = scanI2cBus("normal SDA=21 SCL=22");
  uint8_t normalAddress = detectOledAddress();
  bool normalAck = i2cAddressResponds(normalAddress);

  startI2cBus(OLED_SCL_PIN, OLED_SDA_PIN);
  bool swappedPinsFound = scanI2cBus("swapped SDA=22 SCL=21");
  uint8_t swappedAddress = detectOledAddress();
  bool swappedAck = i2cAddressResponds(swappedAddress);

  uint8_t oledAddress = OLED_I2C_ADDRESS;

  if (normalPinsFound && normalAck) {
    activeDisplay = &displayNormalPins;
    oledAddress = normalAddress;
    startI2cBus(OLED_SDA_PIN, OLED_SCL_PIN);
    Serial.println("OLED wiring selected: SDA=21 SCL=22");
  } else if (swappedPinsFound && swappedAck) {
    activeDisplay = &displaySwappedPins;
    oledAddress = swappedAddress;
    startI2cBus(OLED_SCL_PIN, OLED_SDA_PIN);
    Serial.println("OLED wiring selected: SDA=22 SCL=21");
  } else {
    activeDisplay = &displayNormalPins;
    startI2cBus(OLED_SDA_PIN, OLED_SCL_PIN);
    Serial.println("OLED wiring selected: default SDA=21 SCL=22");
  }

  Serial.print("OLED selected I2C address: 0x");
  Serial.println(oledAddress, HEX);
  Serial.print("OLED normal ACK: ");
  Serial.println(normalAck ? "yes" : "no");
  Serial.print("OLED swapped ACK: ");
  Serial.println(swappedAck ? "yes" : "no");

  display.setI2CAddress(oledAddress << 1);
  display.setBusClock(100000);
  display.begin();
  display.setPowerSave(0);
  oledReady = true;
  showOledBootTest();

  // LED setup
  for (size_t i = 0; i < ledCount; i++) {
    pinMode(ledPins[i], OUTPUT);
    setLedState(i, false);
  }

  setupBluetooth();

  // WiFi setup
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to WiFi");
  unsigned long wifiStartTime = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiStartTime < WIFI_CONNECT_TIMEOUT_MS) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Connected. ESP32 IP: ");
    Serial.println(WiFi.localIP());

    if (oledReady) {
      display.clearBuffer();
      display.setFont(u8g2_font_5x8_tf);
      display.drawStr(0, 7, "WiFi Connected");
      display.drawStr(0, 15, WiFi.localIP().toString().c_str());
      display.sendBuffer();
      delay(1500);
    }
  } else {
    Serial.println("WiFi offline. Bluetooth control is available.");
    WiFi.disconnect(false);

    if (oledReady) {
      display.clearBuffer();
      display.setFont(u8g2_font_5x8_tf);
      display.drawStr(0, 7, "WiFi Offline");
      display.drawStr(0, 15, "BLE EchoHome");
      display.sendBuffer();
      delay(1500);
    }
  }

  mqttClient.setServer(mqtt_server, mqtt_port);
  mqttClient.setCallback(mqttCallback);

  refreshDisplay();
}

unsigned long lastPublishTime = 0;

void loop() {
  if (WiFi.status() == WL_CONNECTED && !mqttClient.connected()) {
    reconnect();
  }

  if (mqttClient.connected()) {
    mqttClient.loop();
  }

  unsigned long now = millis();

  if (now - lastPublishTime > 5000) {
    lastPublishTime = now;
    publishStatus();
    refreshDisplay();
  }
}
