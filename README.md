# SebViewer

Control your Linux PC from your Android phone, TeamViewer-style. Two parts:

| Part | What it is | Get it |
|------|-----------|--------|
| **Android app** | Shows the PC screen, sends taps, gestures and keyboard input | Download `SebViewer-x.y.z.apk` from the [latest release](https://github.com/SebParoc/sebviewer/releases/latest) |
| **Linux host** | Python program that captures the screen and injects input (Wayland via desktop portal, or X11) | One-line installer below |

## Quick start

**On the PC**

```bash
curl -fsSL https://github.com/SebParoc/sebviewer/releases/latest/download/install-host.sh | bash
sebviewer-host
```

The first start on Wayland pops up the desktop's "share your screen" dialog. Pick your monitor,
press *Share*, and it is remembered for next time. The host then prints something like:

```
  PIN : not required (run with --require-pin to enable)
  Port: 7788
  Addresses:
    192.168.1.79     (wlp87s0f0)
    100.67.200.39    (tailscale0)  <- Tailscale, reachable from anywhere
```

**On the phone**

1. Install the APK from the [releases page](https://github.com/SebParoc/sebviewer/releases/latest)
   (Android asks once to allow installs from your browser / GitHub app).
2. Open SebViewer. PCs on the same Wi-Fi show up automatically; tap one, or type an address.
3. Tap connect. If you started the host with `--require-pin`, enter the PIN it printed.

### From anywhere, not just the same Wi-Fi

Use [Tailscale](https://tailscale.com): a free private network between your own devices that
works from any Wi-Fi or mobile network, in any country, with no port forwarding. Traffic between
phone and PC is encrypted end to end (WireGuard), which matters because SebViewer itself speaks
plain WebSocket.

1. PC: `sudo dnf install tailscale && sudo tailscale up` (or the package for your distro), sign in.
2. Phone: install the Tailscale app from the Play Store and sign in with the **same account**.
   Leave the Tailscale VPN switched on.
3. `sebviewer-host` prints the PC's Tailscale name and address under *From anywhere*, e.g.
   `fedora-1.tail3a04e7.ts.net` / `100.67.200.39`. Type either one in the app and connect.
   After the first successful connection the PC appears under *Saved PCs*, so later it is one tap.

Tips for mobile data: pick *Data saver* or *Smooth* in the Quality menu. If the network
switches (Wi-Fi to 4G, tunnel, etc.) the app reconnects by itself a few times.

Do not forward port 7788 on your router instead; the PIN is the only protection on the
WebSocket, which is fine on a LAN or VPN but not on the open internet.

## Using the app

| Gesture | Action |
|---------|--------|
| Tap | Left click |
| Two quick taps | Double click |
| Long press | Right click |
| Two-finger tap | Right click |
| One finger drag | Move the pointer (hover) |
| Two-finger drag | Scroll |
| Pinch | Zoom in/out |
| Three-finger drag | Pan while zoomed |

The round button in the corner opens the control panel:

- **Keyboard** shows the phone keyboard (Gboard glide typing, voice input and autocorrect all
  work); everything you type goes to the PC. The screen shrinks to stay visible above it.
- **Trackpad** moves the pointer relative to your finger, like a laptop touchpad; a blue ring
  shows where the pointer is.
- **Drag** makes a one-finger drag hold the left button (move windows, select text).
- **Ctrl / Alt / Shift / Win** are sticky: tap one, then the next key or click gets the modifier.
- Arrows, Esc, Tab, Enter, Backspace, Del, Home/End, PgUp/PgDn, F1-F12, volume and play/pause.
- **Quality** picks resolution / frame rate presets (use *Data saver* over mobile data).
- **Paste** sends your phone's clipboard to the PC and presses Ctrl+V. Copying on the PC also
  puts the text on your phone's clipboard automatically, and vice versa when you return to the app.
- **Fit** resets the zoom. **Disconnect** ends the session.

The home screen lists PCs found on the Wi-Fi and PCs you connected to before, with a live
online/offline check; tap one to connect, long press to forget it.

Bluetooth/USB keyboards attached to the phone also work, including Ctrl/Alt shortcuts.

## Host options

```
sebviewer-host --help
sebviewer-host --install-service     # start automatically at login (systemd user unit)
sebviewer-host --require-pin         # ask the phone for a PIN (off by default)
sebviewer-host --show-pin            # print the PIN, --new-pin rotates it
sebviewer-host --reset-permissions   # ask the Wayland screen-share question again
sebviewer-host --backend x11         # force the X11 backend
sebviewer-host --fps 30 --quality 70 --max-width 1920
```

Config lives in `~/.config/sebviewer/config.json` (PIN and the Wayland restore token).
With `--require-pin`, five wrong PINs in a row lock that address out for 30 seconds. Without it,
anyone who can reach the port controls the PC, so keep the host on a trusted LAN or Tailscale only.

Clipboard sync needs the desktop's Clipboard portal (GNOME 46+, recent KDE). Text only.

### Requirements (host)

- Python 3.10+
- Wayland: PyGObject, GStreamer and the PipeWire GStreamer plugin, plus a desktop that implements
  the RemoteDesktop portal (GNOME, KDE Plasma, sway/wlroots with `xdg-desktop-portal-wlr`).
  Fedora: `sudo dnf install python3-gobject gstreamer1-plugins-base gstreamer1-plugins-good pipewire-gstreamer`
- X11: `pip install 'sebviewer-host[x11]'` (mss + pynput)

## Development

```
host/      Python package (sebviewer_host), `pip install -e host`
android/   Gradle project, `gradle assembleRelease` (JDK 17, Android SDK 35)
```

Releases are built by GitHub Actions: push a tag like `v0.2.0` and the workflow builds the signed
APK and the Python wheel and attaches them to a GitHub release.

### Protocol

WebSocket on port 7788. The client sends `{"t":"auth","pin":"…"}`, then JSON input events
(`move`, `click`, `btn`, `scroll`, `key`, `text`, `cfg`) with pointer coordinates normalised to
0..1. The host streams JPEG frames as binary messages and expects an `{"t":"ack"}` per frame
(at most two frames in flight), which keeps latency low on slow links.

## License

MIT
