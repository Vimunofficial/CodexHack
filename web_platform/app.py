import json
import os
import queue
import re
import threading
import time
import urllib.error
import urllib.request
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from flask import Flask, Response, jsonify, render_template, request, stream_with_context
import paho.mqtt.client as mqtt


def load_local_env_file():
    env_path = os.path.join(os.path.dirname(__file__), ".env")
    if not os.path.exists(env_path):
        return

    with open(env_path, "r", encoding="utf-8") as env_file:
        for raw_line in env_file:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue

            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key and key not in os.environ:
                os.environ[key] = value


load_local_env_file()


def parse_float_env(name, default):
    try:
        return float(os.getenv(name, str(default)).strip())
    except (TypeError, ValueError):
        return float(default)


MQTT_HOST = os.getenv("MQTT_HOST", "broker.emqx.io")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))
MQTT_PREFIX = os.getenv("MQTT_PREFIX", "echo_home_2026").strip("/")
MQTT_USERNAME = os.getenv("MQTT_USERNAME", "")
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD", "")
LED_COUNT = int(os.getenv("LED_COUNT", "5"))
CONTROL_PIN = os.getenv("CONTROL_PIN", "").strip()
PORT = int(os.getenv("PORT", "5000"))
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-5.4-mini").strip()
LOCAL_TIMEZONE = os.getenv("LOCAL_TIMEZONE", "Asia/Kolkata").strip()
DEVICE_HEARTBEAT_TIMEOUT_SECONDS = int(os.getenv("DEVICE_HEARTBEAT_TIMEOUT_SECONDS", "15"))
ENERGY_RATE_PER_KWH = parse_float_env("ENERGY_RATE_PER_KWH", 8.0)
ENERGY_CURRENCY = os.getenv("ENERGY_CURRENCY", "INR").strip() or "INR"
USAGE_DATA_FILE = os.getenv(
    "USAGE_DATA_FILE",
    os.path.join(os.path.dirname(__file__), "usage_history.json"),
)

DEFAULT_LED_PINS = [19, 4, 16, 17, 18]
DEFAULT_LED_NAMES = [
    "Living Room",
    "Kitchen",
    "Bedroom",
    "Front Door",
    "Garage",
]

try:
    APP_TZ = ZoneInfo(LOCAL_TIMEZONE)
except ZoneInfoNotFoundError:
    if LOCAL_TIMEZONE.lower() in {"asia/kolkata", "asia/calcutta", "ist"}:
        APP_TZ = timezone(timedelta(hours=5, minutes=30), "IST")
    else:
        APP_TZ = timezone.utc


def parse_led_names():
    raw_names = os.getenv("LED_LOCATIONS", "").strip()
    if raw_names:
        names = [name.strip() for name in raw_names.split(",") if name.strip()]
    else:
        names = DEFAULT_LED_NAMES[:]

    while len(names) < LED_COUNT:
        names.append(f"Home Zone {len(names) + 1}")

    return names[:LED_COUNT]


def parse_led_wattages():
    raw_watts = os.getenv("LED_WATTAGES", "").strip()
    if raw_watts:
        values = []
        for item in raw_watts.split(","):
            try:
                values.append(max(0.0, float(item.strip())))
            except ValueError:
                values.append(9.0)
    else:
        values = [9.0] * LED_COUNT

    while len(values) < LED_COUNT:
        values.append(values[-1] if values else 9.0)

    return values[:LED_COUNT]


LED_NAMES = parse_led_names()
LED_WATTAGES = parse_led_wattages()

app = Flask(__name__)

state_lock = threading.Lock()
listeners_lock = threading.Lock()
automation_lock = threading.Lock()
usage_lock = threading.Lock()
listeners = set()
automations = []
usage_events = []
usage_live_states = {
    index + 1: {"state": False, "started_at": None}
    for index in range(LED_COUNT)
}

device_state = {
    "mqtt_connected": False,
    "device_online": False,
    "device_status": "Waiting for ESP32 heartbeat",
    "broker": MQTT_HOST,
    "port": MQTT_PORT,
    "topic_prefix": MQTT_PREFIX,
    "last_seen": None,
    "last_retained_status": None,
    "last_error": None,
    "leds": [
        {
            "id": index + 1,
            "name": LED_NAMES[index],
            "pin": DEFAULT_LED_PINS[index] if index < len(DEFAULT_LED_PINS) else None,
            "state": False,
        }
        for index in range(LED_COUNT)
    ],
}


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def utc_now():
    return datetime.now(timezone.utc)


def parse_iso_datetime(value):
    if not value:
        return None

    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None

    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def local_now():
    return datetime.now(APP_TZ)


def local_now_iso():
    return local_now().isoformat(timespec="seconds")


def topic(path):
    return f"{MQTT_PREFIX}/{path}"


def load_usage_events():
    if not os.path.exists(USAGE_DATA_FILE):
        return []

    try:
        with open(USAGE_DATA_FILE, "r", encoding="utf-8") as usage_file:
            data = json.load(usage_file)
    except (OSError, json.JSONDecodeError):
        return []

    if not isinstance(data, list):
        return []

    return [
        event
        for event in data
        if isinstance(event, dict)
        and isinstance(event.get("led_id"), int)
        and event.get("started_at")
        and event.get("ended_at")
    ]


def save_usage_events_copy(events):
    directory = os.path.dirname(USAGE_DATA_FILE)
    if directory:
        os.makedirs(directory, exist_ok=True)

    temp_path = f"{USAGE_DATA_FILE}.tmp"
    with open(temp_path, "w", encoding="utf-8") as usage_file:
        json.dump(events[-5000:], usage_file, indent=2)
    os.replace(temp_path, USAGE_DATA_FILE)


