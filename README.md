# Shining

A small Android app that controls an LED strip running [OpenBeken](https://github.com/openshwprojects/OpenBK7231T_App) firmware **directly over your Wi-Fi**: no cloud, no account, no ads, no telemetry. The only internet request is a check for new versions on GitHub.

- Power on/off: tap the light on the main screen, plus a **Quick Settings tile**
- **Device controls** (Android 11+): on/off and brightness next to your other smart-home tiles, also from the lock screen
- **Automations**: schedules, sunset/sunrise, and arriving home, all run on the phone
- **Hooks** for launcher shortcuts, NFC tags, Tasker and MacroDroid
- Opens instantly to the last state and syncs live while open
- **Haptics**, theme + **Material You**, and it respects your "reduce animations" setting
- Colour, white (warmth), brightness and one-tap presets

The release APK is about 2 MB and needs no Google services. Design follows the [ARVA](https://ar-va.com) brand: Brygada 1918, IBM Plex Sans and Plex Mono, the ARVA palette in light and dark.

## Setup

### 1. Flash the strip

Shining was built for the Lenovo Smart LED Lightstrip, a Tuya device with a WB3S (BK7231T) controller.

1. Flash **OpenBeken** with [tuya-cloudcutter](https://github.com/tuya-cloudcutter/tuya-cloudcutter). There is no soldering on this hardware, but it needs a Linux machine and a compatible Wi-Fi adapter (for example AR9271). Keep the firmware backup it offers.
2. In OpenBeken's web page, set the strip's pin map (RGBCW: R=P9, G=P24, B=P26, cold=P6, warm=P8, PWM 1000 Hz, button P14) and join your Wi-Fi.

Shining no longer supports stock Tuya firmware. The last version that did is [v1.18.2](../../releases/tag/v1.18.2).

### 2. Install the app

- Download `Shining-<version>.apk` from this repo's [Releases](../../releases) or the latest [Actions build](../../actions), or
- Build it yourself: `./gradlew assembleRelease` → `app/build/named-apk/Shining-<version>.apk`

Releases are signed with a permanent key, so new versions install over old ones. (Builds without the key, such as forks, fall back to a debug key and need an uninstall first.)

To update, open **Settings → Updates → Get the update**. It opens the release page: tap the APK under Assets, then choose **Download anyway** when Chrome warns about the file. Chrome holds the download at 100% until you answer that warning.

### 3. Connect

Open **Settings**, enter the strip's IP address and, if you set one, its web password. Press **Test**, then **Save**.

### Quick Settings tile

Pull down the notification shade → edit → drag **Shining** into your tiles.

### Device controls

Pull down the shade → **Device controls** (on Pixel it may be the **Home** tile) → ⋮ → **Add controls** → **Shining** → tick **Lightstrip** → **Save**. Tap the tile to switch the light on or off; drag across it to set brightness. To use it from the lock screen, turn on **Settings → Display → Lock screen → Control from locked device**.

## Automations

Open the clock icon in the app. Automations run **on the phone**, so they work while it is at home on your Wi-Fi. A run missed while you're out happens when you get back, up to 8 hours later and no later than 03:00.

| Trigger | Notes |
|---|---|
| **Time** | A time on chosen days. Allow **exact timing** when the app asks, or Android may run it a few minutes late. |
| **Sunset / sunrise** | With an offset. Uses your approximate location once; times are worked out on the phone. |
| **Arriving home** | When the phone joins your Wi-Fi and the light answers, and the light is off. Optional: only after dark, quiet late at night. At most once every 30 minutes. |

How arriving home works: about every 15 minutes the app notes whether you are home (on Wi-Fi and the light answers). Only while you are away does it ask Android to tell it when Wi-Fi connects, so Wi-Fi hiccups at home never count as arriving. Leaving is noticed within about 15 minutes (longer while the phone is asleep), so a very short trip may not count. A Wi-Fi outage of 20 minutes or more looks like a trip, so keep **Quiet late at night** on.

### Hooks for other apps

One command format everywhere: `power=on|off|toggle`, `preset=<name>`, `brightness=1-100`, `hue=0-360`, `saturation=0-100`, `warmth=0-100` (0 = warm), `keepoff=1` (set the look but leave the light off).

- **Launcher shortcuts:** long-press the Shining icon (Turn on, Turn off, Warm 40%).
- **Links / NFC tags:** `shining://light?power=off`, `shining://light?preset=Warm&brightness=40`
- **Tasker / MacroDroid:** send a broadcast with action `com.arva.shining.action.LIGHT`, package `com.arva.shining`, and an extra `command` = `power=toggle` (or separate extras with the same names).

## Things to know

- **Give the light a fixed IP** (a DHCP reservation in your router).
- Phone and light must be on the same network. Guest Wi-Fi or "AP isolation" will block it.
- OpenBeken's web login is unencrypted HTTP, so Shining sends the password only on the Wi-Fi it recognises as home. After changing routers, press **Test** in Settings.

## Development

```
app/    Android app (Jetpack Compose, ARVA design tokens, minSdk 26)
tools/  mock_openbeken.py (fake OpenBeken light)
```

```bash
./gradlew :app:testDebugUnitTest           # unit tests
python3 tools/mock_openbeken.py --port 8080  # then use <host>:8080 as the light's IP
```

## License

Code: MIT. Fonts (Brygada 1918, IBM Plex): SIL Open Font License, see `licenses/`. Icons: [Lucide](https://lucide.dev) (ISC). The ARVA name and leaf mark belong to ARVA and are not covered by the MIT licence.
