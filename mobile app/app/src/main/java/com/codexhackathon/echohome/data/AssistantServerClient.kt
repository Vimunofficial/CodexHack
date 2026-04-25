package com.codexhackathon.echohome.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

class AssistantServerClient {
    suspend fun chat(
        serverUrl: String,
        controlPin: String,
        message: String,
        history: List<ChatMessage>,
        transport: TransportMode
    ): AssistantServerResult = withContext(Dispatchers.IO) {
        val endpoint = URL("${normalizeBaseUrl(serverUrl)}/api/assistant/chat")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (controlPin.isNotBlank()) {
                setRequestProperty("X-Control-Pin", controlPin.trim())
            }
        }

        val payload = JSONObject()
            .put("message", message)
            .put(
                "history",
                JSONArray().apply {
                    history.takeLast(8).forEach { item ->
                        put(
                            JSONObject()
                                .put("role", item.role)
                                .put("content", item.content)
                        )
                    }
                }
            )
            .put("transport", if (transport == TransportMode.Bluetooth) "bluetooth" else "server")

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        val root = JSONObject(body.ifBlank { "{}" })

        if (statusCode !in 200..299 || root.optBoolean("ok", false).not()) {
            throw IllegalStateException(root.optString("error", "Assistant server failed"))
        }

        AssistantServerResult(
            reply = root.optString("reply", "Done."),
            mode = root.optString("mode", "OpenAI"),
            actions = parseActions(root.optJSONArray("actions")),
            leds = parseLeds(root.optJSONObject("state"))
        )
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("AI server URL is empty")
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun parseActions(array: JSONArray?): List<AssistantAction> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.has("ok") && !item.optBoolean("ok", true)) continue
                val type = when (item.optString("type")) {
                    "set_all" -> AssistantActionType.SetAll
                    "set_led" -> AssistantActionType.SetLed
                    "schedule_all" -> AssistantActionType.ScheduleAll
                    "schedule_led" -> AssistantActionType.ScheduleLed
                    "status" -> AssistantActionType.Status
                    else -> continue
                }
                val runAt = item.optString("run_at", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }

                add(
                    AssistantAction(
                        type = type,
                        state = if (item.has("state") && !item.isNull("state")) item.optBoolean("state") else null,
                        ledId = if (item.has("led_id") && !item.isNull("led_id")) item.optInt("led_id") else null,
                        runAt = runAt,
                        reason = item.optString("reason", "")
                    )
                )
            }
        }
    }

    private fun parseLeds(state: JSONObject?): List<LedZone>? {
        val ledArray = state?.optJSONArray("leds") ?: return null
        return buildList {
            for (index in 0 until ledArray.length()) {
                val item = ledArray.optJSONObject(index) ?: continue
                val ledId = item.optInt("id", index + 1)
                add(
                    LedZone(
                        id = ledId,
                        name = item.optString(
                            "name",
                            EchoConfig.LedNames.getOrElse(ledId - 1) { "LED $ledId" }
                        ),
                        pin = if (item.has("pin") && !item.isNull("pin")) item.optInt("pin") else EchoConfig.LedPins.getOrNull(ledId - 1),
                        isOn = item.optBoolean("state", false)
                    )
                )
            }
        }
    }
}