def led_watts(led_id):
    if 1 <= led_id <= len(LED_WATTAGES):
        return LED_WATTAGES[led_id - 1]
    return 9.0


def record_led_state_change(led_id, is_on, changed_at=None):
    if not 1 <= led_id <= LED_COUNT:
        return

    changed_at = changed_at or utc_now()
    changed_iso = changed_at.isoformat(timespec="seconds")
    events_to_save = None

    with usage_lock:
        live_state = usage_live_states.setdefault(led_id, {"state": False, "started_at": None})
        if live_state["state"] == is_on:
            return

        if live_state["state"] and live_state.get("started_at"):
            started_at = parse_iso_datetime(live_state["started_at"])
            if started_at and changed_at > started_at:
                usage_events.append(
                    {
                        "led_id": led_id,
                        "name": zone_label(led_id),
                        "started_at": started_at.isoformat(timespec="seconds"),
                        "ended_at": changed_iso,
                        "duration_seconds": round((changed_at - started_at).total_seconds(), 3),
                        "watts": led_watts(led_id),
                    }
                )
                events_to_save = list(usage_events)

        live_state["state"] = is_on
        live_state["started_at"] = changed_iso if is_on else None

    if events_to_save is not None:
        try:
            save_usage_events_copy(events_to_save)
        except OSError:
            pass


def start_of_week(value):
    start_date = value.date() - timedelta(days=value.weekday())
    return datetime.combine(start_date, datetime.min.time(), tzinfo=APP_TZ)


def start_of_month(value):
    return value.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


def next_month_start(value):
    if value.month == 12:
        return value.replace(year=value.year + 1, month=1, day=1, hour=0, minute=0, second=0, microsecond=0)
    return value.replace(month=value.month + 1, day=1, hour=0, minute=0, second=0, microsecond=0)


def seconds_overlap(start_at, end_at, window_start, window_end):
    latest_start = max(start_at, window_start)
    earliest_end = min(end_at, window_end)
    if earliest_end <= latest_start:
        return 0.0
    return (earliest_end - latest_start).total_seconds()


def empty_usage_totals():
    return {
        "seconds": 0.0,
        "kwh": 0.0,
        "cost": 0.0,
        "zones": {
            led_id: {
                "id": led_id,
                "name": zone_label(led_id),
                "watts": led_watts(led_id),
                "seconds": 0.0,
                "kwh": 0.0,
                "cost": 0.0,
            }
            for led_id in range(1, LED_COUNT + 1)
        },
    }


def add_usage_seconds(totals, led_id, seconds):
    if seconds <= 0 or not 1 <= led_id <= LED_COUNT:
        return

    watts = led_watts(led_id)
    kwh = watts * seconds / 3600000.0
    cost = kwh * ENERGY_RATE_PER_KWH

    totals["seconds"] += seconds
    totals["kwh"] += kwh
    totals["cost"] += cost

    zone = totals["zones"][led_id]
    zone["seconds"] += seconds
    zone["kwh"] += kwh
    zone["cost"] += cost


def usage_totals_between(window_start, window_end):
    totals = empty_usage_totals()
    now = utc_now()

    with usage_lock:
        events_copy = list(usage_events)
        live_copy = deepcopy(usage_live_states)

    for event in events_copy:
        led_id = int(event.get("led_id", 0))
        started_at = parse_iso_datetime(event.get("started_at"))
        ended_at = parse_iso_datetime(event.get("ended_at"))
        if not started_at or not ended_at:
            continue

        overlap = seconds_overlap(
            started_at.astimezone(APP_TZ),
            ended_at.astimezone(APP_TZ),
            window_start,
            window_end,
        )
        add_usage_seconds(totals, led_id, overlap)

    for led_id, live_state in live_copy.items():
        if not live_state.get("state") or not live_state.get("started_at"):
            continue

        started_at = parse_iso_datetime(live_state["started_at"])
        if not started_at:
            continue

        overlap = seconds_overlap(
            started_at.astimezone(APP_TZ),
            now.astimezone(APP_TZ),
            window_start,
            window_end,
        )
        add_usage_seconds(totals, int(led_id), overlap)

    return totals


def rounded_usage_period(name, window_start, window_end):
    totals = usage_totals_between(window_start, window_end)
    zones = [
        {
            **zone,
            "hours": round(zone["seconds"] / 3600.0, 2),
            "kwh": round(zone["kwh"], 4),
            "cost": round(zone["cost"], 2),
        }
        for zone in totals["zones"].values()
    ]

    return {
        "name": name,
        "start": window_start.isoformat(timespec="seconds"),
        "end": window_end.isoformat(timespec="seconds"),
        "hours": round(totals["seconds"] / 3600.0, 2),
        "kwh": round(totals["kwh"], 4),
        "cost": round(totals["cost"], 2),
        "zones": zones,
    }


def prediction_for_period(period, full_period_start, full_period_end, current_period):
    now = local_now()
    elapsed_seconds = max(60.0, (now - full_period_start).total_seconds())
    full_seconds = max(elapsed_seconds, (full_period_end - full_period_start).total_seconds())
    multiplier = full_seconds / elapsed_seconds
    projected_kwh = current_period["kwh"] * multiplier
    projected_cost = current_period["cost"] * multiplier

    return {
        "period": period,
        "through": full_period_end.isoformat(timespec="seconds"),
        "kwh": round(projected_kwh, 4),
        "cost": round(projected_cost, 2),
        "basis": "current usage rate",
    }


def usage_series_for_days(name, first_day_start, day_count, now):
    points = []
    for offset in range(day_count):
        day_start = first_day_start + timedelta(days=offset)
        if day_start > now:
            break

        day_end = min(day_start + timedelta(days=1), now)
        period = rounded_usage_period(name, day_start, day_end)
        points.append(
            {
                "label": day_start.strftime("%d %b"),
                "date": day_start.date().isoformat(),
                "kwh": period["kwh"],
                "cost": period["cost"],
                "hours": period["hours"],
            }
        )

    return points


