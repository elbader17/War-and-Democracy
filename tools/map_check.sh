#!/usr/bin/env bash
# Visual verification of Phases 2 and 3 of the nations mod:
#   1. starts a server with a clean world
#   2. creates a territory (nation + several chunks) with the bot
#   3. starts the client and captures the OWN MAP screen (Layer A)
#   4. starts the client and captures the in-game view with the JourneyMap minimap
#
#   tools/map_check.sh
#
# Outputs: tools/logs/nation-map.png and tools/logs/nation-minimap.png
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
export DISPLAY="${DISPLAY:-:0}"
export ALSOFT_DRIVERS="${ALSOFT_DRIVERS:-null}"
export ALSOFT_LOGLEVEL="${ALSOFT_LOGLEVEL:-1}"
[ -S /mnt/wslg/runtime-dir/pulse/native ] && export PULSE_SERVER="${PULSE_SERVER:-unix:/mnt/wslg/runtime-dir/pulse/native}"

cleanup() {
  [ -n "${BOT_PID:-}" ] && kill "$BOT_PID" 2>/dev/null || true
  kill_clients
  "$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

# The development server and the client use the same module (fml.modFolders), so
# they must be told apart: each ModDevGradle run configuration uses its own
# arguments file (build/moddev/<run>RunVmArgs.txt).
kill_clients() {
  for p in $(ps -eo pid,args | grep -E "[c]lientMapRunVmArgs|[c]lientAutoRunVmArgs" | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null || true
  done
}

echo "==> [1/5] Starting the server with a clean world"
# The server must be stopped BEFORE deleting the world: if it is still running,
# its world files vanish under its feet and saving fails.
"$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true
sleep 2
rm -rf "$MODSERVER_ROOT/run/devworld"
"$MODSERVER_ROOT/tools/server.sh" start
"$MODSERVER_ROOT/tools/server.sh" wait 240
# The flat world keeps generating after "Done": if the bot joins before the
# server settles, its commands are processed late and the script gets confused.
sleep 10

echo "==> [2/5] Creating the territory with the bot"
"$MODSERVER_ROOT/tools/console.sh" "op AgentBot" >/dev/null 2>&1 || true
node tools/bot/claimterritory.js --nation Testland --duration 150 > "$MODSERVER_LOG_DIR/bot-territory.log" 2>&1 &
BOT_PID=$!
sleep 12

for pos in "0 70 0" "32 70 0" "64 70 0" "96 70 0" "0 70 32" "32 70 32" "64 70 32" "0 70 -32" "-32 70 0" "128 70 0"; do
  "$MODSERVER_ROOT/tools/console.sh" "tp AgentBot $pos" >/dev/null 2>&1 || true
  sleep 2
done
sleep 3

echo "    nations status:"
"$MODSERVER_ROOT/tools/console.sh" "nation list" 2>&1 | sed 's/^/    /' || true

echo "==> [3/5] Capturing the own map (Layer A)"
rm -f "$MODSERVER_ROOT/run/screenshots/modserver.png"
./gradlew runClientMap --console=plain > "$MODSERVER_LOG_DIR/client-map.log" 2>&1 &
CLIENT_PID=$!
for _ in $(seq 1 60); do
  [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ] && break
  sleep 2
done
sleep 3
kill "$CLIENT_PID" 2>/dev/null || true
kill_clients
if [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ]; then
  cp "$MODSERVER_ROOT/run/screenshots/modserver.png" "$MODSERVER_LOG_DIR/nation-map.png"
  echo "    map screenshot: $MODSERVER_LOG_DIR/nation-map.png"
else
  echo "    The map was NOT captured" >&2
fi

echo "==> [4/5] Capturing the in-game view with the minimap (Layer B)"
rm -f "$MODSERVER_ROOT/run/screenshots/modserver.png"
./gradlew runClientMinimap --console=plain > "$MODSERVER_LOG_DIR/client-minimap.log" 2>&1 &
CLIENT_PID=$!
for _ in $(seq 1 60); do
  [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ] && break
  sleep 2
done
sleep 3
kill "$CLIENT_PID" 2>/dev/null || true
kill_clients
if [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ]; then
  cp "$MODSERVER_ROOT/run/screenshots/modserver.png" "$MODSERVER_LOG_DIR/nation-minimap.png"
  echo "    minimap screenshot: $MODSERVER_LOG_DIR/nation-minimap.png"
else
  echo "    The minimap was NOT captured" >&2
fi

echo "==> [5/5] Checks"
echo "    client (map):"
grep -E "AUTOJOIN|MAPDATA|JOURNEYMAP|SCREENSHOT" "$MODSERVER_LOG_DIR/client-map.log" | tail -6 || true
echo "    client (minimap):"
grep -E "AUTOJOIN|MAPDATA|JOURNEYMAP|SCREENSHOT" "$MODSERVER_LOG_DIR/client-minimap.log" | tail -6 || true

echo "Done."
