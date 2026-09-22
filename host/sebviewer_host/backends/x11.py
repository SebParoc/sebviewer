"""X11 backend: mss for capture, pynput (XTest) for input."""

from __future__ import annotations

import logging
from typing import Optional

from PIL import Image, ImageDraw

from .base import BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT, Backend

log = logging.getLogger(__name__)


class X11Backend(Backend):
    def __init__(self) -> None:
        try:
            import mss  # noqa: F401
            from pynput import keyboard, mouse  # noqa: F401
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError(
                "X11 backend needs the 'mss' and 'pynput' packages: "
                "pip install 'sebviewer-host[x11]'"
            ) from exc
        self._sct = None
        self._mouse = None
        self._kb = None
        self._keymap = {}

    def start(self) -> None:
        import mss
        from pynput import keyboard, mouse

        self._sct = mss.mss()
        mon = self._sct.monitors[0]
        self.width, self.height = mon["width"], mon["height"]
        self._mouse = mouse.Controller()
        self._kb = keyboard.Controller()
        K = keyboard.Key
        self._keymap = {
            "space": K.space, "Return": K.enter, "BackSpace": K.backspace, "Tab": K.tab,
            "Escape": K.esc, "Delete": K.delete, "Insert": K.insert, "Home": K.home,
            "End": K.end, "Page_Up": K.page_up, "Page_Down": K.page_down, "Left": K.left,
            "Up": K.up, "Right": K.right, "Down": K.down, "Shift_L": K.shift,
            "Control_L": K.ctrl, "Alt_L": K.alt, "Super_L": K.cmd, "Caps_Lock": K.caps_lock,
            "Print": K.print_screen, "Menu": K.menu,
            "XF86AudioLowerVolume": K.media_volume_down, "XF86AudioMute": K.media_volume_mute,
            "XF86AudioRaiseVolume": K.media_volume_up, "XF86AudioPlay": K.media_play_pause,
            "XF86AudioPrev": K.media_previous, "XF86AudioNext": K.media_next,
        }
        for i in range(1, 13):
            self._keymap[f"F{i}"] = getattr(K, f"f{i}")
        log.info("X11 backend started, screen %sx%s", self.width, self.height)

    def grab(self) -> Optional[Image.Image]:
        mon = self._sct.monitors[0]
        shot = self._sct.grab(mon)
        img = Image.frombuffer("RGB", shot.size, shot.bgra, "raw", "BGRX", 0, 1)
        self._draw_cursor(img, mon)
        return img

    def _draw_cursor(self, img: Image.Image, mon: dict) -> None:
        try:
            x, y = self._mouse.position
        except Exception:  # pragma: no cover
            return
        x -= mon["left"]
        y -= mon["top"]
        d = ImageDraw.Draw(img)
        pts = [(x, y), (x, y + 17), (x + 4, y + 13), (x + 7, y + 20), (x + 10, y + 18),
               (x + 7, y + 12), (x + 12, y + 12)]
        d.polygon(pts, fill="white", outline="black")

    def pointer_move(self, x: float, y: float) -> None:
        self._mouse.position = (int(x), int(y))

    def pointer_button(self, button: int, down: bool) -> None:
        from pynput.mouse import Button

        b = {BUTTON_LEFT: Button.left, BUTTON_RIGHT: Button.right, BUTTON_MIDDLE: Button.middle}.get(button)
        if b is None:
            return
        if down:
            self._mouse.press(b)
        else:
            self._mouse.release(b)

    def scroll(self, dx: int, dy: int) -> None:
        # pynput: positive dy scrolls up, so invert to match "positive = down"
        self._mouse.scroll(int(dx), -int(dy))

    def key(self, name: str, down: bool) -> None:
        from pynput.keyboard import KeyCode

        k = self._keymap.get(name)
        if k is None:
            if len(name) != 1:
                log.debug("unknown key %r", name)
                return
            k = KeyCode.from_char(name)
        if down:
            self._kb.press(k)
        else:
            self._kb.release(k)

    def type_text(self, text: str) -> None:
        self._kb.type(text)
