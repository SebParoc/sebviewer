"""LAN discovery (mDNS / DNS-SD) and local address listing."""

from __future__ import annotations

import fcntl
import logging
import socket
import struct

log = logging.getLogger(__name__)

SERVICE_TYPE = "_sebviewer._tcp.local."


def list_ipv4() -> list[tuple[str, str]]:
    """Return (interface, address) pairs for every non-loopback IPv4 interface."""
    out = []
    for _idx, name in socket.if_nameindex():
        if name == "lo":
            continue
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            packed = struct.pack("256s", name.encode()[:15])
            addr = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, packed)[20:24])
            s.close()
        except OSError:
            continue
        out.append((name, addr))
    return out


def is_tailscale(addr: str) -> bool:
    first, second = (int(p) for p in addr.split(".")[:2])
    return first == 100 and 64 <= second <= 127


class Advertiser:
    """Registers the host with zeroconf so the app can find it without typing an IP."""

    def __init__(self, port: int, name: str) -> None:
        self.port = port
        self.name = name
        self._zc = None
        self._info = None

    def start(self) -> None:
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            log.warning("zeroconf not installed, discovery disabled")
            return
        addrs = [socket.inet_aton(a) for _n, a in list_ipv4() if not a.startswith("172.17.")]
        if not addrs:
            log.warning("no usable IPv4 address, discovery disabled")
            return
        safe = "".join(c for c in self.name if c.isalnum() or c in "-_ ")[:40] or "sebviewer"
        self._info = ServiceInfo(
            SERVICE_TYPE,
            f"{safe}.{SERVICE_TYPE}",
            addresses=addrs,
            port=self.port,
            properties={"name": self.name, "ver": "1"},
            server=f"{safe.replace(' ', '-')}.local.",
        )
        try:
            self._zc = Zeroconf()
            self._zc.register_service(self._info)
            log.info("Advertising %s on the LAN", self._info.name)
        except Exception as exc:  # noqa: BLE001
            log.warning("discovery disabled: %s", exc)
            self._zc = None

    def stop(self) -> None:
        if self._zc is not None:
            try:
                self._zc.unregister_service(self._info)
                self._zc.close()
            except Exception:  # noqa: BLE001
                pass
            self._zc = None
