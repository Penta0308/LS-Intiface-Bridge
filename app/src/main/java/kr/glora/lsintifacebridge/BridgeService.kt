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

enum class ActiveChannel {
    NONE,
    VIBRATION,
    ROTATION
}

class BridgeService : Service() {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var advertisingSet: AdvertisingSet? = null
    private var isStartingAdvertising = false
    private var pendingStop = false
    private var wsUrl = DEFAULT_WS_URL
    private var currentLevel = 0
    private var vibrationLovenseLevel = 0
    private var rotationLovenseLevel = 0
    private var targetVibeIndex = 0
    private var targetRotateIndex = 0
    private var activeChannel = ActiveChannel.NONE
    private var vibeStopSent = true
    private var rotateStopSent = true
    private var webSocketStatus = "disconnected"
    private var bleStatus = "idle"
    private var lastNotificationText = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val schedulerRunnable = Runnable { tickScheduler() }

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
                if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                    setVibration(intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0))
                }
                if (intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                    setRotation(intent.getIntExtra(EXTRA_ROTATION_LEVEL, 0))
                }
                if (!intent.hasExtra(EXTRA_VIBRATION_LEVEL) && !intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                    val lvl = intent.getIntExtra(EXTRA_LEVEL, 0)
                    setVibration(lvl)
                }
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

        val oldWs = webSocket
        webSocket = null
        oldWs?.close(1000, "reconnect")

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
                    this@BridgeService.webSocket = null
                    stopAdvertising()
                    publish("WebSocket closed: $code $reason", webSocketStatus = "disconnected")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    this@BridgeService.webSocket = null
                    stopAdvertising()
                    publish("WebSocket failure: ${t.message}", webSocketStatus = "failed")
                }
            },
        )
    }

    private fun handleCommand(commandText: String, binaryResponse: Boolean) {
        val raw = commandText.trim()
        if (raw.isEmpty()) return

        publish("<- $raw")

        // Split multiple commands in one message separated by ';' or '\n'
        val tokens = raw.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val responses = mutableListOf<String>()

        for (token in tokens) {
            val cmd = "$token;"
            val resp = when {
                // Nora (Lovense C) exposes separate Vibrate and Rotate controls
                cmd.startsWith("DeviceType;") -> "C:8A3D9FAC2A45:10;"
                cmd.startsWith("Battery") -> "99;"
                cmd.startsWith("Status:1;") -> "2;"
                cmd.startsWith("AutoSwitch:") -> "OK;"
                cmd.startsWith("RotateChange") -> "OK;"
                cmd.startsWith("Vibrate:") -> {
                    val lvl = parseControlLevel(cmd, "Vibrate")
                    setVibration(lvl)
                    "OK;"
                }
                cmd.startsWith("Rotate:") -> {
                    val lvl = parseControlLevel(cmd, "Rotate")
                    setRotation(lvl)
                    "OK;"
                }
                cmd.startsWith("PowerOff") || cmd.startsWith("StopDevice") -> {
                    powerOff()
                    "OK;"
                }
                else -> "OK;"
            }
            if (!responses.contains(resp)) {
                responses.add(resp)
            }
        }

        val finalResponse = if (responses.isEmpty()) "OK;" else responses.joinToString("")
        sendResponse(finalResponse, binaryResponse)
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

    private fun parseControlLevel(command: String, control: String): Int {
        val value = Regex("""$control:(\d+)""").find(command)?.groupValues?.get(1)?.toIntOrNull()
        return value?.coerceIn(0, 20) ?: 0
    }

    private fun setVibration(lovenseLevel: Int) {
        val clamped = lovenseLevel.coerceIn(0, 20)
        vibrationLovenseLevel = clamped
        val newIndex = lovenseLevelToIndex(clamped)
        targetVibeIndex = newIndex
        if (newIndex > 0) {
            vibeStopSent = false
        }
        triggerScheduler()
    }

    private fun setRotation(lovenseLevel: Int) {
        val clamped = lovenseLevel.coerceIn(0, 20)
        rotationLovenseLevel = clamped
        val newIndex = lovenseLevelToIndex(clamped)
        targetRotateIndex = newIndex
        if (newIndex > 0) {
            rotateStopSent = false
        }
        triggerScheduler()
    }

    private fun powerOff() {
        vibrationLovenseLevel = 0
        rotationLovenseLevel = 0
        targetVibeIndex = 0
        targetRotateIndex = 0
        triggerScheduler()
    }

    private fun triggerScheduler() {
        if (advertisingSet == null || activeChannel == ActiveChannel.NONE) {
            mainHandler.removeCallbacks(schedulerRunnable)
            tickScheduler()
        } else {
            // Immediate update if active channel's index changed
            if (activeChannel == ActiveChannel.VIBRATION && targetVibeIndex > 0) {
                applyAdvertisingData(ActiveChannel.VIBRATION, VIBRATION_COMMANDS[targetVibeIndex], targetVibeIndex)
            } else if (activeChannel == ActiveChannel.ROTATION && targetRotateIndex > 0) {
                applyAdvertisingData(ActiveChannel.ROTATION, ROTATION_COMMANDS[targetRotateIndex], targetRotateIndex)
            }
        }
    }

    private fun tickScheduler() {
        mainHandler.removeCallbacks(schedulerRunnable)

        val vibeActive = targetVibeIndex > 0
        val rotateActive = targetRotateIndex > 0

        if (vibeActive && rotateActive) {
            // Both channels are active -> alternate between them with BURST_INTERVAL_MS quantum
            val nextChannel = if (activeChannel == ActiveChannel.VIBRATION) {
                ActiveChannel.ROTATION
            } else {
                ActiveChannel.VIBRATION
            }
            activeChannel = nextChannel
            val cmd = if (nextChannel == ActiveChannel.VIBRATION) {
                VIBRATION_COMMANDS[targetVibeIndex]
            } else {
                ROTATION_COMMANDS[targetRotateIndex]
            }
            val lvl = if (nextChannel == ActiveChannel.VIBRATION) targetVibeIndex else targetRotateIndex
            applyAdvertisingData(nextChannel, cmd, lvl)
            mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
        } else if (vibeActive && !rotateActive) {
            // Only vibration active
            if (!rotateStopSent) {
                // Deliver stop burst for rotation first
                activeChannel = ActiveChannel.ROTATION
                rotateStopSent = true
                applyAdvertisingData(ActiveChannel.ROTATION, ROTATION_COMMANDS[0], 0)
                mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
            } else {
                // Rotation stopped, continuously advertise vibration
                activeChannel = ActiveChannel.VIBRATION
                applyAdvertisingData(ActiveChannel.VIBRATION, VIBRATION_COMMANDS[targetVibeIndex], targetVibeIndex)
                mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
            }
        } else if (!vibeActive && rotateActive) {
            // Only rotation active
            if (!vibeStopSent) {
                // Deliver stop burst for vibration first
                activeChannel = ActiveChannel.VIBRATION
                vibeStopSent = true
                applyAdvertisingData(ActiveChannel.VIBRATION, VIBRATION_COMMANDS[0], 0)
                mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
            } else {
                // Vibration stopped, continuously advertise rotation
                activeChannel = ActiveChannel.ROTATION
                applyAdvertisingData(ActiveChannel.ROTATION, ROTATION_COMMANDS[targetRotateIndex], targetRotateIndex)
                mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
            }
        } else {
            // Both are stopped (level 0)
            if (!vibeStopSent) {
                activeChannel = ActiveChannel.VIBRATION
                vibeStopSent = true
                applyAdvertisingData(ActiveChannel.VIBRATION, VIBRATION_COMMANDS[0], 0)
                mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
            } else if (!rotateStopSent) {
                activeChannel = ActiveChannel.ROTATION
                rotateStopSent = true
                applyAdvertisingData(ActiveChannel.ROTATION, ROTATION_COMMANDS[0], 0)
                mainHandler.postDelayed(schedulerRunnable, BURST_INTERVAL_MS)
            } else {
                // Both stops delivered -> stop BLE advertising completely
                activeChannel = ActiveChannel.NONE
                stopAdvertising()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyAdvertisingData(
        channel: ActiveChannel,
        encodedCommand: Int,
        levelIndex: Int,
    ) {
        if (!hasBluetoothPermissions()) {
            publish("Missing Bluetooth permissions", bleStatus = "permission denied")
            return
        }

        val body = manufacturerBody(encodedCommand)
        val channelName = if (channel == ActiveChannel.VIBRATION) "Vib" else "Rot"
        val lovenseLvl = if (channel == ActiveChannel.VIBRATION) vibrationLovenseLevel else rotationLovenseLevel

        if (currentLevel != levelIndex || activeChannel != channel) {
            publish(
                "[BLE] $channelName ${lovenseLvl.toString().padStart(2, '0')} -> L$levelIndex: ${fullPayload(encodedCommand).toHex()} [V:$targetVibeIndex R:$targetRotateIndex]",
                bleStatus = "advertising $channelName L$levelIndex",
                level = levelIndex,
            )
        }
        currentLevel = levelIndex

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
            set.setAdvertisingData(data)
            return
        }

        if (isStartingAdvertising) {
            return
        }

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        isStartingAdvertising = true
        publish("[BLE] Starting advertising set", bleStatus = "starting")
        try {
            advertiser.startAdvertisingSet(parameters, data, null, null, null, advertisingCallback)
        } catch (e: Exception) {
            isStartingAdvertising = false
            publish("[BLE] startAdvertisingSet exception: ${e.message}", bleStatus = "start error")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        mainHandler.removeCallbacks(schedulerRunnable)
        val set = advertisingSet
        val advertiser = bluetoothAdvertiser()
        if (advertiser != null) {
            if (isStartingAdvertising) {
                pendingStop = true
            } else if (set != null) {
                try {
                    advertiser.stopAdvertisingSet(advertisingCallback)
                } catch (e: Exception) {
                    publish("[BLE] stopAdvertisingSet exception: ${e.message}")
                }
            }
        }
        advertisingSet = null
        isStartingAdvertising = false
        activeChannel = ActiveChannel.NONE
        publish("[BLE] Advertising stopped", bleStatus = "stopped", level = 0)
    }

    private fun shutdown() {
        mainHandler.removeCallbacks(schedulerRunnable)
        vibrationLovenseLevel = 0
        rotationLovenseLevel = 0
        targetVibeIndex = 0
        targetRotateIndex = 0
        vibeStopSent = true
        rotateStopSent = true
        activeChannel = ActiveChannel.NONE
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "service stopped")
        stopAdvertising()
        publish("Bridge stopped", webSocketStatus = "disconnected")
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
            isStartingAdvertising = false
            if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                this@BridgeService.advertisingSet = advertisingSet
                publish("[BLE] Advertising started: status=$status tx=${txPower}dBm", bleStatus = "started")
                if (pendingStop) {
                    pendingStop = false
                    stopAdvertising()
                } else {
                    tickScheduler()
                }
            } else {
                this@BridgeService.advertisingSet = null
                pendingStop = false
                publish("[BLE] Advertising start failed: status=$status", bleStatus = "start failed $status")
            }
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                publish("[BLE] Advertising data update failed: status=$status", bleStatus = "data update failed $status")
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            isStartingAdvertising = false
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
            .putExtra(EXTRA_VIBRATION_LEVEL, this.vibrationLovenseLevel)
            .putExtra(EXTRA_ROTATION_LEVEL, this.rotationLovenseLevel)
        sendBroadcast(intent)
        updateNotification()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val chName = when (activeChannel) {
            ActiveChannel.VIBRATION -> "Vib"
            ActiveChannel.ROTATION -> "Rot"
            ActiveChannel.NONE -> "Idle"
        }
        val notifText = "WS $webSocketStatus | BLE $bleStatus | V:$targetVibeIndex R:$targetRotateIndex ($chName)"
        if (notifText == lastNotificationText) return
        lastNotificationText = notifText
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(notifText),
        )
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
        return (((clamped / 20.0) * 8.0 + 0.5).toInt() + 1).coerceIn(1, 9)
    }

    private fun manufacturerBody(encodedCommand: Int): ByteArray {
        return MANUFACTURER_PREFIX + byteArrayOf(
            ((encodedCommand shr 16) and 0xFF).toByte(),
            ((encodedCommand shr 8) and 0xFF).toByte(),
            (encodedCommand and 0xFF).toByte(),
        )
    }

    private fun fullPayload(encodedCommand: Int): ByteArray =
        byteArrayOf(
            (MANUFACTURER_ID and 0xFF).toByte(),
            ((MANUFACTURER_ID shr 8) and 0xFF).toByte(),
        ) + manufacturerBody(encodedCommand)

    private fun ByteArray.toHex(): String =
        joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    companion object {
        const val ACTION_START = "kr.glora.lsintifacebridge.action.START"
        const val ACTION_STOP = "kr.glora.lsintifacebridge.action.STOP"
        const val ACTION_TEST_LEVEL = "kr.glora.lsintifacebridge.action.TEST_LEVEL"
        const val ACTION_STATUS = "kr.glora.lsintifacebridge.action.STATUS"

        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_LOG = "log"
        const val EXTRA_WS_STATUS = "ws_status"
        const val EXTRA_BLE_STATUS = "ble_status"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_VIBRATION_LEVEL = "vibration_level"
        const val EXTRA_ROTATION_LEVEL = "rotation_level"

        private const val DEFAULT_WS_URL = "ws://192.168.0.2:54817"
        private const val NOTIFICATION_CHANNEL_ID = "bridge_status"
        private const val NOTIFICATION_ID = 1001
        private const val ADVERTISE_SUCCESS = 0
        private const val MANUFACTURER_ID = 0xFFF0
        private const val BURST_INTERVAL_MS = 250L

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
        // The two independently latched output channels are 0x30..0x39 and
        // 0x40..0x49. Array indexes 0..9 correspond to off and levels 1..9.
        private val VIBRATION_COMMANDS = intArrayOf(
            0xD5964C, 0xD41F5D, 0xD7846F, 0xD60D7E, 0xD1B20A,
            0xD03B1B, 0xD3A029, 0xD22938, 0xDDDEC0, 0xDC57D1,
        )
        private val ROTATION_COMMANDS = intArrayOf(
            0xA5113F, 0xA4982E, 0xA7031C, 0xA68A0D, 0xA13579,
            0xA0BC68, 0xA3275A, 0xA2AE4B, 0xAD59B3, 0xACD0A2,
        )
    }
}

