# Shining

A small Android app that controls a Tuya-based LED strip (sold as the Lenovo Smart LED Lightstrip and under many other brands) **directly over your Wi-Fi**: no cloud, no account, no ads, no telemetry.

It replaces the Smart Life app for day-to-day control:

- Power on/off: big button, plus a **Quick Settings tile**
- **Device controls** (Android 11+): on/off and brightness next to your other smart-home tiles, also from the lock screen
- Colour (hue + saturation), white (warmth), and brightness sliders
- One-tap presets
- Finds the light on your network automatically

The release APK is under 2 MB and needs no Google services. Design follows the [ARVA](https://ar-va.com) brand: Archivo, IBM Plex Sans and Plex Mono, the ARVA palette in light and dark.

## How it works

Smart Life is Tuya's white-label app. Tuya devices also listen on the LAN (TCP 6668) for AES-encrypted JSON commands. This app speaks that protocol (versions 3.3, 3.4 and 3.5) natively in Kotlin, with no third-party crypto. The protocol is the same one documented by the open-source [tinytuya](https://github.com/jasonacox/tinytuya) project. The tests in this repo run against a fake device built on tinytuya's own code.

The only thing the app can't discover by itself is the device's **local key**, a 16-character secret that Tuya's cloud assigns when the light is paired. You fetch it once (see below).

## Setup (one time, about 15 minutes)

### 1. Get the local key

1. Create a free account at <https://platform.tuya.com>.
2. **Cloud → Development → Create Cloud Project**: choose Industry *Smart Home*, Development Method *Smart Home*, and the **data center that matches your Smart Life account region** (for example *Central Europe* or *Western America*).
3. In the project, open **Devices → Link App Account → Add App Account** and scan the QR code with the Smart Life app (*Me* tab → scan icon, top right).
4. On the project **Overview** tab, copy the **Access ID** and **Access Secret**.
5. On a computer on the same Wi-Fi:

   ```bash
   pip install tinytuya qrcode
   python3 -m tinytuya wizard      # paste Access ID/Secret, pick your region, answer Y to scan
   ```

   This writes `devices.json`, which contains your keys. Keep it private; it's already in `.gitignore`.

### 2. Send the settings to your phone

```bash
python3 tools/setup_qr.py
```

Scan the QR code it prints with your phone camera and open it with Shining. Done.

Alternatively, open **Settings** in the app, tap **Scan Wi-Fi** to fill in the IP, device ID and version, then type the local key.

### 3. Install the app

- Download `app-release.apk` from this repo's [Releases](../../releases) or the latest [Actions build](../../actions), or
- Build it yourself: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

APKs built on different machines are signed with different debug keys. If you switch between a CI build and a local build, uninstall the old one first.

### Quick Settings tile

Pull down the notification shade → ✏️ edit → drag **Shining** into your tiles.

### Device controls

Pull down the shade → **Device controls** (on Pixel it may be the **Home** tile) → ⋮ → **Add controls** → **Shining** → tick **Lightstrip** → **Save**. Tap the tile to switch the light on or off; drag across it to set brightness. To use it from the lock screen, turn on **Settings → Display → Lock screen → Control from locked device**.

## Things to know

- **Google Home keeps working.** It talks to the Tuya cloud through your Smart Life account, not through the app. You can uninstall the Smart Life app, but **don't delete the account** and **don't remove or re-pair the light**: re-pairing gives it a new local key, and you'd repeat step 1.
- **Give the light a fixed IP** (a DHCP reservation in your router). If the IP changes, use **Settings → Scan Wi-Fi** to update it.
- Tuya lights often allow **only one local connection** at a time. The app closes its connection after 20 s idle so it doesn't block anything else.
- Phone and light must be on the same network. Guest Wi-Fi or "AP isolation" will block it.

## Troubleshooting

Use **Settings → Test connection**. It shows the raw data points (DPs) the light reports.

| Message | Fix |
|---|---|
| *did not answer* / *Cannot reach* | Wrong IP, light offline, or on a different network |
| *closed the connection* / *wrong local key* | Key is wrong or outdated (was the light re-paired?), or the protocol version is wrong: try 3.3 / 3.4 / 3.5 |
| *doesn't look like a light* | The device uses non-standard DPs. Open an issue with the DP list from Test connection |

## Development

```
tuya/   Pure Kotlin/JVM Tuya LAN protocol library (framing, AES-ECB/GCM, session keys, discovery, light DPs)
app/    Android app (Jetpack Compose, ARVA design tokens, minSdk 26)
tools/  mock_device.py (fake light for testing), setup_qr.py (QR from tinytuya output)
```

```bash
./gradlew :tuya:test                       # unit tests
# Protocol tests against a fake light built on tinytuya:
pip install tinytuya
python3 tools/mock_device.py --version 3.4 --port 16668 &
TUYA_MOCK_PORT=16668 TUYA_MOCK_VERSION=3.4 ./gradlew :tuya:test --rerun
```

## License

Code: MIT. Fonts (Archivo, IBM Plex): SIL Open Font License, see `licenses/`. Icons: [Lucide](https://lucide.dev) (ISC). The ARVA name and leaf mark belong to ARVA and are not covered by the MIT licence.
