"""Wayland backend using the xdg-desktop-portal RemoteDesktop + ScreenCast APIs.

Screen frames arrive through PipeWire (decoded with GStreamer), input is injected
through the portal's Notify* methods.  Works on GNOME, KDE, sway/wlroots with the
matching portal backends installed.

The first run pops up the desktop's "share your screen" dialog.  The portal
hands back a restore token which we persist, so later runs do not prompt again.
"""

from __future__ import annotations

import logging
import os
import threading
from typing import Optional

import gi

gi.require_version("Gst", "1.0")
gi.require_version("GstApp", "1.0")
gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")
from gi.repository import Gio, GLib, Gst, GstApp  # noqa: E402,F401
from PIL import Image  # noqa: E402

from ..config import load_config, save_config  # noqa: E402
from ..keys import keysym_for_char, keysym_for_name  # noqa: E402
from .base import BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT, Backend  # noqa: E402

log = logging.getLogger(__name__)

PORTAL_BUS = "org.freedesktop.portal.Desktop"
PORTAL_PATH = "/org/freedesktop/portal/desktop"
IFACE_REMOTE = "org.freedesktop.portal.RemoteDesktop"
IFACE_SCREENCAST = "org.freedesktop.portal.ScreenCast"
IFACE_REQUEST = "org.freedesktop.portal.Request"
IFACE_SESSION = "org.freedesktop.portal.Session"

DEVICE_KEYBOARD = 1
DEVICE_POINTER = 2
SOURCE_MONITOR = 1
CURSOR_EMBEDDED = 2
PERSIST_UNTIL_REVOKED = 2

# Linux evdev button codes
EVDEV_BUTTONS = {BUTTON_LEFT: 0x110, BUTTON_RIGHT: 0x111, BUTTON_MIDDLE: 0x112}


class PortalError(RuntimeError):
    pass


