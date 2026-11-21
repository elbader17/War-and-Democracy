#!/usr/bin/env bash
# Common environment for the modServer project (WSL2 / Linux).
# Usage:  source tools/env.sh
set -o pipefail

export MODSERVER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TOOLCHAIN_DIR="${MODSERVER_TOOLCHAIN_DIR:-$HOME/.mctools}"

export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN_DIR/jdk21}"
export NODE_HOME="${NODE_HOME:-$TOOLCHAIN_DIR/node}"
export PATH="$JAVA_HOME/bin:$NODE_HOME/bin:$PATH"

# Gradle: cache on ext4 (/home) to go fast; the sources stay on /mnt/c.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

export MODSERVER_LOG_DIR="$MODSERVER_ROOT/tools/logs"
# FIFOs do not work on /mnt/c (drvfs), so the server control channel lives in the
# Linux filesystem.
export MODSERVER_RUN_DIR="${MODSERVER_RUN_DIR:-$HOME/.mctools/run}"
export MODSERVER_FIFO="$MODSERVER_RUN_DIR/server.stdin"
export MODSERVER_PID_FILE="$MODSERVER_RUN_DIR/server.pid"
export MODSERVER_HOST="${MODSERVER_HOST:-127.0.0.1}"
export MODSERVER_PORT="${MODSERVER_PORT:-25565}"

mkdir -p "$MODSERVER_LOG_DIR" "$MODSERVER_RUN_DIR"

# Verifies the toolchain and warns if something is missing.
modserver_check_toolchain() {
  local missing=0
  if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "ERROR: no JDK 21 found in $JAVA_HOME (missing $TOOLCHAIN_DIR/jdk21)" >&2
    missing=1
  fi
  if ! command -v node >/dev/null; then
    echo "WARNING: Node is required to run the test bots" >&2
  fi
  return $missing
}
