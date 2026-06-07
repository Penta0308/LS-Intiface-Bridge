# LS Intiface Bridge

Android bridge app for controlling Love Spouse / MuSe style devices from Intiface Central.

The app connects to Intiface Central's Device Websocket Server as a Lovense-compatible websocket device, receives vibration commands, and emits Love Spouse BLE legacy advertising packets from the Android device.

## Requirements

- Android 12 or newer.
- Android device with BLE advertising support.
- Intiface Central running on Windows.
- Windows PC and Android device on the same network.
- Windows firewall allowing Intiface Central's Device Websocket Server port.

## Intiface Central Setup

Keep Intiface Central's normal Buttplug server and Device Websocket Server on separate ports.

Recommended layout:

```text
Intiface BP server:          ws://0.0.0.0:12345
Device Websocket Server:  ws://0.0.0.0:54817
```

In Intiface Central, add a websocket device:

```text
Protocol: lovense
Name:     LVSDevice
```

If the UI does not expose the name field, configure the user device config with a Lovense websocket entry named `LVSDevice`.

```json
{
  "version": {
    "major": 5,
    "minor": 5
  },
  "user_configs": {
    "protocols": {
      "lovense": {
        "communication": [
          {
            "websocket": {
              "name": "LVSDevice"
            }
          }
        ],
        "configurations": []
      }
    },
    "devices": []
  }
}
```

## Android App Usage

1. Build and install the debug APK.
2. Grant Bluetooth/Nearby Devices permission.
3. On Android 13 or newer, grant notification permission for the foreground service.
4. Enter the Device Websocket Server URL, for example:

```text
ws://192.168.0.2:54817
```

5. Press `Start`.
6. Start scanning in the Intiface client.

The app stores the last websocket URL. While running, it uses a foreground notification with a `Stop` action.

The test slider sends local BLE advertising levels without waiting for Intiface commands.

## BLE Payload

The bridge uses Android's Bluetooth LE advertiser with legacy, connectable, scannable advertising.

```text
Manufacturer ID: 0xFFF0
Prefix:          6D B6 43 CE 97 FE 42 7C
```

Lovense levels `0..20` are mapped to the original Love Spouse/MuSe command table from the ESP32 firmware.

## Development

Main source files:

```text
app/src/main/java/kr/glora/lsintifacebridge/MainActivity.kt
app/src/main/java/kr/glora/lsintifacebridge/BridgeService.kt
```

Build:

```bash
./gradlew :app:assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```