class WaylandPortalBackend(Backend):
    def __init__(self) -> None:
        self.bus: Optional[Gio.DBusConnection] = None
        self.session: Optional[str] = None
        self.node_id: Optional[int] = None
        self.pipeline: Optional[Gst.Pipeline] = None
        self.appsink = None
        self._counter = 0
        self._alive = False
        self._loop: Optional[GLib.MainLoop] = None
        self._loop_thread: Optional[threading.Thread] = None
        self._last_image: Optional[Image.Image] = None
        self._pressed_keys: set[int] = set()
        self._pressed_buttons: set[int] = set()

    # ------------------------------------------------------------------ portal
    def _token(self) -> str:
        self._counter += 1
        return f"sebviewer{os.getpid()}_{self._counter}"

    def _request(self, iface: str, method: str, args: list, options: dict) -> dict:
        """Call a portal method that answers via a Request object and wait for it."""
        token = self._token()
        options = dict(options)
        options["handle_token"] = GLib.Variant("s", token)
        sender = self.bus.get_unique_name()[1:].replace(".", "_")
        request_path = f"/org/freedesktop/portal/desktop/request/{sender}/{token}"

        loop = GLib.MainLoop()
        result: dict = {}

        def on_response(_conn, _sender, _path, _iface, _signal, params):
            code, results = params.unpack()
            result["code"] = code
            result["results"] = results
            loop.quit()

        sub = self.bus.signal_subscribe(
            PORTAL_BUS, IFACE_REQUEST, "Response", request_path, None,
            Gio.DBusSignalFlags.NONE, on_response,
        )
        sig = "(" + "".join(a.get_type_string() for a in args) + "a{sv})"
        params = GLib.Variant(sig, [a.unpack() for a in args] + [options])
        try:
            self.bus.call_sync(PORTAL_BUS, PORTAL_PATH, iface, method, params, None,
                               Gio.DBusCallFlags.NONE, -1, None)
            loop.run()
        finally:
            self.bus.signal_unsubscribe(sub)
        if result.get("code") != 0:
            raise PortalError(f"{method} was cancelled or failed (code {result.get('code')})")
        return result["results"]

    def start(self) -> None:
        Gst.init(None)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        cfg = load_config()
        restore_token = cfg.get("portal_restore_token")

        res = self._request(IFACE_REMOTE, "CreateSession", [],
                            {"session_handle_token": GLib.Variant("s", self._token())})
        self.session = res["session_handle"]
        session_v = GLib.Variant("o", self.session)

        dev_opts = {
            "types": GLib.Variant("u", DEVICE_KEYBOARD | DEVICE_POINTER),
            "persist_mode": GLib.Variant("u", PERSIST_UNTIL_REVOKED),
        }
        if restore_token:
            dev_opts["restore_token"] = GLib.Variant("s", restore_token)
        self._request(IFACE_REMOTE, "SelectDevices", [session_v], dev_opts)

        self._request(IFACE_SCREENCAST, "SelectSources", [session_v], {
            "types": GLib.Variant("u", SOURCE_MONITOR),
            "multiple": GLib.Variant("b", False),
            "cursor_mode": GLib.Variant("u", CURSOR_EMBEDDED),
        })

        if not restore_token:
            log.warning("A system dialog is asking which screen to share. "
                        "Pick your monitor and press Share (only needed once).")
        res = self._request(IFACE_REMOTE, "Start", [session_v, GLib.Variant("s", "")], {})
        streams = res.get("streams") or []
        if not streams:
            raise PortalError("no screen was shared")
        self.node_id, props = streams[0]
        size = props.get("size")
        if size:
            self.width, self.height = int(size[0]), int(size[1])
        new_token = res.get("restore_token")
        if new_token and new_token != restore_token:
            cfg["portal_restore_token"] = new_token
            save_config(cfg)

        # PipeWire fd
        reply, fdlist = self.bus.call_with_unix_fd_list_sync(
            PORTAL_BUS, PORTAL_PATH, IFACE_SCREENCAST, "OpenPipeWireRemote",
            GLib.Variant("(oa{sv})", [self.session, {}]), GLib.VariantType("(h)"),
            Gio.DBusCallFlags.NONE, -1, None, None,
        )
        fd = fdlist.get(reply.unpack()[0])

        self._start_pipeline(fd)
        self._alive = True

        # keep a main loop running for the session Closed signal
        self.bus.signal_subscribe(PORTAL_BUS, IFACE_SESSION, "Closed", self.session, None,
                                  Gio.DBusSignalFlags.NONE, self._on_closed)
        self._loop = GLib.MainLoop()
        self._loop_thread = threading.Thread(target=self._loop.run, name="glib-loop", daemon=True)
        self._loop_thread.start()
        log.info("Wayland portal session started, stream %sx%s", self.width, self.height)

    def _on_closed(self, *_args) -> None:
        log.warning("Screen sharing session was closed by the desktop")
        self._alive = False

    def _start_pipeline(self, fd: int) -> None:
        desc = (
            f"pipewiresrc fd={fd} path={self.node_id} do-timestamp=true keepalive-time=500 ! "
            "videoconvert n-threads=4 ! video/x-raw,format=BGRx ! "
            "appsink name=sink drop=true max-buffers=1 sync=false emit-signals=false"
        )
        self.pipeline = Gst.parse_launch(desc)
        self.appsink = self.pipeline.get_by_name("sink")
        ret = self.pipeline.set_state(Gst.State.PLAYING)
        if ret == Gst.StateChangeReturn.FAILURE:
            raise PortalError("failed to start the PipeWire pipeline")
        # wait for the first frame so width/height are known for sure
        sample = self.appsink.try_pull_sample(5 * Gst.SECOND)
        if sample is None:
            raise PortalError("no video arrived from PipeWire")
        self._consume(sample)

    def _consume(self, sample) -> Image.Image:
        caps = sample.get_caps().get_structure(0)
        w, h = caps.get_value("width"), caps.get_value("height")
        buf = sample.get_buffer()
        ok, info = buf.map(Gst.MapFlags.READ)
        if not ok:
            raise PortalError("could not map video buffer")
        try:
            stride = len(info.data) // h
            img = Image.frombuffer("RGB", (w, h), bytes(info.data), "raw", "BGRX", stride, 1)
        finally:
            buf.unmap(info)
        self.width, self.height = w, h
        self._last_image = img
        return img

    # ----------------------------------------------------------------- backend
    @property
    def alive(self) -> bool:
        return self._alive

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
                self.bus.call_sync(PORTAL_BUS, self.session, IFACE_SESSION, "Close", None, None,
                                   Gio.DBusCallFlags.NONE, 1000, None)
            except GLib.Error:
                pass
        if self._loop is not None:
            self._loop.quit()

    def grab(self) -> Optional[Image.Image]:
        if not self._alive or self.appsink is None:
            return None
        sample = self.appsink.try_pull_sample(100 * Gst.MSECOND)
        if sample is None:
            return None
        return self._consume(sample)

    def _notify(self, method: str, sig: str, *args) -> None:
        """Send one input event and wait for the portal to acknowledge it.

        The calls must be synchronous: xdg-desktop-portal handles each method call
        on a thread pool, so two in-flight async calls (a key press and its release)
        can reach the compositor in the wrong order.  Mutter then drops the release
        as a duplicate and the key stays pressed, auto-repeating forever.
        """
        params = GLib.Variant("(oa{sv}" + sig + ")", [self.session, {}, *args])
        try:
            self.bus.call_sync(PORTAL_BUS, PORTAL_PATH, IFACE_REMOTE, method, params, None,
                               Gio.DBusCallFlags.NONE, 2000, None)
        except GLib.Error as exc:
            log.warning("%s failed: %s", method, exc.message)

    def pointer_move(self, x: float, y: float) -> None:
        self._notify("NotifyPointerMotionAbsolute", "udd", self.node_id, float(x), float(y))

    def pointer_button(self, button: int, down: bool) -> None:
        code = EVDEV_BUTTONS.get(button)
        if code is None:
            return
        self._notify("NotifyPointerButton", "iu", code, 1 if down else 0)
        (self._pressed_buttons.add if down else self._pressed_buttons.discard)(code)

    def scroll(self, dx: int, dy: int) -> None:
        if dy:
            self._notify("NotifyPointerAxisDiscrete", "ui", 0, int(dy))
        if dx:
            self._notify("NotifyPointerAxisDiscrete", "ui", 1, int(dx))

    def _keysym(self, keysym: int, down: bool) -> None:
        self._notify("NotifyKeyboardKeysym", "iu", int(keysym), 1 if down else 0)
        (self._pressed_keys.add if down else self._pressed_keys.discard)(int(keysym))

    def release_all(self) -> None:
        """Release anything we still hold, so nothing stays stuck on the desktop."""
        if not self._alive or self.bus is None:
            return
        for ks in list(self._pressed_keys):
            self._keysym(ks, False)
        for code in list(self._pressed_buttons):
            self._notify("NotifyPointerButton", "iu", code, 0)
        self._pressed_buttons.clear()

    def key(self, name: str, down: bool) -> None:
        keysym = keysym_for_name(name)
        if keysym is None:
            log.debug("unknown key %r", name)
            return
        self._keysym(keysym, down)

    def type_text(self, text: str) -> None:
        for ch in text:
            ks = keysym_for_char(ch)
            self._keysym(ks, True)
            self._keysym(ks, False)
