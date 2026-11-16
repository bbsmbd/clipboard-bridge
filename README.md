# Clipboard Bridge
[![Latest Release](https://img.shields.io/github/v/release/brahmanbariait1-coder/clipboard-bridge)](https://github.com/brahmanbariait1-coder/clipboard-bridge/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Copy on your PC → paste on your Android phone. Copy on the phone → paste on the PC.**
Over your own WiFi, end-to-end encrypted, no cloud, no account.

[বাংলা নির্দেশিকা (Bengali guide)](README-bn.md)

---

## Download

From the [Releases](https://github.com/brahmanbariait1-coder/clipboard-bridge/releases) page:

| File | What it is |
|---|---|
| `ClipboardBridge-V1-Setup.exe` | **Windows installer** (recommended): installs, adds Start menu/Desktop shortcuts, starts with Windows, uninstalls cleanly |
| `ClipboardBridge-V1.exe` | Portable Windows app (no install). Keep it in a fixed folder, since autostart remembers its location |
| `ClipboardBridge-V1.apk` | Android app v1.1.0 (Android 6 – 17) |

Requirements: Windows 10/11 64-bit · Android 6.0 or newer · PC and phone on the same WiFi/router.

## Features

- **PC → phone:** Ctrl+C on the PC is on the phone's clipboard instantly.
- **Phone → PC:** copy on the phone, then Ctrl+V on the PC.
  - Android 6–9: automatic.
  - Android 10+: automatic with the app's Accessibility service turned on.
  - One-tap ways that always work:
    - "Send to PC" in the text-selection menu
    - Share → Clipboard Bridge
    - The notification button
    - The Quick Settings tile
- **Images too (both ways):**
  - Copy an image or take a screenshot on the PC (Win+Shift+S) → it is on the phone's clipboard, ready to paste in WhatsApp, Telegram, Gmail and other apps that accept pasted images. On Android 10+ it is also saved to Gallery → *Pictures/Clipboard Bridge*.
  - Copy an image on the phone, or use Share → Clipboard Bridge → it is on the PC clipboard, so Ctrl+V works in Word, Paint, WhatsApp Web and other apps. Up to 20 MB per image.
- **Finds the PC by itself:** a UDP beacon plus a subnet scan, so it still works when the router blocks broadcasts. It reconnects by itself when the PC's IP changes.
- **Secure:**
  - Pairing uses a 6-digit PIN with PBKDF2-SHA256 and a lockout after 5 wrong tries.
  - All traffic is encrypted with AES-256-GCM.
  - The key on the phone is protected by the Android Keystore.
- **Low maintenance:**
  - The PC app runs in the system tray and starts with Windows.
  - The phone keeps a foreground connection and reconnects after WiFi drops and reboots.
- **Languages:** English (default) and Bengali.

## Setup

1. **PC:** run `ClipboardBridge-V1-Setup.exe`.
   - On first start Windows asks for permission to add firewall rules. Click **Yes**.
   - The window shows a **6-digit PIN**.
2. **Phone:** install `ClipboardBridge-V1.apk` and open it. Pick your PC from the list (or type the IP shown on the PC), then enter the PIN.
3. **Android 10+:**
   - In the app tap **Turn on Accessibility (auto copy)** and switch Clipboard Bridge on.
   - If it is greyed out ("Controlled by Restricted Setting"): Settings → Apps → Clipboard Bridge → ⋮ → **Allow restricted settings**, then try again.
4. Tap **Allow running in background**.
   - On OnePlus/Oppo/Realme/Xiaomi, also allow *background activity / auto launch* for the app.

### If the APK hangs at "Installing…"
Some file-manager installers hang. Enable USB debugging, connect the phone, and run `tools/INSTALL-ON-PHONE.bat` (uses `adb` from the Android SDK platform-tools).

## Screenshots

![Pair with PC](docs/screenshots/pair-with-pc.png)
*Pair with PC — enter the PC's IP and the 6-digit PIN shown on the PC.*

![Connected on phone](docs/screenshots/phone-connected.png)
*Connected — copy on the PC, paste on the phone, and back.*

![Connected on PC](docs/screenshots/pc-connected.png)
*The PC window — connected phone, pairing PIN and PC IP.*

> The IP addresses, PIN and device names in the screenshots are examples.

## How it works

```
 PC app (Rust, Win32)                         Android app (Java)
 ─────────────────────                        ───────────────────
 clipboard listener ─┐                      ┌─ foreground service (TCP client)
 tray + window       │  TCP 54546 (AES-GCM) │  accessibility: detects "copy"
 TCP server ─────────┼──────────────────────┼─ capture window reads clipboard
 UDP beacon 54545 ───┘  UDP 54545 discovery └─ discovery: UDP + /24 sweep
```

- Frames: `[u32 length][kind 'P'|'E'][payload]`.
  - 'P': plain JSON, used for the handshake only.
  - 'E': AES-256-GCM (`iv12 ‖ ciphertext ‖ tag16`).
- Pairing:
  - The PC sends a salt and a nonce.
  - The phone proves it knows the PIN by encrypting the nonce with `PBKDF2(PIN, salt, 50 000)`.
  - The PC replies with a fresh random 32-byte session key.
- Clipboard contents are never written to disk on either side.

## Build from source

**Windows app** (`pc-app/`, Rust):
```
cargo build --release                                   # on Windows
cargo build --release --target x86_64-pc-windows-gnu    # cross-compile (mingw-w64)
```
Installer: copy the built EXE into `installer/` and run `makensis installer/ClipboardBridge.nsi`.

**Android app** (`android-app/`, Java, no Gradle needed):
```
KS=path/to/your.jks KS_PASS=yourpassword ./android-app/build_apk.sh
```
Needs `aapt`, `javac`, `dalvik-exchange` (dx), `zipalign`, `apksigner`, and `android.jar` for API 34 and API 23. The script also checks that no API above Android 6 is used outside `Api.java`.

**Tests:** `android-app/test/E2E.java` runs the real PC core (headless Linux build) against the Java phone core, covering pairing, wrong PIN/lockout, Bengali/emoji and 400k-character texts, images (6 MB) both ways, and reconnects.

## Privacy

Clipboard Bridge only talks to your own PC on your local network. It has no servers, no analytics and no accounts. The Accessibility service is used only to notice when you tap Copy; it does not read or send screen content.

## Developer

**Developed by Tarek Ahmed**

## License

[MIT](LICENSE) © 2026 Tarek Ahmed
