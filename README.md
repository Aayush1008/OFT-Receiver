<p align="center">
  <img src="docs/icon-preview.svg" width="96" alt="OFT Receiver icon" />
</p>

<h1 align="center">OFT Receiver</h1>

<p align="center">
  <strong>Optical File Transfer for Android</strong><br>
  Receive files through a repeating QR code sequence — no network needed.
</p>

<p align="center">
  <a href="https://github.com/Aayush1008/OFT-Receiver/actions/workflows/build.yml">
    <img src="https://github.com/Aayush1008/OFT-Receiver/actions/workflows/build.yml/badge.svg" alt="Build APK" />
  </a>
  <a href="https://github.com/Aayush1008/OFT-Receiver/releases/latest">
    <img src="https://img.shields.io/github/v/release/Aayush1008/OFT-Receiver?label=latest%20release" alt="Latest Release" />
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3ddc84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Material%20You-Dynamic%20Colors-6750A4?logo=materialdesign&logoColor=white" alt="Material You" />
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/Aayush1008/OFT-Receiver" alt="License" />
  </a>
</p>

---

## What is OFT?

**Optical File Transfer (OFT1)** is a protocol that encodes a file as a rapid sequence of QR codes displayed on one screen and scanned by another device's camera. It's a true air-gapped transfer — no Wi-Fi, Bluetooth, USB, or internet connection involved.

This app is the **receiver** side. Point your phone camera at the sender's QR sequence, and the file is reassembled on your device with full integrity verification.

## Features

- **Fully offline** — bundled ML Kit model, no Google Play Services required
- **Material You** — dynamic colors pulled from your wallpaper (Android 12+)
- **Edge-to-edge UI** — immersive dark theme optimized for Pixel 8 / Android 15
- **Live transfer progress** — real-time chunk counter, progress bar, and file metadata
- **Integrity verification** — CRC-32 per-chunk + SHA-256 whole-file verification
- **Transfer history** — browse past transfers with Room-backed persistent storage
- **Torch control** — toggle flashlight for low-light scanning
- **Adaptive icon** — custom QR code icon with monochrome layer for themed icons

## Screenshots

<!-- Add screenshots here -->
<p align="center">
  <em>Screenshots coming soon</em>
</p>

## Download

Grab the latest APK from [**Releases**](https://github.com/Aayush1008/OFT-Receiver/releases/latest).

> **Install:** Download `app-debug.apk` → open it on your phone → allow installation from unknown sources if prompted → done.

## Architecture

```
com.oftreceiver/
├── OftApp.kt                   # Application — Material Dynamic Colors init
├── protocol/
│   ├── OftProtocol.kt          # Frame parser, Base64url, CRC-32 validation
│   └── TransferSession.kt      # Chunk storage, progress tracking, SHA-256
├── data/
│   ├── TransferRecord.kt       # Room entity for transfer history
│   ├── TransferDao.kt          # Data access object
│   └── AppDatabase.kt          # Room database singleton
├── viewmodel/
│   └── MainViewModel.kt        # AndroidViewModel — state + DB access
└── ui/
    ├── MainActivity.kt          # Fragment host with BottomNavigationView
    ├── ScanFragment.kt          # CameraX preview + QR scanning UI
    ├── HistoryFragment.kt       # Transfer history list
    ├── HistoryAdapter.kt        # RecyclerView adapter
    └── QrAnalyzer.kt            # ML Kit barcode analyzer for CameraX
```

## OFT1 Protocol

The sender displays QR codes in a repeating loop. Each frame is pipe-delimited:

| Frame | Fields |
|-------|--------|
| **Manifest** | `OFT1\|M\|session_id\|total_chunks\|file_size\|sha256\|filename_b64url` |
| **Data** | `OFT1\|D\|session_id\|chunk_index\|crc32_hex\|payload_b64url` |

**Key details:**
- Manifest is retransmitted every 8 data frames
- Payloads use Base64url encoding **without padding**
- Scanning can begin at any point in the sequence
- Each chunk is verified with CRC-32; the complete file is verified with SHA-256

## Tech Stack

| Component | Library | Version |
|-----------|---------|---------|
| UI framework | Material 3 (Material Design Components) | 1.12.0 |
| Camera | CameraX | 1.3.3 |
| QR scanning | ML Kit Barcode Scanning (bundled) | 17.2.0 |
| Database | Room | 2.6.1 |
| Annotation processing | KSP | — |
| Min SDK | Android 8.0 | API 26 |
| Target SDK | Android 15 | API 35 |

## Building from Source

### Prerequisites

- **JDK 17+**
- **Android SDK** with API 35 build tools
- Android Studio Ladybug (2024.2) or later *(optional — CLI build works fine)*

### Build

```bash
git clone https://github.com/Aayush1008/OFT-Receiver.git
cd OFT-Receiver
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Test

```bash
./gradlew test
```

## CI/CD

Every push to `main` triggers a [GitHub Actions workflow](.github/workflows/build.yml) that:

1. Runs unit tests
2. Builds a debug APK
3. Creates a GitHub Release with the APK attached

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

## Acknowledgments

- [Google ML Kit](https://developers.google.com/ml-kit) — on-device barcode scanning
- [CameraX](https://developer.android.com/training/camerax) — camera abstraction layer
- [Material Design 3](https://m3.material.io/) — design system and dynamic color
