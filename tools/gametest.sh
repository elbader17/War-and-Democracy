#!/usr/bin/env bash
# Runs the automated tests inside the game (GameTest).
# Headless: starts the test server, runs the tests and exits.
#
# The run-gametest/ directory is recreated on every run: the GameTestServer
# leaves structures in the world and reusing them makes the tests hang.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
LOG="$MODSERVER_LOG_DIR/gametest.log"
GAMEDIR="$MODSERVER_ROOT/run-gametest"
mkdir -p "$MODSERVER_LOG_DIR"

echo "==> Preparing a clean world for the tests ($GAMEDIR)"
rm -rf "$GAMEDIR"
mkdir -p "$GAMEDIR"
printf 'eula=true\n' > "$GAMEDIR/eula.txt"
cat > "$GAMEDIR/server.properties" <<'PROPS'
online-mode=false
level-name=gametest
spawn-protection=0
max-players=1
view-distance=4
simulation-distance=4
sync-chunk-writes=false
gamemode=creative
PROPS

echo "Running GameTest (log: $LOG)"
set +e
timeout "${MODSERVER_GAMETEST_TIMEOUT:-600}" ./gradlew runGameTestServer --console=plain > "$LOG" 2>&1
CODE=$?
set -e

echo "---- summary ----"
grep -E "GAME TESTS COMPLETE|required tests passed|Game test server crashed|tests are now running" "$LOG" | tail -6 || true

if [ "$CODE" -eq 124 ]; then
  echo "ERROR: the GameTests exceeded the time limit (they hung)." >&2
  exit 1
fi
exit $CODE
