"""LAN discovery (mDNS / DNS-SD) and local address listing."""

from __future__ import annotations

import fcntl
import logging
import socket
import struct
import threading

log = logging.getLogger(__name__)

SERVICE_TYPE = "_sebviewer._tcp.local."
VIRTUAL_IFACES = ("docker", "br-", "veth", "virbr", "tailscale", "tun", "wg")
REFRESH_S = 10


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


def lan_ipv4() -> list[tuple[str, str]]:
    """Like list_ipv4(), without Tailscale and virtual (Docker, VM, VPN) interfaces."""
    return [(n, a) for n, a in list_ipv4()
            if not is_tailscale(a) and not n.startswith(VIRTUAL_IFACES)]


class Advertiser:
    """Registers the host with zeroconf so the app can find it without typing an IP.

    Only LAN addresses are announced: whoever hears mDNS is on the LAN, and a phone that
    picked the Tailscale or Docker address from the list could not connect.  They are
    re-checked every few seconds, as DHCP and Wi-Fi changes happen while the host runs.
    """

    def __init__(self, port: int, name: str) -> None:
        self.port = port
        self.name = name
        self._zc = None
        self._info = None
        self._addrs: list[str] = []
        self._stop = threading.Event()

    def start(self) -> None:
        try:
            from zeroconf import Zeroconf
        except ImportError:
            log.warning("zeroconf not installed, discovery disabled")
            return
        try:
            self._zc = Zeroconf()
            self._refresh()
        except Exception as exc:  # noqa: BLE001
            log.warning("discovery disabled: %s", exc)
            self.stop()
            return
        threading.Thread(target=self._watch, name="mdns", daemon=True).start()

    def _watch(self) -> None:
        while not self._stop.wait(REFRESH_S):
            try:
                self._refresh()
            except Exception as exc:  # noqa: BLE001
                log.warning("discovery refresh failed: %s", exc)

    def _refresh(self) -> None:
        from zeroconf import ServiceInfo

        addrs = [a for _n, a in lan_ipv4()]
        if addrs == self._addrs or self._zc is None:
            return
        if not addrs:
            log.warning("no LAN address, not advertising until there is one")
            if self._info is not None:
                self._zc.unregister_service(self._info)
                self._info = None
            self._addrs = addrs
            return
        safe = "".join(c for c in self.name if c.isalnum() or c in "-_ ")[:40] or "sebviewer"
        info = ServiceInfo(
            SERVICE_TYPE,
            f"{safe}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(a) for a in addrs],
            port=self.port,
            properties={"name": self.name, "ver": "1"},
            server=f"{safe.replace(' ', '-')}.local.",
        )
        if self._info is None:
            self._zc.register_service(info)
        else:
            self._zc.update_service(info)
        self._info = info
        self._addrs = addrs  # only once announced, so a failure is retried next round
        log.info("Advertising %s on the LAN at %s", info.name, ", ".join(addrs))

    def stop(self) -> None:
        self._stop.set()
        if self._zc is not None:
            try:
                if self._info is not None:
                    self._zc.unregister_service(self._info)
                self._zc.close()
            except Exception:  # noqa: BLE001
                pass
            self._zc = None
            self._info = None


def tailscale_info() -> dict | None:
    """Return {"ip": ..., "dns": ...} if Tailscale is installed and connected."""
    import json
    import shutil
    import subprocess

    exe = shutil.which("tailscale")
    if not exe:
        return None
    try:
        out = subprocess.run([exe, "status", "--json"], capture_output=True, text=True, timeout=3)
        data = json.loads(out.stdout)
        me = data.get("Self") or {}
        ips = [ip for ip in me.get("TailscaleIPs", []) if "." in ip]
        if not ips or not me.get("Online", True):
            return None
        return {"ip": ips[0], "dns": (me.get("DNSName") or "").rstrip(".")}
    except Exception:  # noqa: BLE001
        return None
