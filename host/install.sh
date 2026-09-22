#!/usr/bin/env bash
# Installs (or updates) sebviewer-host into ~/.local/share/sebviewer and puts
# `sebviewer-host` on your PATH.  Usage:
#   curl -fsSL https://github.com/SebParoc/sebviewer/releases/latest/download/install-host.sh | bash
# Set SEBVIEWER_VERSION=0.1.0 to pin a version.
set -euo pipefail

REPO="${SEBVIEWER_REPO:-SebParoc/sebviewer}"
DIR="${SEBVIEWER_DIR:-$HOME/.local/share/sebviewer}"
BIN="$HOME/.local/bin"

say() { printf '\033[1;36m%s\033[0m\n' "$*"; }
die() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 is required"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' || die "Python 3.10+ is required"

SESSION="${XDG_SESSION_TYPE:-}"
if [ "$SESSION" != "x11" ]; then
  if ! python3 -c 'import gi; gi.require_version("Gst","1.0"); from gi.repository import Gst' 2>/dev/null \
     || ! command -v gst-inspect-1.0 >/dev/null || ! gst-inspect-1.0 pipewiresrc >/dev/null 2>&1; then
    say "Wayland support needs PyGObject + GStreamer + the PipeWire GStreamer plugin."
    say "  Fedora:        sudo dnf install python3-gobject gstreamer1-plugins-base gstreamer1-plugins-good pipewire-gstreamer"
    say "  Debian/Ubuntu: sudo apt install python3-gi gir1.2-gst-plugins-base-1.0 gir1.2-gst-plugins-bad-1.0 gstreamer1.0-plugins-good gstreamer1.0-pipewire"
    say "  Arch:          sudo pacman -S python-gobject gst-plugins-base gst-plugins-good gst-plugin-pipewire"
    say "Install those, then run this script again (or use X11 with: sebviewer-host --backend x11)."
    [ -t 0 ] && { read -r -p "Continue anyway? [y/N] " a; [ "${a,,}" = y ] || exit 1; }
  fi
fi

if [ -n "${SEBVIEWER_VERSION:-}" ]; then
  API="https://api.github.com/repos/$REPO/releases/tags/v$SEBVIEWER_VERSION"
else
  API="https://api.github.com/repos/$REPO/releases/latest"
fi
say "Looking up release..."
WHL=$(curl -fsSL "$API" | grep -o 'https://[^"]*\.whl' | head -1)
[ -n "$WHL" ] || die "could not find a wheel in $API"

say "Installing into $DIR"
mkdir -p "$DIR" "$BIN"
[ -x "$DIR/venv/bin/python" ] || python3 -m venv --system-site-packages "$DIR/venv"
EXTRA=""
[ "$SESSION" = "x11" ] && EXTRA="[x11]"
"$DIR/venv/bin/pip" install -q --upgrade "sebviewer-host${EXTRA} @ $WHL"
ln -sf "$DIR/venv/bin/sebviewer-host" "$BIN/sebviewer-host"

mkdir -p "$HOME/.local/share/applications"
cat > "$HOME/.local/share/applications/sebviewer-host.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=SebViewer Host
Comment=Control this PC from the SebViewer Android app
Exec=$BIN/sebviewer-host
Terminal=true
Categories=Network;RemoteAccess;
DESKTOP

case ":$PATH:" in *":$BIN:"*) ;; *) say "Note: add $BIN to your PATH";; esac
say "Done. Start it with:  sebviewer-host"
say "Autostart at login:   sebviewer-host --install-service"
