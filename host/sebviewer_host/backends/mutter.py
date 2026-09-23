"""GNOME backend that talks to Mutter's own RemoteDesktop/ScreenCast D-Bus API.

This is the interface GNOME's built-in Remote Desktop (gnome-remote-desktop) uses.
It is only reachable by processes running as the logged-in user, and unlike the
xdg-desktop-portal it never shows a "share your screen" dialog.  That matters for
an unattended PC: after a suspend the portal can hand back a screen share with no
keyboard/mouse, and nobody is there to click "Share" again.
"""

from __future__ import annotations

import logging
import threading

from .wayland import PortalError, WaylandPortalBackend  # sets gi versions first

from gi.repository import Gio, GLib, Gst  # noqa: E402

log = logging.getLogger(__name__)

RD_BUS = "org.gnome.Mutter.RemoteDesktop"
RD_PATH = "/org/gnome/Mutter/RemoteDesktop"
RD_IFACE = "org.gnome.Mutter.RemoteDesktop"
RD_SESSION = "org.gnome.Mutter.RemoteDesktop.Session"
SC_BUS = "org.gnome.Mutter.ScreenCast"
SC_PATH = "/org/gnome/Mutter/ScreenCast"
SC_IFACE = "org.gnome.Mutter.ScreenCast"
SC_SESSION = "org.gnome.Mutter.ScreenCast.Session"
SC_STREAM = "org.gnome.Mutter.ScreenCast.Stream"
DC_BUS = "org.gnome.Mutter.DisplayConfig"
DC_PATH = "/org/gnome/Mutter/DisplayConfig"
CURSOR_EMBEDDED = 1  # Mutter's enum: 0 hidden, 1 embedded, 2 metadata


def available() -> bool:
    try:
        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        bus.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                      "GetNameOwner", GLib.Variant("(s)", [RD_BUS]), None,
                      Gio.DBusCallFlags.NONE, 1000, None)
        return True
    except GLib.Error:
        return False


