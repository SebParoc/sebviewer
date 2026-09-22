"""Capture/input backends."""

import logging
import os

log = logging.getLogger(__name__)


def create_backend(kind: str = "auto"):
    """Instantiate the backend for the current display server."""
    if kind == "auto":
        session = os.environ.get("XDG_SESSION_TYPE", "").lower()
        if session == "wayland" or (not session and os.environ.get("WAYLAND_DISPLAY")):
            kind = "wayland"
        else:
            kind = "x11"
    log.info("Using %s backend", kind)
    if kind == "wayland":
        from .wayland import WaylandPortalBackend

        return WaylandPortalBackend()
    if kind == "x11":
        from .x11 import X11Backend

        return X11Backend()
    raise ValueError(f"unknown backend {kind!r}")
