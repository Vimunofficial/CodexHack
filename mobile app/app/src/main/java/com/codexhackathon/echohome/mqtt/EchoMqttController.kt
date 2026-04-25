package com.codexhackathon.echohome.mqtt

import com.codexhackathon.echohome.data.EchoConfig
import com.codexhackathon.echohome.data.LedZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class EchoMqttController(
    private val onConnectionChanged: (connected: Boolean, status: String) -> Unit,
    private val onStatusReceived: (leds: List<LedZone>) -> Unit
) {
    private val serverUri = "tcp://${EchoConfig.MQTT_HOST}:${EchoConfig.MQTT_PORT}"
    private val clientId = "echo-home-android-${System.currentTimeMillis()}"
    private val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
    private val connectionLock = Any()
    private var connectionWaiter: CompletableDeferred<Unit>? = null

    init {
        client.setCallback(
            object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    handleConnected(if (reconnect) "MQTT broker reconnected" else "MQTT broker connected")
                }

                override fun connectionLost(cause: Throwable?) {
                    completeConnectionFailure(cause ?: IllegalStateException("MQTT broker disconnected"))
                    onConnectionChanged(false, cause?.message ?: "MQTT broker disconnected")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == EchoConfig.MQTT_STATUS_TOPIC && message != null) {
                        parseStatus(String(message.payload, Charsets.UTF_8))?.let(onStatusReceived)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            }
        )
    }

    fun connect() {
        if (client.isConnected) {
            handleConnected("MQTT broker connected")
            return
        }

        val shouldStart = synchronized(connectionLock) {
            val activeWaiter = connectionWaiter
            if (activeWaiter != null && activeWaiter.isActive) {
                false
            } else {
                connectionWaiter = CompletableDeferred()
                true
            }
        }

        if (!shouldStart) {
            onConnectionChanged(false, "Connecting to MQTT broker")
            return
        }

        startConnection()
    }

    fun disconnect() {
        runCatching {
            if (client.isConnected) client.disconnect()
        }
        completeConnectionFailure(IllegalStateException("MQTT broker disconnected"))
        onConnectionChanged(false, "MQTT broker disconnected")
    }

    suspend fun publishLed(ledId: Int, isOn: Boolean) {
        publish("cmd/led/$ledId", if (isOn) "on" else "off", retained = true)
    }

    suspend fun publishAll(isOn: Boolean) {
        publish("cmd/all", if (isOn) "on" else "off", retained = true)
    }

    suspend fun publishDisplayMessage(message: String) {
        publish("cmd/display", message.take(80), retained = true)
    }

    private fun startConnection() {
        if (client.isConnected) {
            handleConnected("MQTT broker connected")
            return
        }

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 8
            keepAliveInterval = 20
            maxInflight = 20
        }

        try {
            onConnectionChanged(false, "Connecting to MQTT broker")
            client.connect(
                options,
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        handleConnected("MQTT broker connected")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        handleConnectionFailure(exception ?: IllegalStateException("MQTT connection failed"))
                    }
                }
            )
        } catch (exception: MqttException) {
            handleConnectionFailure(exception)
        }
    }

    private suspend fun ensureConnected() {
        if (client.isConnected) return

        val (waiter, shouldStart) = synchronized(connectionLock) {
            val activeWaiter = connectionWaiter
            if (activeWaiter != null && activeWaiter.isActive) {
                activeWaiter to false
            } else {
                CompletableDeferred<Unit>().also { connectionWaiter = it } to true
            }
        }

        if (shouldStart) {
            startConnection()
        }

        withTimeout(12_000) {
            waiter.await()
        }
    }

    private fun subscribeStatus() {
        if (!client.isConnected) return
        runCatching {
            client.subscribe(EchoConfig.MQTT_STATUS_TOPIC, 1)
        }
    }

    private suspend fun publish(path: String, payload: String, retained: Boolean) {
        withContext(Dispatchers.IO) {
            ensureConnected()

            val topic = "${EchoConfig.MQTT_PREFIX}/$path"
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = retained
            }

            suspendCoroutine<Unit> { continuation ->
                try {
                    client.publish(
                        topic,
                        message,
                        null,
                        object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                continuation.resume(Unit)
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                continuation.resumeWithException(
                                    exception ?: IllegalStateException("MQTT publish failed")
                                )
                            }
                        }
                    )
                } catch (exception: MqttException) {
                    continuation.resumeWithException(exception)
                }
            }
        }
    }

    private fun handleConnected(status: String) {
        completeConnectionSuccess()
        onConnectionChanged(true, status)
        subscribeStatus()
    }

    private fun handleConnectionFailure(exception: Throwable) {
        completeConnectionFailure(exception)
        onConnectionChanged(false, exception.message ?: "MQTT connection failed")
    }

    private fun completeConnectionSuccess() {
        synchronized(connectionLock) {
            connectionWaiter?.complete(Unit)
            connectionWaiter = null
        }
    }

    private fun completeConnectionFailure(exception: Throwable) {
        synchronized(connectionLock) {
            connectionWaiter?.completeExceptionally(exception)
            connectionWaiter = null
        }
    }

    private fun parseStatus(payload: String): List<LedZone>? {
        return runCatching {
            val root = JSONObject(payload)
            val ledArray = root.optJSONArray("leds") ?: return null
            buildList {
                for (index in 0 until ledArray.length()) {
                    val item = ledArray.getJSONObject(index)
                    val ledId = item.optInt("id", index + 1)
                    add(
                        LedZone(
                            id = ledId,
                            name = EchoConfig.LedNames.getOrElse(ledId - 1) { "LED $ledId" },
                            pin = if (item.has("pin")) item.optInt("pin") else EchoConfig.LedPins.getOrNull(ledId - 1),
                            isOn = item.optBoolean("state", false)
                        )
                    )
                }
            }
        }.getOrNull()
    }
}
