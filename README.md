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
  PIN : 483920
  Port: 7788
  Addresses:
    192.168.1.79     (wlp87s0f0)
    100.67.200.39    (tailscale0)  <- Tailscale, reachable from anywhere
```

**On the phone**

1. Install the APK from the [releases page](https://github.com/SebParoc/sebviewer/releases/latest)
   (Android asks once to allow installs from your browser / GitHub app).
2. Open SebViewer. PCs on the same Wi-Fi show up automatically; tap one, or type an address.
3. Enter the PIN and connect.

### From anywhere, not just the same Wi-Fi

The host listens on every address the PC has. The easiest way to reach it from outside your
home network is a VPN such as [Tailscale](https://tailscale.com) on both the PC and the phone:
then use the PC's Tailscale address (100.x.y.z) in the app. Traffic is plain WebSocket, so
only expose the port over a VPN or on a trusted LAN, never directly on the internet.

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

The round button in the corner opens the toolbar:

- **⌨** shows the phone keyboard; everything you type goes to the PC.
- **Drag** makes a one-finger drag hold the left button (move windows, select text).
- **Trackpad** moves the pointer relative to your finger, like a laptop touchpad.
- **Ctrl / Alt / Shift / Win** are sticky: tap one, then the next key or click gets the modifier.
- Arrows, Esc, Tab, Enter, Backspace, Del, Home/End, PgUp/PgDn, F1-F12, volume and play/pause.
- **Quality** picks resolution / frame rate presets (use *Data saver* over mobile data).
- **Fit** resets the zoom.

Bluetooth/USB keyboards attached to the phone also work, including Ctrl/Alt shortcuts.

## Host options

```
sebviewer-host --help
sebviewer-host --install-service     # start automatically at login (systemd user unit)
sebviewer-host --new-pin             # rotate the PIN
sebviewer-host --reset-permissions   # ask the Wayland screen-share question again
sebviewer-host --backend x11         # force the X11 backend
sebviewer-host --fps 30 --quality 70 --max-width 1920
```

Config lives in `~/.config/sebviewer/config.json` (PIN and the Wayland restore token).
A wrong PIN five times in a row locks that address out for 30 seconds.

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
