# sebviewer-host

Linux side of [SebViewer](https://github.com/SebParoc/sebviewer): streams your screen to the
Android app and injects the mouse/keyboard events it sends back.

```bash
curl -fsSL https://github.com/SebParoc/sebviewer/releases/latest/download/install-host.sh | bash
sebviewer-host            # prints the PIN and your addresses
```

Wayland (GNOME, KDE, sway) uses the desktop portal: the first start asks which screen to share,
after that it is remembered. X11 uses `mss` + `pynput` (`pip install 'sebviewer-host[x11]'`).
