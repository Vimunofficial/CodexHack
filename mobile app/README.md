# Echo Home Mobile

Native Android Studio Kotlin version of the Echo Home web platform.

## What is included

- Jetpack Compose mobile dashboard for the same 5 Echo Home zones.
- MQTT control using the same broker and topic prefix as the ESP32 firmware:
  `broker.emqx.io`, `echo_home_2026`.
- Nearby Bluetooth LE control for the ESP32 named `EchoHome`.
- Local assistant commands for light control, status, scheduling, usage, and bill estimates.
- Voice command entry using Android speech recognition.
- OLED display message sender over MQTT.
- Local usage/bill tracking from LED on-time at `9W` per zone and `INR 8.0/kWh`.
- Usage and cost graph dialog.

## Open in Android Studio

1. Open Android Studio.
2. Choose **Open**.
3. Select:

   ```text
   D:\CodexHackathon\mobile app
   ```

4. Let Android Studio sync Gradle.
5. Run the `app` configuration on a phone or emulator.

For Bluetooth control, use a real Android phone. Emulators usually do not expose BLE scanning to apps.

## ChatGPT Assistant Setup

The mobile app can use the web platform as its ChatGPT decision server.

1. Start the web platform:

   ```powershell
   cd D:\CodexHackathon\web_platform
   .\.venv\Scripts\Activate.ps1
   python app.py
   ```

2. Make sure `OPENAI_API_KEY` is set in `web_platform\.env`.
3. On the phone app, open **Tools** and set **AI server URL** to your PC/server address, for example:

   ```text
   http://192.168.1.10:5000
   ```

Do not use `localhost` on a real phone. `localhost` points to the phone itself, not your computer.

If the server URL is empty or unreachable, the app uses the local assistant fallback.

## Device compatibility notes

The app uses the UUIDs and commands from `src/main.cpp`:

```text
BLE service:  7f2b6d6a-9c0b-4b36-91f5-71f67e2f8a10
BLE command:  led:<id>:on, led:<id>:off, all:on, all:off
MQTT status:  echo_home_2026/status
MQTT LED:     echo_home_2026/cmd/led/<id>
MQTT display: echo_home_2026/cmd/display
```

If you change the ESP32 topic prefix or BLE UUIDs, update `EchoConfig` in:

```text
app/src/main/java/com/codexhackathon/echohome/data/AppModels.kt
```
