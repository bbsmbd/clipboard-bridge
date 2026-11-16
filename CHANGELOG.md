# Changelog

All notable changes to Clipboard Bridge will be documented in this file.

## [1.1.0] - 2026-11-17

### Added

- First public release.
- PC app (Rust, Windows 10/11): system-tray app, clipboard listener, TCP server with AES-256-GCM encryption, UDP discovery, 6-digit PIN pairing with PBKDF2-SHA256 and lockout after 5 wrong tries.
- Android app (Java, Android 6–17): foreground service, automatic copy detection (Accessibility service on Android 10+), "Send to PC", Share, notification button and Quick Settings tile.
- Image clipboard sharing both ways (up to 20 MB per image).
- Windows installer (NSIS) and portable EXE.
- Bengali guide (`README-bn.md`) and English README.
