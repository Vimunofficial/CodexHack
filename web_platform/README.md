# Echo Home

Echo Home controls the existing ESP32 LED firmware through MQTT and nearby Bluetooth.

It also includes an AI assistant for home-lighting decisions. Without an
OpenAI key it uses the local automation planner. With `OPENAI_API_KEY`, it uses
the OpenAI Responses API.

The ESP32, Python backend, and browser use the same topic prefix:

```text
echo_home_2026
```

## Run Locally

```powershell
cd D:\CodexHackathon\web_platform
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python app.py
```

Open:

```text
http://localhost:5000
```

## Environment

Create environment variables when you deploy, or create a local `.env` file in
this folder using `.env.example` as the template:

```text
MQTT_HOST=broker.emqx.io
MQTT_PORT=1883
MQTT_PREFIX=echo_home_2026
LED_COUNT=5
LED_LOCATIONS=Living Room,Kitchen,Bedroom,Front Door,Garage
LED_WATTAGES=9,9,9,9,9
ENERGY_RATE_PER_KWH=8.0
ENERGY_CURRENCY=INR
LOCAL_TIMEZONE=Asia/Kolkata
DEVICE_HEARTBEAT_TIMEOUT_SECONDS=15
CONTROL_PIN=your-private-pin
OPENAI_API_KEY=your-openai-api-key
OPENAI_MODEL=gpt-5.4-mini
```

Keep `.env` private. Do not commit or share your real `OPENAI_API_KEY`.

Usage and bill statistics are estimates. Echo Home calculates them from each
zone's on-time, `LED_WATTAGES`, and `ENERGY_RATE_PER_KWH`; it is not reading a
physical energy meter.

`CONTROL_PIN` is optional locally, but recommended when the web app is public.

Example assistant commands:

```text
Home closes at 6 PM
Turn on Living Room
I am not available at home on 1:30 PM
I am not available in Kitchen at 1:30 PM
Switch off Kitchen
What lights are on?
What is today's bill?
Show this week's usage
Predict this month's bill
What can you do?
```

## Bluetooth Portal

The web UI includes a Bluetooth button. Use Chrome or Edge on `localhost` or HTTPS, connect to the BLE device named `EchoHome`, then the LED switches and AI commands can control the ESP32 directly without MQTT/internet. Timed Bluetooth commands run from the browser, so keep the page open and Bluetooth connected until the scheduled time. When you say you are not available, away, or not at home at a time, the assistant schedules the target lights off two minutes after that time.

## Voice Assistant

The AI Assistant panel includes a voice command button and a spoken-reply toggle.
Voice input uses the browser Web Speech API, so use Chrome or Edge on
`localhost` or HTTPS and allow microphone permission when prompted.

## Deploy For World-Wide Control

Deploy the `web_platform` folder to any Python web host that supports Flask or Gunicorn.

The deployed server must keep the same `MQTT_PREFIX` as the ESP32 firmware. If you change `MQTT_PREFIX`, update the ESP32 topics in `src/main.cpp` and upload the firmware again.