def month_day_count(value):
    return (value.date() - start_of_month(value).date()).days + 1


def usage_snapshot():
    now = local_now()
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = start_of_week(now)
    month_start = start_of_month(now)
    last_7_start = day_start - timedelta(days=6)

    today = rounded_usage_period("today", day_start, now)
    week = rounded_usage_period("week", week_start, now)
    month = rounded_usage_period("month", month_start, now)
    month_zones = sorted(month["zones"], key=lambda zone: zone["cost"], reverse=True)

    return {
        "generated_at": now_iso(),
        "currency": ENERGY_CURRENCY,
        "rate_per_kwh": ENERGY_RATE_PER_KWH,
        "installed_watts": round(sum(LED_WATTAGES), 2),
        "estimated": True,
        "note": "Estimated from LED on-time, configured wattage, and configured energy rate.",
        "periods": {
            "today": today,
            "week": week,
            "month": month,
        },
        "predictions": {
            "today": prediction_for_period("today", day_start, day_start + timedelta(days=1), today),
            "week": prediction_for_period("week", week_start, week_start + timedelta(days=7), week),
            "month": prediction_for_period("month", month_start, next_month_start(now), month),
        },
        "charts": {
            "last_7_days": usage_series_for_days("day", last_7_start, 7, now),
            "month_days": usage_series_for_days("month_day", month_start, month_day_count(now), now),
            "zones": month_zones,
        },
        "zones": month_zones,
    }


usage_events = load_usage_events()


def automation_snapshot():
    with automation_lock:
        visible = [
            {
                "id": item["id"],
                "label": item["label"],
                "run_at": item["run_at"].isoformat(timespec="minutes"),
                "status": item["status"],
            }
            for item in automations
            if item["status"] in {"scheduled", "running", "failed"}
        ]
    return visible[-8:]


def snapshot_state():
    with state_lock:
        snapshot = deepcopy(device_state)
    snapshot["server_time"] = now_iso()
    snapshot["local_time"] = local_now_iso()
    snapshot["control_pin_required"] = bool(CONTROL_PIN)
    snapshot["automations"] = automation_snapshot()
    snapshot["assistant_mode"] = "OpenAI" if OPENAI_API_KEY else "Local"
    snapshot["usage"] = usage_snapshot()
    return snapshot


def broadcast_state():
    snapshot = snapshot_state()
    with listeners_lock:
        dead_listeners = []
        for listener in listeners:
            try:
                listener.put_nowait(snapshot)
            except queue.Full:
                dead_listeners.append(listener)

        for listener in dead_listeners:
            listeners.discard(listener)


def set_mqtt_connected(is_connected, error=None):
    with state_lock:
        device_state["mqtt_connected"] = is_connected
        device_state["last_error"] = error
        if not is_connected:
            device_state["device_online"] = False
            device_state["device_status"] = "MQTT broker disconnected"
    broadcast_state()


def apply_status_payload(payload, is_retained=False):
    try:
        status = json.loads(payload)
    except json.JSONDecodeError:
        with state_lock:
            device_state["last_error"] = "ESP32 sent invalid status JSON"
        broadcast_state()
        return

    incoming_leds = status.get("leds", [])
    received_at = now_iso()
    usage_updates = []
    with state_lock:
        for incoming in incoming_leds:
            led_id = int(incoming.get("id", 0))
            if 1 <= led_id <= LED_COUNT:
                led = device_state["leds"][led_id - 1]
                next_state = bool(incoming.get("state", False))
                if not is_retained and led["state"] != next_state:
                    usage_updates.append((led_id, next_state))
                led["pin"] = incoming.get("pin", led["pin"])
                led["state"] = next_state
                led["name"] = LED_NAMES[led_id - 1]

        if is_retained:
            device_state["last_retained_status"] = received_at
            if not device_state["device_online"]:
                device_state["device_status"] = "Last known state loaded; waiting for ESP32 heartbeat"
        else:
            device_state["last_seen"] = received_at
            device_state["device_online"] = True
            device_state["device_status"] = "ESP32 online"
            device_state["last_error"] = None

    for led_id, next_state in usage_updates:
        record_led_state_change(led_id, next_state)

    broadcast_state()


def refresh_device_presence():
    should_broadcast = False

    with state_lock:
        last_seen = parse_iso_datetime(device_state.get("last_seen"))
        is_stale = (
            last_seen is None
            or utc_now() - last_seen > timedelta(seconds=DEVICE_HEARTBEAT_TIMEOUT_SECONDS)
        )

        next_online = bool(device_state["mqtt_connected"] and not is_stale)
        if device_state["device_online"] != next_online:
            device_state["device_online"] = next_online
            should_broadcast = True

        if not device_state["mqtt_connected"]:
            next_status = "MQTT broker disconnected"
        elif last_seen is None:
            next_status = "Waiting for ESP32 heartbeat"
        elif is_stale:
            next_status = f"ESP32 offline; no heartbeat for {DEVICE_HEARTBEAT_TIMEOUT_SECONDS}s"
        else:
            next_status = "ESP32 online"

        if device_state["device_status"] != next_status:
            device_state["device_status"] = next_status
            should_broadcast = True

    if should_broadcast:
        broadcast_state()


def update_led_local(led_id, is_on):
    should_record_usage = False
    with state_lock:
        if 1 <= led_id <= LED_COUNT:
            should_record_usage = device_state["leds"][led_id - 1]["state"] != is_on
            device_state["leds"][led_id - 1]["state"] = is_on
        device_state["last_error"] = None

    if should_record_usage:
        record_led_state_change(led_id, is_on)

    broadcast_state()


