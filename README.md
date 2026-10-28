# LD2450 Portable Radar — ESP32-S3 + Native Android

A GitHub-ready starter project for an ESP32-S3 BLE UART bridge and a native Android radar dashboard.

## What this project does

- Keeps the ESP32-S3 as a simple UART-to-BLE transport bridge (no Wi-Fi).
- Uses GPIO 44 as UART RX and GPIO 43 as UART TX at 256000 baud, 8-N-1.
- Android connects to the Nordic UART Service (NUS) and reconstructs the LD2450 byte stream.
- Parses standard 30-byte LD2450 target-report frames and displays up to three reported targets.
- Uses a two-consecutive-report stability filter plus configurable minimum/maximum range filters.
- Uses a dark, modern radar-style interface.

## Important limits

The LD2450 reports target tracks; it does not provide a general-purpose "human identity" or a confidence score in its normal target frame. The app's two-report stability and distance filters can suppress brief glitches and unwanted distant tracks, but cannot guarantee that every reported target is a human or remove all environmental false detections. Tune the radar module's configuration and detection zones for the room. Fans, moving curtains, pets, and reflections may still cause false targets.

The standard target frame is parsed as 30 bytes:
`AA FF 03 00` + three 8-byte target records + `55 CC`.
The parser checks the header and footer and resynchronizes after malformed bytes. BLE notifications are arbitrary stream chunks; one notification is not assumed to equal one radar frame.

## Build the APK on GitHub

1. Create a new GitHub repository.
2. Unzip this archive and upload the **contents** of this folder to the repository root.
3. Push/commit the files.
4. Open **Actions** in the repository. The workflow `Build Android APK` runs automatically.
5. Download `LD2450-debug-apk` from the completed workflow's Artifacts section.
6. On your Android phone, download the APK and install it. Android may ask you to allow installation from the browser/file manager you used.

The workflow installs Gradle 8.9 and Android SDK components; a Gradle wrapper is not required.

## Android connection

1. Power the radar and ESP32-S3.
2. Open the app and grant Nearby devices/Bluetooth permissions.
3. Tap **Scan & connect** and select `LD2450_BLE_Bridge`.
4. The app subscribes to TX notifications and starts parsing radar frames.

Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`  
Phone writes to ESP32 RX: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`  
ESP32 notifies phone on TX: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

## Firmware notes

Flash `firmware/LD2450_BLE_Bridge.ino` using Arduino IDE with an ESP32 Arduino core that includes the legacy `BLEDevice.h` API. The sketch deliberately uses 20-byte notifications for compatibility with the default ATT MTU. It forwards UART bytes unchanged and does not try to modify radar reports.

The BLE queue is bounded. If its overflow counter increases, the BLE client is not draining data quickly enough or the connection is congested. The app should be connected before relying on live data; data is intentionally discarded while disconnected.

## Radar placement and tuning

- Aim the radar away from ceiling fans and moving curtains where possible.
- Keep the module stable and avoid pointing through dense materials.
- Use the app's distance filter to hide unwanted distant tracks, not as a substitute for proper radar configuration.
- Test with the room empty, then with a stationary person, then with walking movement.