class MutterBackend(WaylandPortalBackend):
    """Reuses the portal backend's GStreamer capture and key bookkeeping."""

    def _call(self, bus_name, path, iface, method, params=None, reply=None, timeout=5000):
        return self.bus.call_sync(bus_name, path, iface, method, params,
                                  GLib.VariantType(reply) if reply else None,
                                  Gio.DBusCallFlags.NONE, timeout, None)

    def _primary_connector(self) -> str:
        state = self._call(DC_BUS, DC_PATH, "org.gnome.Mutter.DisplayConfig",
                           "GetCurrentState", None, "(ua((ssss)a(siiddada{sv})a{sv})"
                           "a(iiduba(ssss)a{sv})a{sv})").unpack()
        logical = state[2]
        for _x, _y, _scale, _transform, primary, monitors, _props in logical:
            if primary and monitors:
                return monitors[0][0]
        return state[1][0][0][0]

    def start(self) -> None:
        Gst.init(None)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self._loop = GLib.MainLoop()
        self._loop_thread = threading.Thread(target=self._loop.run, name="glib-loop", daemon=True)
        self._loop_thread.start()

        self.rd_session = self._call(RD_BUS, RD_PATH, RD_IFACE, "CreateSession",
                                     None, "(o)").unpack()[0]
        session_id = self._call(RD_BUS, self.rd_session, "org.freedesktop.DBus.Properties", "Get",
                                GLib.Variant("(ss)", [RD_SESSION, "SessionId"]),
                                "(v)").unpack()[0]
        sc_session = self._call(SC_BUS, SC_PATH, SC_IFACE, "CreateSession",
                                GLib.Variant("(a{sv})", [{
                                    "remote-desktop-session-id": GLib.Variant("s", session_id)}]),
                                "(o)").unpack()[0]
        connector = self._primary_connector()
        self.stream_path = self._call(SC_BUS, sc_session, SC_SESSION, "RecordMonitor",
                                      GLib.Variant("(sa{sv})", [connector, {
                                          "cursor-mode": GLib.Variant("u", CURSOR_EMBEDDED)}]),
                                      "(o)").unpack()[0]

        got_node = threading.Event()

        def on_stream(_c, _s, _p, _i, _sig, params):
            self.node_id = params.unpack()[0]
            got_node.set()

        self.bus.signal_subscribe(SC_BUS, SC_STREAM, "PipeWireStreamAdded", self.stream_path,
                                  None, Gio.DBusSignalFlags.NONE, on_stream)
        self.bus.signal_subscribe(None, RD_SESSION, "Closed", None, None,
                                  Gio.DBusSignalFlags.NONE, self._on_rd_closed)
        self._call(RD_BUS, self.rd_session, RD_SESSION, "Start")
        if not got_node.wait(10):
            raise PortalError("Mutter did not provide a PipeWire stream")

        self.session = self.rd_session
        self._start_pipeline(None)
        self._alive = True
        log.info("Mutter remote desktop session started on %s, stream %sx%s (no dialog needed)",
                 connector, self.width, self.height)

    def _on_rd_closed(self, _c, sender, path, _i, _sig, _params) -> None:
        log.info("Closed signal for %s (ours: %s)", path, self.rd_session)
        if path == self.rd_session:
            self._on_closed()

    def _start_pipeline(self, fd) -> None:
        # Mutter streams live on the user's default PipeWire daemon, so no fd is needed.
        desc = (
            f"pipewiresrc path={self.node_id} do-timestamp=true keepalive-time=500 ! "
            "videoconvert n-threads=4 ! video/x-raw,format=BGRx ! "
            "appsink name=sink drop=true max-buffers=1 sync=false emit-signals=false"
        )
        self.pipeline = Gst.parse_launch(desc)
        self.appsink = self.pipeline.get_by_name("sink")
        if self.pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            raise PortalError("failed to start the PipeWire pipeline")
        sample = self.appsink.try_pull_sample(5 * Gst.SECOND)
        if sample is None:
            raise PortalError("no video arrived from PipeWire")
        self._consume(sample)

    def stop(self) -> None:
        try:
            self.release_all()
        except Exception:  # noqa: BLE001
            pass
        self._alive = False
        if self.pipeline is not None:
            self.pipeline.set_state(Gst.State.NULL)
            self.pipeline = None
        if self.bus is not None and self.session:
            try:
                self._call(RD_BUS, self.rd_session, RD_SESSION, "Stop", timeout=1000)
            except GLib.Error:
                pass
        if self._loop is not None:
            self._loop.quit()

    # ------------------------------------------------------------------ input
    def _rd(self, method: str, sig: str, *args) -> None:
        """Synchronous on purpose: keeps press/release ordering (see _notify)."""
        try:
            self._call(RD_BUS, self.rd_session, RD_SESSION, method,
                       GLib.Variant("(" + sig + ")", list(args)), timeout=2000)
        except GLib.Error as exc:
            log.warning("%s failed: %s", method, exc.message)

    def pointer_move(self, x: float, y: float) -> None:
        self._rd("NotifyPointerMotionAbsolute", "sdd", self.stream_path, float(x), float(y))

    def pointer_button(self, button: int, down: bool) -> None:
        from .wayland import EVDEV_BUTTONS
        code = EVDEV_BUTTONS.get(button)
        if code is None:
            return
        self._rd("NotifyPointerButton", "ib", code, bool(down))
        (self._pressed_buttons.add if down else self._pressed_buttons.discard)(code)

    def scroll(self, dx: int, dy: int) -> None:
        if dy:
            self._rd("NotifyPointerAxisDiscrete", "ui", 0, int(dy))
        if dx:
            self._rd("NotifyPointerAxisDiscrete", "ui", 1, int(dx))

    def _keysym(self, keysym: int, down: bool) -> None:
        self._rd("NotifyKeyboardKeysym", "ub", int(keysym), bool(down))
        (self._pressed_keys.add if down else self._pressed_keys.discard)(int(keysym))

    def release_all(self) -> None:
        if not self._alive or self.bus is None:
            return
        for ks in list(self._pressed_keys):
            self._keysym(ks, False)
        for code in list(self._pressed_buttons):
            self._rd("NotifyPointerButton", "ib", code, False)
        self._pressed_buttons.clear()

    def set_clipboard(self, text: str) -> None:
        return  # clipboard is wired up separately