def update_all_leds_local(is_on):
    usage_updates = []
    with state_lock:
        for led in device_state["leds"]:
            if led["state"] != is_on:
                usage_updates.append((led["id"], is_on))
            led["state"] = is_on
        device_state["last_error"] = None

    for led_id, next_state in usage_updates:
        record_led_state_change(led_id, next_state)

    broadcast_state()


def parse_state(value):
    if isinstance(value, bool):
        return value

    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"1", "true", "on", "yes"}:
            return True
        if normalized in {"0", "false", "off", "no"}:
            return False

    return None


def pin_error_response():
    return jsonify({"ok": False, "error": "Invalid control PIN"}), 401


def require_control_pin():
    if not CONTROL_PIN:
        return None

    payload = request.get_json(silent=True) or {}
    supplied_pin = (
        request.headers.get("X-Control-Pin")
        or request.args.get("pin")
        or payload.get("pin")
        or ""
    ).strip()

    if supplied_pin != CONTROL_PIN:
        return pin_error_response()

    return None


def publish_command(path, payload, retain=False):
    result = mqtt_client.publish(topic(path), payload, qos=1, retain=retain)
    if result.rc != mqtt.MQTT_ERR_SUCCESS:
        message = f"MQTT publish failed with code {result.rc}"
        with state_lock:
            device_state["last_error"] = message
        broadcast_state()
        return False, message

    return True, None


def execute_led_command(led_id, desired_state):
    ok, error = publish_command(f"cmd/led/{led_id}", "on" if desired_state else "off", retain=True)
    if ok:
        update_led_local(led_id, desired_state)
    return ok, error


def execute_all_leds(desired_state):
    for led_id in range(1, LED_COUNT + 1):
        ok, error = publish_command(f"cmd/led/{led_id}", "on" if desired_state else "off", retain=True)
        if not ok:
            return False, error

    update_all_leds_local(desired_state)
    return True, None


def mqtt_reason_code_value(reason_code):
    try:
        return int(reason_code)
    except (TypeError, ValueError):
        return 0 if str(reason_code).lower() == "success" else 1


def on_connect(client, userdata, flags, reason_code, properties=None):
    if mqtt_reason_code_value(reason_code) == 0:
        client.subscribe(topic("status"), qos=1)
        set_mqtt_connected(True)
    else:
        set_mqtt_connected(False, f"MQTT connect failed with code {reason_code}")


def on_disconnect(client, userdata, *args):
    set_mqtt_connected(False, "MQTT disconnected")


def on_message(client, userdata, message):
    if message.topic == topic("status"):
        apply_status_payload(message.payload.decode("utf-8", errors="replace"), is_retained=message.retain)


def zone_label(led_id):
    if 1 <= led_id <= LED_COUNT:
        return LED_NAMES[led_id - 1]
    return f"LED {led_id}"


def led_status_summary():
    with state_lock:
        leds = deepcopy(device_state["leds"])

    on_zones = [led["name"] for led in leds if led["state"]]
    off_zones = [led["name"] for led in leds if not led["state"]]

    if not on_zones:
        return "All home lights are currently off."
    if not off_zones:
        return "All home lights are currently on."

    return f"On: {', '.join(on_zones)}. Off: {', '.join(off_zones)}."


def format_bill_amount(value):
    return f"{ENERGY_CURRENCY} {value:.2f}"


def is_usage_query(text):
    normalized = text.lower()
    usage_terms = [
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
        "stats",
    ]
    return any(re.search(rf"\b{re.escape(term)}\b", normalized) for term in usage_terms)


def is_capability_query(text):
    normalized = text.lower()
    return any(
        phrase in normalized
        for phrase in [
            "what can you do",
            "what ai can do",
            "what are your features",
            "what are the features",
            "help",
            "commands",
            "capabilities",
        ]
    )


def usage_period_from_text(text):
    normalized = text.lower()
    if any(word in normalized for word in ["today", "day", "daily"]):
        return "today"
    if any(word in normalized for word in ["week", "weekly"]):
        return "week"
    return "month"


def usage_chat_reply(text):
    snapshot = usage_snapshot()
    period_name = usage_period_from_text(text)
    period = snapshot["periods"][period_name]
    prediction = snapshot["predictions"][period_name]
    top_zones = [zone for zone in snapshot["zones"] if zone["kwh"] > 0][:3]

    period_label = "today" if period_name == "today" else f"this {period_name}"
    reply = (
        f"Estimated usage for {period_label}: {period['kwh']:.4f} kWh, "
        f"{period['hours']:.2f} light-hours, current bill {format_bill_amount(period['cost'])}. "
        f"Projected {period_name} bill: {format_bill_amount(prediction['cost'])} "
        f"at {ENERGY_RATE_PER_KWH:.2f} {ENERGY_CURRENCY}/kWh."
    )

    if top_zones:
        zone_text = ", ".join(
            f"{zone['name']} {zone['kwh']:.4f} kWh"
            for zone in top_zones
        )
        reply += f" Highest usage zones this month: {zone_text}."

    reply += " This is an estimate from on-time and configured wattage, not a meter reading."
    return reply


def assistant_capabilities_reply():
    return (
        "I can control individual zones or all lights, schedule lights, answer status questions, "
        "estimate day/week/month energy usage and bill, predict usage and bill from current patterns, "
        "and suggest energy-saving actions such as turning off zones when nobody is home."
    )


