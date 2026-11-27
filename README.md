# Universal Keyboard

[![Build APK](https://github.com/moloyb301-eng/universal-keyboard/actions/workflows/build.yml/badge.svg)](https://github.com/moloyb301-eng/universal-keyboard/actions/workflows/build.yml)

Turn your Android phone into a Bluetooth **keyboard + trackpad** for a Windows
handheld (ROG Ally, Legion Go, MSI Claw, …) docked to a screen, or any PC.

You type with the keyboard you already use on your phone (Samsung Keyboard,
Gboard, …). The app sends each keystroke to the handheld, which sees an ordinary
Bluetooth keyboard and mouse. No drivers or companion app needed on Windows.

```
┌─────────────────────────┐
│ ● Connected   Pair Hosts│
├─────────────────────────┤
│                         │
│        TRACKPAD         │  move · tap = click
│                         │  2 fingers = scroll / right-click
├────────────┬────────────┤
│ Left click │ Right click│
├────────────┴────────────┤
│ Ctrl Alt Shift Win Esc ←│  scrolls sideways for more keys
├─────────────────────────┤
│   your phone keyboard   │
└─────────────────────────┘
```

## Install

1. On your phone, open the **[nightly release](https://github.com/moloyb301-eng/universal-keyboard/releases/tag/nightly)**
   and download `universal-keyboard.apk`.
2. Open it and allow installing from your browser/files app when asked.
3. Launch **Universal Keyboard** and allow **Nearby devices**.

Direct APK link: https://github.com/moloyb301-eng/universal-keyboard/releases/download/nightly/universal-keyboard.apk

After that, the app checks for new builds itself and offers an **Update** button
(also under ⚙ → **Check for updates**).

## Pair with your handheld

1. In the app, tap **Pair**. Your phone becomes discoverable for 3 minutes.
2. On the handheld: **Settings → Bluetooth & devices → Add device → Bluetooth**.
3. Pick your phone and confirm the code on both screens.

After that, open the app and it reconnects to the last handheld automatically,
or choose one under **Hosts**.

## Using it

| Do this | To get |
|---|---|
| Type on your phone keyboard | Text on the handheld |
| 1-finger drag / tap on the trackpad | Move pointer / left click |
| 2-finger drag / tap | Scroll / right click |
| Double-tap then drag | Click-and-drag (select text, move windows) |
| Tap **Ctrl**, then type `c` | Ctrl+C (one-shot modifier) |
| Tap **Alt** twice, tap **Tab** repeatedly, tap **Alt** again | Hold Alt and switch windows |
| **⌨** button | Show/hide the phone keyboard |
| **⚙** button | Pointer/scroll speed, natural scrolling, smart typing |

**Smart typing** (in settings) turns on your keyboard's predictions,
autocorrect and swipe typing. Corrections are sent as backspaces plus
retyping. Leave it off for the most reliable typing.

## Limitations

- Needs Android 9 or newer, and a phone whose maker hasn't disabled the
  Bluetooth HID device profile. Samsung Galaxy phones support it.
- Assumes the US keyboard layout on Windows. Emoji and accented
  characters are skipped.
- Scrolling is mouse-wheel style, not a Windows Precision Touchpad.

## How it works

- `hid/HidController.kt` registers the phone as a Bluetooth HID keyboard + mouse
  using Android's `BluetoothHidDevice` API and sends input reports.
- `input/HidInputConnection.kt` gives the phone keyboard a hidden text buffer.
  After each edit it diffs the buffer against what's already on the host and
  sends only the changes (backspaces, characters, arrow keys).
- `ui/TrackpadView.kt` turns touch gestures into mouse reports.

## Building

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`)
and published as the `nightly` release. Pushing a tag like `v0.1.0` creates a
versioned release.

To build locally you need JDK 17, the Android SDK, and Gradle 8.10+:

```sh
gradle assembleDebug
```

### Signing

Android only installs an update over an existing app if both are signed with the
same key. CI signs every build with one permanent key stored in the repository
secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD` and
`SIGNING_KEY_ALIAS`. Builds on `main` fail instead of publishing if those
secrets are missing. Keep a private backup of the keystore: if it is lost,
every phone has to uninstall and reinstall once.

## License

[MIT](LICENSE)
