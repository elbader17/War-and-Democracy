#!/usr/bin/env bash
# Sends a command to the server console via RCON.
#   tools/console.sh "say hola"
#   tools/console.sh "modserver selftest"
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if [ $# -lt 1 ]; then
  echo "Usage: tools/console.sh \"<command>\"" >&2
  exit 2
fi

exec python3 "$MODSERVER_ROOT/tools/rcon.py" --host "$MODSERVER_HOST" "$@"
