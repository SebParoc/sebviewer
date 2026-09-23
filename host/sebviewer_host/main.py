"""Command line entry point."""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import socket
import subprocess
import sys
from pathlib import Path

from . import __version__
from .config import CONFIG_FILE, get_or_create_pin, load_config, save_config
from .discovery import Advertiser, is_tailscale, list_ipv4, tailscale_info
from .server import Server, StreamSettings

log = logging.getLogger("sebviewer")

SERVICE_NAME = "sebviewer-host.service"


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="sebviewer-host",
                                description="Let the SebViewer Android app control this PC.")
    p.add_argument("--port", type=int, default=int(os.environ.get("SEBVIEWER_PORT", 7788)))
    p.add_argument("--bind", default="0.0.0.0", help="address to listen on (default all)")
    p.add_argument("--pin", help="require this PIN (implies --require-pin)")
    p.add_argument("--require-pin", action="store_true",
                   help="ask the phone for the saved PIN (off by default: personal use on a "
                        "trusted LAN/VPN). Manage it with --show-pin / --new-pin.")
    p.add_argument("--fps", type=float, default=20)
    p.add_argument("--quality", type=int, default=60, help="JPEG quality 10-95")
    p.add_argument("--max-width", type=int, default=1280, help="downscale frames to this width")
    p.add_argument("--backend", choices=["auto", "wayland", "x11"], default="auto")
    p.add_argument("--no-discovery", action="store_true", help="do not advertise on the LAN")
    p.add_argument("--reset-permissions", action="store_true",
                   help="forget the Wayland screen-share permission (asks again next start)")
    p.add_argument("--show-pin", action="store_true", help="print the PIN and exit")
    p.add_argument("--new-pin", action="store_true", help="generate a new random PIN and exit")
    p.add_argument("--install-service", action="store_true",
                   help="install and enable a systemd user service that starts at login")
    p.add_argument("--uninstall-service", action="store_true")
    p.add_argument("-v", "--verbose", action="store_true")
    p.add_argument("--version", action="version", version=__version__)
    return p.parse_args(argv)


def install_service(args: argparse.Namespace) -> int:
    exe = Path(sys.argv[0]).resolve()
    unit_dir = Path.home() / ".config" / "systemd" / "user"
    unit_dir.mkdir(parents=True, exist_ok=True)
    unit = unit_dir / SERVICE_NAME
    unit.write_text(f"""[Unit]
Description=SebViewer host (remote control from the SebViewer app)
After=graphical-session.target
PartOf=graphical-session.target

[Service]
ExecStart={exe} --port {args.port}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=graphical-session.target
""")
    subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
    subprocess.run(["systemctl", "--user", "enable", "--now", SERVICE_NAME], check=False)
    print(f"Installed {unit}\nCheck it with: systemctl --user status {SERVICE_NAME}")
    return 0


def uninstall_service() -> int:
    subprocess.run(["systemctl", "--user", "disable", "--now", SERVICE_NAME], check=False)
    unit = Path.home() / ".config" / "systemd" / "user" / SERVICE_NAME
    if unit.exists():
        unit.unlink()
    subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
    print("Service removed")
    return 0


def print_banner(port: int, pin: str | None) -> None:
    print("=" * 56)
    print(f"  SebViewer host {__version__} on {socket.gethostname()}")
    print("=" * 56)
    print(f"  PIN : {pin}" if pin else "  PIN : not required (run with --require-pin to enable)")
    print(f"  Port: {port}")
    print("  On this network:")
    for name, addr in list_ipv4():
        if is_tailscale(addr) or name.startswith(("docker", "br-", "veth", "virbr")):
            continue
        print(f"    {addr:<16} ({name})")
    ts = tailscale_info()
    if ts:
        print("  From anywhere (phone needs the Tailscale app, same account):")
        if ts["dns"]:
            print(f"    {ts['dns']}")
        print(f"    {ts['ip']}")
    else:
        print("  From other networks: install Tailscale on PC and phone (tailscale.com/download)")
    print("  Open the SebViewer app, pick this PC (or type an address) and connect.")
    print("=" * 56, flush=True)


def main(argv=None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if args.install_service:
        return install_service(args)
    if args.uninstall_service:
        return uninstall_service()
    if args.reset_permissions:
        cfg = load_config()
        cfg.pop("portal_restore_token", None)
        save_config(cfg)
        print("Screen-share permission forgotten.")
        return 0
    if args.new_pin:
        cfg = load_config()
        cfg.pop("pin", None)
        save_config(cfg)
        print(f"New PIN: {get_or_create_pin()}")
        return 0
    if args.show_pin:
        print(args.pin or get_or_create_pin())
        return 0
    pin = None
    if args.pin or args.require_pin or os.environ.get("SEBVIEWER_REQUIRE_PIN"):
        pin = args.pin or get_or_create_pin()
        if not pin.isdigit() or len(pin) < 4:
            print("PIN must be at least 4 digits", file=sys.stderr)
            return 2

    from .backends import create_backend

    backend = create_backend(args.backend)
    # Wayland: start now so the permission dialog appears at launch, not at first connect
    if args.backend != "x11":
        try:
            backend.start()
        except Exception as exc:  # noqa: BLE001
            log.error("Could not start screen capture: %s", exc)
            log.error("Tip: run again and accept the screen-share dialog, or use --backend x11.")
            return 1
    else:
        backend.start()

    settings = StreamSettings(fps=args.fps, quality=args.quality, max_width=args.max_width)
    server = Server(backend, pin, args.bind, args.port, settings)
    adv = None if args.no_discovery else Advertiser(args.port, socket.gethostname())
    print_banner(args.port, pin)
    log.info("Config file: %s", CONFIG_FILE)
    try:
        if adv:
            adv.start()
        asyncio.run(server.serve())
    except KeyboardInterrupt:
        pass
    finally:
        if adv:
            adv.stop()
        backend.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
