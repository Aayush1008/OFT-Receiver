# Changelog

All notable changes to OFT Receiver are documented here.

## [2.0] - 2026-09-26

### Added
- Material 3 / Material You with dynamic wallpaper-based colors
- Edge-to-edge display with proper status bar and navigation bar insets
- Transfer history tab with Room-backed persistent storage
- Bottom navigation (Scan / History)
- Custom stylized QR code adaptive icon with monochrome layer
- Torch toggle for low-light scanning
- Delete individual history entries

### Changed
- Complete UI redesign — dark theme, rounded cards, modern typography
- Rebuilt as fragment-based architecture (ScanFragment + HistoryFragment)
- Upgraded to compileSdk/targetSdk 35 (Android 15)
- ViewModel rewritten as AndroidViewModel for database access

## [1.0] - 2026-09-26

### Added
- Initial release
- OFT1 protocol parser (manifest + data frames)
- CameraX preview with ML Kit barcode scanning (bundled model)
- CRC-32 per-chunk verification
- SHA-256 whole-file verification
- Save files via Storage Access Framework
- GitHub Actions CI with automatic APK releases
