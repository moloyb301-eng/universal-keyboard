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
| **Claude** tab | A Claude Code session running on the phone (see below) |
| **Keyboard → PC / Claude** | Choose where the phone keyboard types |

**Smart typing** (in settings) turns on your keyboard's predictions,
autocorrect and swipe typing. Corrections are sent as backspaces plus
retyping. Leave it off for the most reliable typing.

## Claude Code panel

If you run [Claude Code](https://claude.com/claude-code) on the phone itself
(for example in a Linux environment app such as Horus), the **Claude** tab shows
that session next to the trackpad, and you type into it with the same phone
keyboard.

A small bridge script runs next to Claude Code and serves its terminal to the
app over `ws://127.0.0.1` only. It needs nothing but Python 3.

### Start the bridge (in Horus)

1. Get the script into your Linux environment, e.g. clone this repository or
   download only the bridge:

   ```sh
   curl -fsSLO https://raw.githubusercontent.com/moloyb301-eng/universal-keyboard/main/bridge/claude_bridge.py
   ```

2. From an interactive terminal, in the folder you want Claude to work in:

   ```sh
   python3 claude_bridge.py            # runs `claude`
   python3 claude_bridge.py --shell    # or your shell instead
   python3 claude_bridge.py -- claude --continue   # any command after --
   ```

   On first run it prints a **pairing code** like `XXXX-XXXX-XXXX` and saves it
   (readable only by you) in `~/.config/universal-keyboard/bridge-token`.
   Show it again with `--show-code`; replace it with `--new-code`.

3. Keep it running (leave the terminal open). Other options: `--port`
   (default 8765), `--cwd DIR`, `--help`.

### Pair the app

1. In Universal Keyboard, open the **Claude** tab.
2. Enter the pairing code once (and the port, if you changed it). The app
   remembers it in its private storage.

The tab shows the connection state and reconnects by itself if the bridge
restarts. The session keeps running while the app is closed and picks up where
it was when you come back. If Claude exits, tap **Restart** for a new session.

### Typing into Claude

Opening the Claude tab switches typing to **Keyboard → Claude**, going back to
the trackpad switches it to **Keyboard → PC**. Tap the button to override
either way. In Claude mode, Enter, Backspace, arrows, Tab, Esc and the sticky
Ctrl/Alt/Shift (e.g. **Ctrl** then `c`) go to the terminal; the mouse and
trackpad always control the PC.

### Security

- The bridge listens on 127.0.0.1 only and never on your network.
- Other apps on the phone can reach 127.0.0.1 too, so every connection must
  send the pairing code first; wrong codes are refused and slowed down. Treat
  the code like a password: whoever has it can type into Claude Code.
- The terminal page is bundled with the app (xterm.js, MIT license, in
  `app/src/main/assets/terminal/vendor/`); the app loads nothing remote, and
  plain-text traffic is allowed only to 127.0.0.1.

## Limitations

- Needs Android 9 or newer, and a phone whose maker hasn't disabled the
  Bluetooth HID device profile (most do).
- Assumes the US keyboard layout on Windows. Emoji and accented
  characters are skipped.
- Scrolling is mouse-wheel style, not a Windows Precision Touchpad.
- Typing into Claude uses the same US-layout character set, so emoji and
  accented characters are skipped there too.

## How it works

- `hid/HidController.kt` registers the phone as a Bluetooth HID keyboard + mouse
  using Android's `BluetoothHidDevice` API and sends input reports.
- `input/HidInputConnection.kt` gives the phone keyboard a hidden text buffer.
  After each edit it diffs the buffer against what's already on the host and
  sends only the changes (backspaces, characters, arrow keys).
- `ui/TrackpadView.kt` turns touch gestures into mouse reports.
- `input/KeyOutput.kt` sends those keystrokes to the selected target: the
  Bluetooth host, or `claude/ClaudeTerminal.kt`, which turns them into terminal
  input (`claude/TerminalKeys.kt`) for the xterm.js page in a WebView.
- `bridge/claude_bridge.py` runs Claude Code in a pseudo-terminal and serves it
  over a WebSocket on 127.0.0.1 (standard library only).

xterm.js 6.0.0 and @xterm/addon-fit 0.11.0 are vendored unmodified from the npm
registry tarballs, checked against the registry's sha512 integrity values.

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