def zone_aliases():
    aliases = {}
    number_words = ["one", "two", "three", "four", "five"]

    for index, name in enumerate(LED_NAMES, start=1):
        normalized_name = name.lower()
        aliases[normalized_name] = index
        aliases[f"led {index}"] = index
        aliases[f"light {index}"] = index
        if index <= len(number_words):
            aliases[f"led {number_words[index - 1]}"] = index
            aliases[f"light {number_words[index - 1]}"] = index

        for token in re.findall(r"[a-z0-9]+", normalized_name):
            if len(token) >= 4:
                aliases[token] = index

    aliases.update(
        {
            "gate": 1,
            "living": 1,
            "hall": 1,
            "kitchen": 2,
            "bedroom": 3,
            "bed": 3,
            "front": 4,
            "door": 4,
            "porch": 4,
            "garage": 5,
            "parking": 5,
        }
    )
    return aliases


def resolve_zone_ids(text):
    normalized = text.lower()
    found = []
    for alias, led_id in zone_aliases().items():
        if re.search(rf"\b{re.escape(alias)}\b", normalized) and led_id not in found:
            found.append(led_id)
    return found


def parse_desired_light_state(text):
    normalized = text.lower()
    off_patterns = [
        r"\b(turn|switch|put|power)\s+off\b",
        r"\blights?\s+off\b",
        r"\bleds?\s+off\b",
        r"\ball\s+off\b",
        r"\boff\s+(all|lights?|leds?)\b",
        r"\b(close|closed|closing|shutdown|shut down|power down)\b",
    ]
    on_patterns = [
        r"\b(turn|switch|put|power)\s+on\b",
        r"\blights?\s+on\b",
        r"\bleds?\s+on\b",
        r"\ball\s+on\b",
        r"\bon\s+(all|lights?|leds?)\b",
        r"\b(open|opened|start|power up)\b",
    ]

    if any(re.search(pattern, normalized) for pattern in off_patterns):
        return False
    if any(re.search(pattern, normalized) for pattern in on_patterns):
        return True
    return None


def parse_contextual_light_intent(text):
    normalized = text.lower()
    unavailable_patterns = [
        r"\bnot\s+available\b",
        r"\bnot\s+at\s+home\b",
        r"\bnot\s+home\b",
        r"\baway\s+from\s+home\b",
        r"\bi\s*(am|'m)?\s*away\b",
        r"\bi\s*(am|'m)?\s*leaving\b",
        r"\bi\s*(will|would|am\s+going\s+to|plan\s+to|have\s+to|need\s+to)?\s*leave\s+(the\s+)?(home|house)\b",
        r"\bleave\s+(the\s+)?(home|house)\b",
        r"\bleaving\s+(the\s+)?(home|house)\b",
        r"\bout\s+of\s+(home|house)\b",
        r"\bno\s+one\s+(is\s+)?home\b",
        r"\bnobody\s+(is\s+)?home\b",
        r"\bempty\s+(home|house)\b",
    ]

    if any(re.search(pattern, normalized) for pattern in unavailable_patterns):
        return {
            "state": False,
            "delay_after_time": timedelta(minutes=2),
            "reason": "you said you will not be available",
        }

    return None


def parse_schedule_time(text):
    normalized = text.lower()
    now = local_now()

    relative_match = re.search(r"\b(?:in|after)\s+(\d{1,3})\s*(minute|minutes|min|hour|hours|hr|hrs)\b", normalized)
    if relative_match:
        amount = int(relative_match.group(1))
        unit = relative_match.group(2)
        if unit.startswith(("hour", "hr")):
            return now + timedelta(hours=amount)
        return now + timedelta(minutes=amount)

    def scheduled_datetime(hour, minute):
        run_date = now.date()
        if "tomorrow" in normalized:
            run_date = run_date + timedelta(days=1)

        run_at = datetime.combine(run_date, datetime.min.time(), tzinfo=APP_TZ).replace(hour=hour, minute=minute)
        if "today" not in normalized and "tomorrow" not in normalized and run_at <= now:
            run_at = run_at + timedelta(days=1)
        return run_at

    clock_match = re.search(r"\b(\d{1,2})(?:\s*:\s*(\d{1,2}))?\s*(am|pm)\b", normalized)
    if clock_match:
        hour = int(clock_match.group(1))
        minute = int(clock_match.group(2) or "0")
        meridiem = clock_match.group(3)

        if not 1 <= hour <= 12 or not 0 <= minute <= 59:
            return None

        if meridiem == "pm" and hour != 12:
            hour += 12
        elif meridiem == "am" and hour == 12:
            hour = 0

        return scheduled_datetime(hour, minute)

    clock_24_match = re.search(r"\b(?:at|by|around|on)\s+([01]?\d|2[0-3])\s*:\s*([0-5]?\d)\b", normalized)
    if clock_24_match:
        return scheduled_datetime(int(clock_24_match.group(1)), int(clock_24_match.group(2)))

    bare_hour_match = re.search(r"\b(?:at|by|around)\s+(\d{1,2})(?:\s*o'?clock)?\b", normalized)
    if bare_hour_match:
        hour = int(bare_hour_match.group(1))
        if not 1 <= hour <= 23:
            return None

        candidate_hours = [hour]
        if hour <= 11:
            candidate_hours.append(hour + 12)

        candidates = [scheduled_datetime(candidate_hour, 0) for candidate_hour in candidate_hours]
        return min(candidates)

    return None


def format_local_time(value):
    return value.strftime("%I:%M %p").lstrip("0")


def format_schedule_time(value):
    local_value = value.astimezone(APP_TZ) if value.tzinfo else value.replace(tzinfo=APP_TZ)
    today = local_now().date()

    if local_value.date() == today:
        return f"at {format_local_time(local_value)}"
    if local_value.date() == today + timedelta(days=1):
        return f"tomorrow at {format_local_time(local_value)}"

    return f"on {local_value.strftime('%d %b %Y')} at {format_local_time(local_value)}"


