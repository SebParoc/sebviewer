"""Backend interface."""

from __future__ import annotations

from typing import Optional

from PIL import Image

# Mouse buttons as sent by the client
BUTTON_LEFT = 1
BUTTON_MIDDLE = 2
BUTTON_RIGHT = 3


class Backend:
    """A screen source plus an input sink.

    Coordinates handed to pointer_* are in *capture pixels* (0..width, 0..height).
    """

    width: int = 0
    height: int = 0

    def start(self) -> None:
        raise NotImplementedError

    def stop(self) -> None:
        pass

    @property
    def alive(self) -> bool:
        return True

    def grab(self) -> Optional[Image.Image]:
        """Return the current screen as an RGB image, or None if nothing new."""
        raise NotImplementedError

    def pointer_move(self, x: float, y: float) -> None:
        raise NotImplementedError

    def pointer_button(self, button: int, down: bool) -> None:
        raise NotImplementedError

    def scroll(self, dx: int, dy: int) -> None:
        """Scroll by whole steps. Positive dy scrolls down, positive dx scrolls right."""
        raise NotImplementedError

    def key(self, name: str, down: bool) -> None:
        raise NotImplementedError

    def type_text(self, text: str) -> None:
        raise NotImplementedError

    def release_all(self) -> None:
        """Release every key/button this backend still holds down."""
