package kr.glora.lsintifacebridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kr.glora.lsintifacebridge.ui.theme.LSIntifaceBridgeTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var wsUrl by mutableStateOf(DEFAULT_WS_URL)
    private var webSocketStatus by mutableStateOf("disconnected")
    private var bleStatus by mutableStateOf("idle")
    private var currentLevel by mutableIntStateOf(0)
    private var testLovenseLevel by mutableIntStateOf(10)
    private var logText by mutableStateOf("")

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                appendLog("Permissions granted")
            } else {
                appendLog("Permissions denied")
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BridgeService.ACTION_STATUS) return
            webSocketStatus = intent.getStringExtra(BridgeService.EXTRA_WS_STATUS) ?: webSocketStatus
            bleStatus = intent.getStringExtra(BridgeService.EXTRA_BLE_STATUS) ?: bleStatus
            currentLevel = intent.getIntExtra(BridgeService.EXTRA_LEVEL, currentLevel)
            intent.getStringExtra(BridgeService.EXTRA_LOG)?.let(::appendLog)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wsUrl = preferences().getString(PREF_WS_URL, DEFAULT_WS_URL) ?: DEFAULT_WS_URL
        requestMissingPermissions()

        setContent {
            LSIntifaceBridgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("LS Intiface Bridge", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = wsUrl,
                            onValueChange = {
                                wsUrl = it
                                preferences().edit().putString(PREF_WS_URL, it).apply()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Intiface Device WebSocket") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startBridge() }) {
                                Text("Start")
                            }
                            Button(onClick = { stopBridge() }) {
                                Text("Stop")
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Test Lovense Level: $testLovenseLevel")
                            Slider(
                                value = testLovenseLevel.toFloat(),
                                onValueChange = { testLovenseLevel = it.roundToInt() },
                                valueRange = 0f..20f,
                                steps = 19,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { testLevel(testLovenseLevel) }) {
                                    Text("Send")
                                }
                                Button(onClick = { testLevel(0) }) {
                                    Text("Off")
                                }
                            }
                        }
                        Text("WebSocket: $webSocketStatus")
                        Text("BLE: $bleStatus")
                        Text("Level: $currentLevel")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = logText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BridgeService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun startBridge() {
        if (!hasPermissions()) {
            requestMissingPermissions()
            return
        }
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_START)
            .putExtra(BridgeService.EXTRA_WS_URL, wsUrl)
        preferences().edit().putString(PREF_WS_URL, wsUrl).apply()
        ContextCompat.startForegroundService(this, intent)
        appendLog("Bridge service starting")
    }

    private fun stopBridge() {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_STOP)
        startService(intent)
        appendLog("Bridge service stopping")
    }

    private fun testLevel(level: Int) {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_TEST_LEVEL)
            .putExtra(BridgeService.EXTRA_LEVEL, level)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestMissingPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermissions(): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun appendLog(message: String) {
        logText = (logText + message + "\n").lines().takeLast(120).joinToString("\n")
    }

    private fun preferences() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        private const val DEFAULT_WS_URL = "ws://192.168.0.2:54817"
        private const val PREFS_NAME = "bridge_settings"
        private const val PREF_WS_URL = "ws_url"
    }
}