def schedule_light_action(target, desired_state, run_at, led_id=None, reason=""):
    state_word = "on" if desired_state else "off"
    if target == "all":
        label = f"Turn all lights {state_word} {format_schedule_time(run_at)}"
    else:
        label = f"Turn {zone_label(led_id)} {state_word} {format_schedule_time(run_at)}"

    automation = {
        "id": f"auto-{int(time.time() * 1000)}",
        "target": target,
        "led_id": led_id,
        "state": desired_state,
        "run_at": run_at,
        "label": label,
        "reason": reason,
        "status": "scheduled",
    }

    with automation_lock:
        automations.append(automation)

    broadcast_state()
    return automation


def parse_action_time(value):
    if not value:
        return None

    try:
        normalized = str(value).replace("Z", "+00:00")
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return parse_schedule_time(str(value))

    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=APP_TZ)
    return parsed.astimezone(APP_TZ)


def run_scheduled_automation(automation):
    if automation["target"] == "all":
        return execute_all_leds(automation["state"])
    return execute_led_command(automation["led_id"], automation["state"])


def automation_scheduler_loop():
    while True:
        due_items = []
        now = local_now()

        with automation_lock:
            for automation in automations:
                if automation["status"] == "scheduled" and automation["run_at"] <= now:
                    automation["status"] = "running"
                    due_items.append(automation.copy())

        for automation in due_items:
            ok, error = run_scheduled_automation(automation)
            with automation_lock:
                for item in automations:
                    if item["id"] == automation["id"]:
                        item["status"] = "completed" if ok else "failed"
                        if error:
                            item["error"] = error
                        break
            broadcast_state()

        time.sleep(2)


def device_presence_loop():
    while True:
        refresh_device_presence()
        time.sleep(2)


def local_assistant_plan(message):
    if is_capability_query(message):
        return {"reply": assistant_capabilities_reply(), "actions": []}

    if is_usage_query(message):
        return {"reply": usage_chat_reply(message), "actions": []}

    desired_state = parse_desired_light_state(message)
    run_at = parse_schedule_time(message)
    contextual_intent = parse_contextual_light_intent(message)
    contextual_schedule_note = ""

    if contextual_intent and desired_state is None:
        desired_state = contextual_intent["state"]

    if contextual_intent and run_at:
        delay_after_time = contextual_intent.get("delay_after_time")
        if delay_after_time:
            run_at = run_at + delay_after_time
            contextual_schedule_note = f", two minutes after {contextual_intent['reason']}"

    zone_ids = resolve_zone_ids(message)
    normalized = message.lower()
    is_all_target = any(word in normalized for word in ["home", "house", "all", "everything", "entire"])

    if any(word in normalized for word in ["status", "which", "what", "show"]):
        return {"reply": led_status_summary(), "actions": []}

    if desired_state is None:
        return {
            "reply": "I can switch home zones on or off, or schedule them. Try: home closes at 6 pm, turn on Living Room, or switch off Kitchen.",
            "actions": [],
        }

    target_all = is_all_target or not zone_ids

    if run_at:
        if target_all:
            return {
                "reply": f"Scheduled all home lights to turn {'on' if desired_state else 'off'} {format_schedule_time(run_at)}{contextual_schedule_note}.",
                "actions": [
                    {
                        "type": "schedule_all",
                        "state": desired_state,
                        "run_at": run_at.isoformat(),
                        "reason": message,
                    }
                ],
            }

        return {
            "reply": f"Scheduled {', '.join(zone_label(led_id) for led_id in zone_ids)} to turn {'on' if desired_state else 'off'} {format_schedule_time(run_at)}{contextual_schedule_note}.",
            "actions": [
                {
                    "type": "schedule_led",
                    "led_id": led_id,
                    "state": desired_state,
                    "run_at": run_at.isoformat(),
                    "reason": message,
                }
                for led_id in zone_ids
            ],
        }

    if target_all:
        return {
            "reply": f"Turning {'on' if desired_state else 'off'} all home lights.",
            "actions": [{"type": "set_all", "state": desired_state, "reason": message}],
        }

    return {
        "reply": f"Turning {'on' if desired_state else 'off'} {', '.join(zone_label(led_id) for led_id in zone_ids)}.",
        "actions": [
            {"type": "set_led", "led_id": led_id, "state": desired_state, "reason": message}
            for led_id in zone_ids
        ],
    }


def extract_openai_text(response):
    if response.get("output_text"):
        return response["output_text"]

    for item in response.get("output", []):
        for content in item.get("content", []):
            if content.get("type") in {"output_text", "text"} and content.get("text"):
                return content["text"]

    raise ValueError("OpenAI response did not include text")


