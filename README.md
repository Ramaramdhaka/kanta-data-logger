# Kanta Data Logger — Android Bluetooth receiver

Standalone diagnostic APK to discover how a real weighing scale sends data.

## Features
- Bluetooth Classic SPP receiver (paired scale; client mode or optional listener mode)
- Bluetooth Low Energy scan: log advertisement bytes + RSSI
- BLE GATT: enumerate service/characteristic UUIDs, enable standard notifications/indications, manually read characteristics
- Optional ASCII command for scales that require a request. Text escapes `\\r`, `\\n`, `\\t` are supported; nothing is sent without pressing Send.
- Every receive chunk shows millisecond timestamp, inter-receive time, ASCII escaped and exact HEX bytes
- TXT export to Files/Downloads via Android document picker. No storage permission needed.
- Different app ID from Bluetooth Kanta Sender so both can be installed side-by-side

## Important limitations
- This is not an over-the-air packet sniffer and cannot capture another app's private Bluetooth connection.
- Some scales use proprietary BLE protocols, different RFCOMM UUIDs, HID, Wi-Fi, or RS232. Those may not supply weight using generic SPP or GATT notifications. A service connection does not guarantee weight will be received.
- One callback can contain a partial frame or several frames. Delta is measured between Android callbacks, not the radio packet rate.
- Data log is limited to ~600,000 characters in memory; export frequently if logging at high rates.
- Keep the app open while collecting; Android may pause a background app.

## Build APK using GitHub Actions (no Android Studio)
1. Create a **new** GitHub repo `kanta-data-logger` (keep previous `kanta-apk` sender repo safe).
2. Upload the **contents** of this extracted project at repo root; preserve paths. If using GitHub's New file editor you can enter the full `path/filename` to create folders.
3. GitHub Actions `Build Kanta Data Logger APK` workflow runs on every push to main.
4. Open successful Actions run -> Artifacts -> `Kanta-Data-Logger-APK` -> download ZIP -> extract `app-debug.apk`.
5. Install on Android phone and grant **Nearby devices** permission. Older Android phones may request Location for BLE scan.

## What to send for imitation
Connect scale and set it to 0 kg, 5 kg, 20 kg (if safe for scale). Capture data at each weight, export the TXT, then send the TXT and the scale model/label/photo. Include any setting for automatic streaming vs on-demand transmission.
