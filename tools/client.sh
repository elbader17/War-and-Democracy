#!/usr/bin/env bash
# Launches the development client (Minecraft with GUI) using WSLg.
#
#   tools/client.sh                 -> normal client
#   tools/client.sh --auto          -> client that connects on its own to the development server
#
# WSLg notes:
#  - the X11 root is black; to see the game use the client's own screenshot
#    (run/screenshots/modserver.png) instead of tools/screenshot.sh
#  - OpenAL usually fails without an audio device: the "null" driver is used
#    (silent but stable). For real audio, export ALSOFT_DRIVERS=oss or =pulse.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
  echo "WARNING: neither DISPLAY nor WAYLAND_DISPLAY is set. The GUI client needs WSLg (wsl --update)." >&2
fi

# Audio: OpenAL Soft null driver (no physical device required).
export ALSOFT_DRIVERS="${ALSOFT_DRIVERS:-null}"
export ALSOFT_LOGLEVEL="${ALSOFT_LOGLEVEL:-1}"
if [ -S /mnt/wslg/runtime-dir/pulse/native ]; then
  export PULSE_SERVER="${PULSE_SERVER:-unix:/mnt/wslg/runtime-dir/pulse/native}"
fi

cd "$MODSERVER_ROOT"
LOG="$MODSERVER_LOG_DIR/client.log"
mkdir -p "$MODSERVER_LOG_DIR"

TASK="runClient"
if [ "${1:-}" = "--auto" ]; then
  TASK="runClientAuto"
  shift
  echo "Client connecting on its own to $MODSERVER_HOST:$MODSERVER_PORT"
fi

echo "Starting $TASK (log: $LOG)"
exec ./gradlew "$TASK" --console=plain "$@" 2>&1 | tee "$LOG"
