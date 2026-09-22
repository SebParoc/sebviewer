"""Persistent configuration in ~/.config/sebviewer/config.json."""

from __future__ import annotations

import json
import os
import secrets
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "sebviewer"
CONFIG_FILE = CONFIG_DIR / "config.json"


def load_config() -> dict:
    try:
        with CONFIG_FILE.open() as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def save_config(cfg: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix(".tmp")
    with tmp.open("w") as fh:
        json.dump(cfg, fh, indent=2)
    os.chmod(tmp, 0o600)
    tmp.replace(CONFIG_FILE)


def get_or_create_pin() -> str:
    cfg = load_config()
    pin = cfg.get("pin")
    if not pin:
        pin = "".join(secrets.choice("0123456789") for _ in range(6))
        cfg["pin"] = pin
        save_config(cfg)
    return str(pin)
