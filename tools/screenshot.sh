#!/usr/bin/env bash
# Copies the latest screenshot taken by the client itself into the game.
#
#   tools/screenshot.sh [output.png]
#
# Why the operating system screen is not captured: on WSLg the X11 root is
# black (each window is composed separately), so XGetImage on the root returns
# black. The reliable screenshot is taken by the client inside the game:
#   /modserver screenshot  ->  <gameDir>/screenshots/modserver.png
# and with "tools/client.sh --auto" it is taken automatically on world entry.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

SRC="$MODSERVER_ROOT/run/screenshots/modserver.png"
OUT="${1:-$MODSERVER_LOG_DIR/screen.png}"

if [ ! -f "$SRC" ]; then
  echo "There is no client screenshot at $SRC" >&2
  echo "Generate it with: tools/client.sh --auto   (or /modserver screenshot in the game)" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
cp "$SRC" "$OUT"
echo "Screenshot copied to $OUT"