def call_openai_assistant(message, history):
    zones = ", ".join(f"{index + 1}: {name}" for index, name in enumerate(LED_NAMES))
    current_light_state = led_status_summary()
    usage = usage_snapshot()
    usage_context = (
        f"Estimated usage today: {usage['periods']['today']['kwh']:.4f} kWh, "
        f"{format_bill_amount(usage['periods']['today']['cost'])}. "
        f"This week: {usage['periods']['week']['kwh']:.4f} kWh, "
        f"{format_bill_amount(usage['periods']['week']['cost'])}. "
        f"This month: {usage['periods']['month']['kwh']:.4f} kWh, "
        f"{format_bill_amount(usage['periods']['month']['cost'])}. "
        f"Projected month bill: {format_bill_amount(usage['predictions']['month']['cost'])}. "
        f"Energy rate: {usage['rate_per_kwh']:.2f} {usage['currency']}/kWh. "
        f"Installed load: {usage['installed_watts']:.2f} W. "
        "Usage is estimated from LED on-time and configured wattage, not direct metering."
    )
    with state_lock:
        device_status = device_state.get("device_status", "Unknown")
        last_seen = device_state.get("last_seen") or "not seen yet"

    system_prompt = (
        "You are Echo Home, a ChatGPT-style home assistant and the decision maker for a home lighting controller. "
        "Chat naturally and helpfully for normal conversation, while also extracting clear lighting actions from the user's messages. "
        "Understand conversational requests, decide the best lighting action, and return concise confirmations when a control action is needed. "
        "Prioritize safety, energy saving, clear user intent, and predictable behavior. "
        f"Current local time: {local_now_iso()}. Home zones: {zones}. "
        f"Current light state: {current_light_state} Device status: {device_status}. Last ESP32 heartbeat: {last_seen}. "
        f"{usage_context} "
        "For whole-home phrases, control all lights. For named rooms or zones, control only those zones. "
        "For ordinary chat, greetings, explanations, or questions that do not need a lighting change, reply normally and return no actions. "
        "For short commands such as 'kitchen on', 'bedroom off', or 'lights off at 8 pm', infer the on/off state, target zone, and schedule time. "
        "If the user asks about usage, bill, cost, statistics, or prediction, answer using the estimated usage data and return no actions. "
        "If the user asks what you can do, explain the available lighting, scheduling, usage, bill, and prediction capabilities. "
        "If the user says the home is closing, everyone is leaving, or it is bedtime at a time, schedule all lights off at that time. "
        "If the user says they are not available, away, not at home, leaving, or nobody is home at a time, schedule the relevant lights off two minutes after that time. "
        "If a request is only asking for status, use a status action and explain the current state. "
        "If the request is ambiguous or unsafe, reply with a short clarification and no actions. "
        "Only use these action types: set_all, set_led, schedule_all, schedule_led, status. "
        "Use ISO 8601 local timestamps for scheduled run_at values. "
        "Never invent zones, devices, sensors, or capabilities that are not listed."
    )
    compact_history = [
        {"role": item.get("role", "user"), "content": str(item.get("content", ""))[:500]}
        for item in history[-8:]
        if item.get("role") in {"user", "assistant"} and item.get("content")
    ]

    input_messages = [
        {"role": "system", "content": [{"type": "input_text", "text": system_prompt}]}
    ]
    for item in compact_history:
        input_messages.append(
            {
                "role": item["role"],
                "content": [{"type": "input_text", "text": item["content"]}],
            }
        )
    input_messages.append({"role": "user", "content": [{"type": "input_text", "text": message}]})

    schema = {
        "type": "object",
        "properties": {
            "reply": {"type": "string"},
            "actions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "type": {
                            "type": "string",
                            "enum": ["set_all", "set_led", "schedule_all", "schedule_led", "status"],
                        },
                        "state": {"type": ["boolean", "null"]},
                        "led_id": {"type": ["integer", "null"]},
                        "run_at": {"type": ["string", "null"]},
                        "reason": {"type": "string"},
                    },
                    "required": ["type", "state", "led_id", "run_at", "reason"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["reply", "actions"],
        "additionalProperties": False,
    }

    request_body = {
        "model": OPENAI_MODEL,
        "input": input_messages,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "echo_home_action_plan",
                "schema": schema,
                "strict": True,
            }
        },
    }

    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(request_body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {OPENAI_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    with urllib.request.urlopen(request, timeout=25) as response:
        response_body = json.loads(response.read().decode("utf-8"))

    return json.loads(extract_openai_text(response_body))


def build_assistant_plan(message, history):
    if not OPENAI_API_KEY:
        return local_assistant_plan(message), "Local"

    try:
        return call_openai_assistant(message, history), "OpenAI"
    except (urllib.error.URLError, urllib.error.HTTPError, ValueError, json.JSONDecodeError) as exc:
        plan = local_assistant_plan(message)
        plan["reply"] = f"{plan['reply']} I used the local automation planner because the AI service was unavailable."
        plan["error"] = str(exc)
        return plan, "Local"


def apply_assistant_actions(actions, transport="server"):
    results = []
    client_handles_immediate = transport == "bluetooth"

    for action in actions:
        action_type = action.get("type")
        desired_state = action.get("state")
        led_id = action.get("led_id")
        reason = action.get("reason", "")

        if action_type == "status":
            results.append({"type": action_type, "ok": True})
            continue

        if not isinstance(desired_state, bool):
            results.append({"type": action_type, "ok": False, "error": "Missing light state"})
            continue

        if action_type == "set_all":
            if client_handles_immediate:
                results.append(
                    {
                        "type": action_type,
                        "ok": True,
                        "state": desired_state,
                        "transport": "bluetooth",
                        "client_action": True,
                    }
                )
                continue

            ok, error = execute_all_leds(desired_state)
            results.append({"type": action_type, "ok": ok, "error": error, "state": desired_state})
        elif action_type == "set_led":
            if not isinstance(led_id, int) or not 1 <= led_id <= LED_COUNT:
                results.append({"type": action_type, "ok": False, "error": "Unknown zone"})
                continue

            if client_handles_immediate:
                results.append(
                    {
                        "type": action_type,
                        "ok": True,
                        "state": desired_state,
                        "led_id": led_id,
                        "transport": "bluetooth",
                        "client_action": True,
                    }
                )
                continue

            ok, error = execute_led_command(led_id, desired_state)
            results.append({"type": action_type, "ok": ok, "error": error, "led_id": led_id, "state": desired_state})
        elif action_type in {"schedule_all", "schedule_led"}:
            run_at = parse_action_time(action.get("run_at"))
            if not run_at:
                results.append({"type": action_type, "ok": False, "error": "Missing schedule time"})
                continue

            state_word = "on" if desired_state else "off"

            if action_type == "schedule_all":
                label = f"Turn all lights {state_word} {format_schedule_time(run_at)}"
                if client_handles_immediate:
                    results.append(
                        {
                            "type": action_type,
                            "ok": True,
                            "state": desired_state,
                            "run_at": run_at.isoformat(timespec="seconds"),
                            "automation": label,
                            "transport": "bluetooth",
                            "client_action": True,
                        }
                    )
                    continue
                automation = schedule_light_action("all", desired_state, run_at, reason=reason)
            else:
                if not isinstance(led_id, int) or not 1 <= led_id <= LED_COUNT:
                    results.append({"type": action_type, "ok": False, "error": "Unknown zone"})
                    continue
                label = f"Turn {zone_label(led_id)} {state_word} {format_schedule_time(run_at)}"
                if client_handles_immediate:
                    results.append(
                        {
                            "type": action_type,
                            "ok": True,
                            "state": desired_state,
                            "led_id": led_id,
                            "run_at": run_at.isoformat(timespec="seconds"),
                            "automation": label,
                            "transport": "bluetooth",
                            "client_action": True,
                        }
                    )
                    continue
                automation = schedule_light_action("led", desired_state, run_at, led_id=led_id, reason=reason)
            results.append(
                {
                    "type": action_type,
                    "ok": True,
                    "state": desired_state,
                    "led_id": led_id,
                    "run_at": run_at.isoformat(timespec="seconds"),
                    "automation": automation["label"],
                }
            )
        else:
            results.append({"type": action_type, "ok": False, "error": "Unsupported action"})

    return results


mqtt_client = mqtt.Client(
    mqtt.CallbackAPIVersion.VERSION2,
    client_id=f"led-web-{os.getpid()}-{int(time.time())}",
    protocol=mqtt.MQTTv311,
)
mqtt_client.on_connect = on_connect
mqtt_client.on_disconnect = on_disconnect
mqtt_client.on_message = on_message

if MQTT_USERNAME:
    mqtt_client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

try:
    mqtt_client.connect_async(MQTT_HOST, MQTT_PORT, keepalive=60)
    mqtt_client.loop_start()
except Exception as exc:
    set_mqtt_connected(False, str(exc))

scheduler_thread = threading.Thread(target=automation_scheduler_loop, daemon=True)
scheduler_thread.start()
presence_thread = threading.Thread(target=device_presence_loop, daemon=True)
presence_thread.start()


@app.get("/")
def index():
    return render_template(
        "index.html",
        app_config={
            "ledCount": LED_COUNT,
            "pinRequired": bool(CONTROL_PIN),
            "ledNames": LED_NAMES,
        },
    )


@app.get("/api/status")
def api_status():
    return jsonify(snapshot_state())


@app.get("/api/events")
def api_events():
    def event_stream():
        listener = queue.Queue(maxsize=16)
        with listeners_lock:
            listeners.add(listener)

        listener.put(snapshot_state())

        try:
            while True:
                try:
                    event = listener.get(timeout=20)
                    yield f"data: {json.dumps(event)}\n\n"
                except queue.Empty:
                    yield ": keepalive\n\n"
        finally:
            with listeners_lock:
                listeners.discard(listener)

    return Response(
        stream_with_context(event_stream()),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/api/led/<int:led_id>")
def api_set_led(led_id):
    pin_error = require_control_pin()
    if pin_error:
        return pin_error

    if led_id < 1 or led_id > LED_COUNT:
        return jsonify({"ok": False, "error": "Unknown LED"}), 404

    payload = request.get_json(silent=True) or {}
    desired_state = parse_state(payload.get("state"))
    if desired_state is None:
        return jsonify({"ok": False, "error": "state must be on/off"}), 400

    ok, error = execute_led_command(led_id, desired_state)
    if not ok:
        return jsonify({"ok": False, "error": error}), 502

    return jsonify({"ok": True, "led": led_id, "state": desired_state})


@app.post("/api/all")
def api_set_all():
    pin_error = require_control_pin()
    if pin_error:
        return pin_error

    payload = request.get_json(silent=True) or {}
    desired_state = parse_state(payload.get("state"))
    if desired_state is None:
        return jsonify({"ok": False, "error": "state must be on/off"}), 400

    ok, error = execute_all_leds(desired_state)
    if not ok:
        return jsonify({"ok": False, "error": error}), 502

    return jsonify({"ok": True, "state": desired_state})


@app.post("/api/assistant/chat")
def api_assistant_chat():
    pin_error = require_control_pin()
    if pin_error:
        return pin_error

    payload = request.get_json(silent=True) or {}
    message = str(payload.get("message", "")).strip()
    history = payload.get("history", [])

    if not message:
        return jsonify({"ok": False, "error": "message is required"}), 400

    if not isinstance(history, list):
        history = []

    plan, mode = build_assistant_plan(message, history)
    transport = str(payload.get("transport", "server")).strip().lower()
    if transport not in {"server", "bluetooth"}:
        transport = "server"

    action_results = apply_assistant_actions(plan.get("actions", []), transport=transport)

    return jsonify(
        {
            "ok": True,
            "mode": mode,
            "reply": plan.get("reply", "Done."),
            "actions": action_results,
            "state": snapshot_state(),
        }
    )


@app.post("/api/display")
def api_display_message():
    pin_error = require_control_pin()
    if pin_error:
        return pin_error

    payload = request.get_json(silent=True) or {}
    message = str(payload.get("message", "")).strip()
    if not message:
        return jsonify({"ok": False, "error": "message is required"}), 400

    ok, error = publish_command("cmd/display", message[:80], retain=True)
    if not ok:
        return jsonify({"ok": False, "error": error}), 502

    return jsonify({"ok": True, "message": message[:80]})


@app.get("/api/usage")
def api_usage():
    return jsonify(usage_snapshot())


@app.get("/healthz")
def healthz():
    snapshot = snapshot_state()
    return jsonify(
        {
            "ok": True,
            "mqtt_connected": snapshot["mqtt_connected"],
            "device_online": snapshot["device_online"],
        }
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT, threaded=True)
