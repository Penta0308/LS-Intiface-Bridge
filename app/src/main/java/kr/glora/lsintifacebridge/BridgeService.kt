package kr.glora.lsintifacebridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class BridgeService : Service() {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var advertisingSet: AdvertisingSet? = null
    private var wsUrl = DEFAULT_WS_URL
    private var currentLevel = 0
    private var webSocketStatus = "disconnected"
    private var bleStatus = "idle"

    // pulsed, waves and so on... added!
    private val patternHandler = Handler(Looper.getMainLooper())
    private var patternRunnable: Runnable? = null
    private var activePattern: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                wsUrl = intent.getStringExtra(EXTRA_WS_URL).orEmpty().ifBlank { DEFAULT_WS_URL }
                connect()
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_TEST_LEVEL -> {
                stopPattern()
                advertiseLevel(intent.getIntExtra(EXTRA_LEVEL, 0))
            }
            ACTION_TEST_PATTERN -> {
                val pattern = intent.getStringExtra(EXTRA_PATTERN)
                if (pattern.isNullOrBlank()) stopPattern() else startPattern(pattern)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect() {
        if (!hasBluetoothPermissions()) {
            publish("Missing Bluetooth permissions", webSocketStatus = "permission denied")
            return
        }

        webSocket?.close(1000, "reconnect")
        webSocket = null
        publish("Connecting to $wsUrl", webSocketStatus = "connecting")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val handshake = """{"identifier":"LVSDevice","address":"8A3D9FAC2A45","version":0}"""
                    webSocket.send(handshake)
                    publish("WSDM handshake sent: $handshake", webSocketStatus = "connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleCommand(text, binaryResponse = false)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleCommand(bytes.utf8(), binaryResponse = true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    stopAdvertising()
                    publish("WebSocket closed: $code $reason", webSocketStatus = "disconnected")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    stopAdvertising()
                    publish("WebSocket failure: ${t.message}", webSocketStatus = "failed")
                }
            },
        )
    }

    private fun handleCommand(commandText: String, binaryResponse: Boolean) {
        val command = commandText.trim()
        publish("<- $command")

        val response = when {
            command.startsWith("DeviceType;") -> "Z:8A3D9FAC2A45:10;"
            command.startsWith("Battery") -> "99;"
            command.startsWith("Status:1;") -> "2;"
            command.startsWith("AutoSwitch:") -> "OK;"
            command.startsWith("Vibrate:") -> {
                // A real command from Intiface should win over a locally running test
                // pattern, otherwise the pattern loop would immediately overwrite it.
                stopPattern()
                advertiseLevel(parseVibrateLevel(command))
                "OK;"
            }
            command.startsWith("PowerOff;") -> {
                advertiseLevel(0)
                "OK;"
            }
            else -> "OK;"
        }

        sendResponse(response, binaryResponse)
    }

    private fun sendResponse(response: String, binary: Boolean) {
        val socket = webSocket ?: return
        if (binary) {
            socket.send(response.encodeToByteArray().toByteString())
        } else {
            socket.send(response)
        }
        publish("-> $response")
    }

    private fun parseVibrateLevel(command: String): Int {
        val value = Regex("""Vibrate:(\d+)""").find(command)?.groupValues?.get(1)?.toIntOrNull()
        return value?.coerceIn(0, 20) ?: 0
    }

    @SuppressLint("MissingPermission")
    private fun advertiseLevel(lovenseLevel: Int) {
        if (!hasBluetoothPermissions()) {
            publish("Missing Bluetooth permissions", bleStatus = "permission denied")
            return
        }

        val index = lovenseLevelToIndex(lovenseLevel)
        val body = manufacturerBody(index)
        currentLevel = index
        publish(
            "[BLE] Lovense ${lovenseLevel.toString().padStart(2, '0')} -> L$index: ${fullPayload(index).toHex()}",
        )

        val advertiser = bluetoothAdvertiser()
        if (advertiser == null) {
            publish("[BLE] Bluetooth LE advertiser unavailable", bleStatus = "advertiser unavailable")
            return
        }

        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, body)
            .build()

        val set = advertisingSet
        if (set != null) {
            publish("[BLE] Updating advertising data", bleStatus = "updating data")
            set.setAdvertisingData(data)
            updateNotification()
            return
        }

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        publish("[BLE] Starting advertising", bleStatus = "starting")
        advertiser.startAdvertisingSet(parameters, data, null, null, null, advertisingCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val advertiser = bluetoothAdvertiser() ?: return
        advertiser.stopAdvertisingSet(advertisingCallback)
        advertisingSet = null
        publish("[BLE] Advertising stopped", bleStatus = "stopped", level = 0)
    }

    private fun shutdown() {
        stopPattern()
        webSocket?.close(1000, "service stopped")
        webSocket = null
        stopAdvertising()
        publish("Bridge stopped", webSocketStatus = "disconnected")
    }

    /**
     * PR proposal: replays [PATTERNS] entries as a looping sequence of advertiseLevel()
     * calls, each held for its given duration. This is purely a local effect - nothing
     * is sent back to Intiface - so it works the same whether or not a session is active,
     * mirroring how the existing test slider drives the BLE side directly.
     */
    private fun startPattern(name: String) {
        val steps = PATTERNS[name]
        if (steps == null) {
            publish("[Pattern] Unknown pattern '$name'")
            return
        }

        stopPattern()
        activePattern = name
        publish("[Pattern] Starting '$name'")

        var stepIndex = 0
        val runnable = object : Runnable {
            override fun run() {
                val (level, holdMs) = steps[stepIndex % steps.size]
                stepIndex++
                advertiseLevel(level)
                patternHandler.postDelayed(this, holdMs)
            }
        }
        patternRunnable = runnable
        patternHandler.post(runnable)
    }

    private fun stopPattern() {
        val runnable = patternRunnable ?: return
        patternHandler.removeCallbacks(runnable)
        patternRunnable = null
        publish("[Pattern] Stopped '$activePattern'")
        activePattern = null
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdvertiser() =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter
            ?.bluetoothLeAdvertiser

    private val advertisingCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?,
            txPower: Int,
            status: Int,
        ) {
            if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                this@BridgeService.advertisingSet = advertisingSet
                publish("[BLE] Advertising started: status=$status tx=${txPower}dBm", bleStatus = "started")
            } else {
                this@BridgeService.advertisingSet = null
                publish("[BLE] Advertising start failed: status=$status", bleStatus = "start failed $status")
            }
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                publish("[BLE] Advertising data updated: status=$status", bleStatus = "data updated")
            } else {
                publish("[BLE] Advertising data update failed: status=$status", bleStatus = "data update failed $status")
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            if (this@BridgeService.advertisingSet == advertisingSet) {
                this@BridgeService.advertisingSet = null
            }
            publish("[BLE] Advertising stopped", bleStatus = "stopped")
        }
    }

    private fun publish(
        message: String,
        webSocketStatus: String? = null,
        bleStatus: String? = null,
        level: Int? = null,
    ) {
        if (webSocketStatus != null) this.webSocketStatus = webSocketStatus
        if (bleStatus != null) this.bleStatus = bleStatus
        if (level != null) this.currentLevel = level

        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_LOG, message)
            .putExtra(EXTRA_WS_STATUS, this.webSocketStatus)
            .putExtra(EXTRA_BLE_STATUS, this.bleStatus)
            .putExtra(EXTRA_LEVEL, this.currentLevel)
        sendBroadcast(intent)
        updateNotification()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification("WS $webSocketStatus / BLE $bleStatus / L$currentLevel"))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("LS Intiface Bridge")
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Bridge status",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun lovenseLevelToIndex(level: Int): Int {
        val clamped = level.coerceIn(0, 20)
        if (clamped == 0) return 0
        // PR note: the previous "scale by 8, +0.5, floor, +1" formula squeezed the 20
        // non-zero Lovense levels (1..20) into the 9 BLE channels unevenly - level 1
        // alone produced index 1, while indexes 3, 5 and 7 each absorbed 3 input levels.
        // That made the gentlest, most frequently used setting the least adjustable one.
        // Plain integer division spreads the 20 inputs over the 9 outputs as evenly as
        // possible (two buckets of 3 levels, seven buckets of 2) and keeps the result
        // within 1..9 by construction, so the trailing coerceIn can be dropped too.
        return (((clamped - 1) * 9) / 20) + 1
    }

    private fun manufacturerBody(index: Int): ByteArray {
        val channel = CHANNELS[index.coerceIn(0, 9)]
        return MANUFACTURER_PREFIX + byteArrayOf(
            ((channel shr 16) and 0xFF).toByte(),
            ((channel shr 8) and 0xFF).toByte(),
            (channel and 0xFF).toByte(),
        )
    }

    private fun fullPayload(index: Int): ByteArray =
        byteArrayOf(
            (MANUFACTURER_ID and 0xFF).toByte(),
            ((MANUFACTURER_ID shr 8) and 0xFF).toByte(),
        ) + manufacturerBody(index)

    private fun ByteArray.toHex(): String =
        joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    companion object {
        const val ACTION_START = "kr.glora.lsintifacebridge.action.START"
        const val ACTION_STOP = "kr.glora.lsintifacebridge.action.STOP"
        const val ACTION_TEST_LEVEL = "kr.glora.lsintifacebridge.action.TEST_LEVEL"
        const val ACTION_TEST_PATTERN = "kr.glora.lsintifacebridge.action.TEST_PATTERN"
        const val ACTION_STATUS = "kr.glora.lsintifacebridge.action.STATUS"

        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_LOG = "log"
        const val EXTRA_WS_STATUS = "ws_status"
        const val EXTRA_BLE_STATUS = "ble_status"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_PATTERN = "pattern"

        private const val DEFAULT_WS_URL = "ws://192.168.0.2:54817"
        private const val NOTIFICATION_CHANNEL_ID = "bridge_status"
        private const val NOTIFICATION_ID = 1001
        private const val ADVERTISE_SUCCESS = 0
        private const val MANUFACTURER_ID = 0xFFF0
        private val MANUFACTURER_PREFIX = byteArrayOf(
            0x6D.toByte(),
            0xB6.toByte(),
            0x43.toByte(),
            0xCE.toByte(),
            0x97.toByte(),
            0xFE.toByte(),
            0x42.toByte(),
            0x7C.toByte(),
        )
        private val CHANNELS = intArrayOf(
            0xE50000,
            0xF40000,
            0xF70000,
            0xF60000,
            0xF10000,
            0xF00000,
            0xF30000,
            0xE70000,
            0xFC0000,
            0xE60000,
        )

        // PR proposal: preset "fake patterns" - each entry is a list of
        // (Lovense level 0..20, hold duration in milliseconds) steps that are looped by
        // startPattern(). These are just suggested starting points and easy to retune
        // or extend; the names are also what the test UI passes through EXTRA_PATTERN.
        private val PATTERNS: Map<String, List<Pair<Int, Long>>> = mapOf(
            "pulse" to listOf(20 to 350L, 0 to 350L),
            "wave" to listOf(
                4 to 250L, 8 to 250L, 12 to 250L, 16 to 250L, 20 to 250L,
                16 to 250L, 12 to 250L, 8 to 250L, 4 to 250L,
            ),
            "escalate" to listOf(4 to 600L, 8 to 600L, 12 to 600L, 16 to 600L, 20 to 1200L),
        )
    }
}
