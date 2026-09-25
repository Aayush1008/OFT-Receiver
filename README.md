# OFT Receiver — Offline Optical File Transfer

Native Android app that receives files transferred optically via a repeating
QR code sequence displayed by a laptop (the "sender"). No network connection
required — everything runs on-device.

## Build & Run

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK with API 34 build tools

### Steps
1. Open the `OFTReceiver/` folder as an Android Studio project.
2. Wait for Gradle sync to finish (downloads CameraX + ML Kit bundled model).
3. Connect a device or start an emulator (API 26+).
4. Run **app** configuration.
5. Grant camera permission when prompted.

### Build from command line
```bash
cd OFTReceiver
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

### Run unit tests
```bash
./gradlew test
```

## ML Kit Dependency — Why Bundled

The app uses `com.google.mlkit:barcode-scanning` (the **bundled** variant),
not `com.google.android.gms:play-services-mlkit-barcode-scanning` (the
thin/Google Play Services variant). This means:

- The barcode model ships inside the APK (~2.5 MB larger).
- Scanning works immediately after install with **no network access**.
- Works on devices without Google Play Services (e.g. AOSP, Huawei).

The `<meta-data>` tag in `AndroidManifest.xml` under
`com.google.mlkit.vision.DEPENDENCIES` is for pre-warming but is
not required for the bundled variant — it's included for safety.

## Architecture

```
com.oftreceiver/
├── protocol/
│   ├── OftProtocol.kt     — Frame parser, Base64url, CRC-32, validation
│   └── TransferSession.kt — Chunk storage, progress, SHA-256 verification
├── viewmodel/
│   └── MainViewModel.kt   — Rotation-safe state, processes QR payloads
└── ui/
    ├── MainActivity.kt     — CameraX setup, permission handling, SAF save
    └── QrAnalyzer.kt       — ML Kit barcode analyzer for CameraX
```

## Protocol Summary

| Frame     | Format |
|-----------|--------|
| Manifest  | `OFT1\|M\|session_id\|total_chunks\|file_size\|sha256\|filename_b64url` |
| Data      | `OFT1\|D\|session_id\|chunk_index\|crc32_hex\|payload_b64url` |

The sender repeats the manifest every 8 data frames. Scanning can begin
at any point in the sequence.
