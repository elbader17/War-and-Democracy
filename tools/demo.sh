#!/usr/bin/env bash
# Mini demo of the nations mod, end to end and visible.
#
#   tools/demo.sh
#
# What it does:
#   1. clean world + dev server
#   2. three bot nations (cyan, red, green) claim their own territory
#      (the commands are run as the bots with RCON, so the result is exact)
#   3. the GUI client joins: it founds its own nation, so the Xaero minimap shows
#      the nation markers and the chat shows the economy of the nation
#   4. Beta attacks a chunk of Alfa: the dispute is opened and the defending
#      nation is warned in the chat
#   5. the GUI client joins again with the own map of the mod open: the territory
#      of every nation with its exact colour
#
# Screenshots (tools/logs/):
#   demo-1-minimap.png   in-game view: Xaero minimap with the nation markers + chat
#   demo-2-nationmap.png the own map of the mod with the territory of every nation
#   demo-summary.txt     what happened, step by step
#
# The GUI client opens a real window under WSLg: you can watch the demo live.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
LOG_DIR="$MODSERVER_LOG_DIR"
DEMO_LOG="$LOG_DIR/demo.log"
SUMMARY="$LOG_DIR/demo-summary.txt"
mkdir -p "$LOG_DIR"

# Colours (decimal RGB): cyan, red and green.
ALFA_COLOR=5636095   # 0x55FFFF
BETA_COLOR=16733525  # 0xFF5555
GAMMA_COLOR=5635925  # 0x55FF55

cleanup() {
  "$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

say() {
  echo "$*" | tee -a "$DEMO_LOG"
}

rcon() {
  "$MODSERVER_ROOT/tools/console.sh" "$*" 2>&1 || true
}

: > "$DEMO_LOG"

say "==> [1/6] Starting the server with a clean world"
rm -rf "$MODSERVER_ROOT/run/devworld"
"$MODSERVER_ROOT/tools/server.sh" start
if ! "$MODSERVER_ROOT/tools/server.sh" wait 240; then
  say "    the server did not start" >&2
  exit 1
fi

say "==> [2/6] Three nations are founded and claim their territory"
for bot in AlfaBot BetaBot GammaBot; do
  node "$MODSERVER_ROOT/tools/bot/run.js" --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" \
    --user "$bot" --scenario "$MODSERVER_ROOT/tools/bot/scenarios/demo-idle.json" \
    > "$LOG_DIR/demo-$bot.log" 2>&1 &
done
sleep 25

# The nations are created and their chunks claimed with the commands run as the
# bots: this way the demo does not depend on the bot movement events.
rcon "op AlfaBot" >/dev/null
rcon "op BetaBot" >/dev/null
rcon "op GammaBot" >/dev/null
rcon "gamemode creative AlfaBot" >/dev/null
rcon "gamemode creative BetaBot" >/dev/null
rcon "gamemode creative GammaBot" >/dev/null
sleep 1

# The capital of each nation is the first chunk of its territory.
rcon "execute as AlfaBot run tp AlfaBot 56 64 56" >/dev/null
rcon "execute as BetaBot run tp BetaBot -56 64 56" >/dev/null
rcon "execute as GammaBot run tp GammaBot 56 64 -56" >/dev/null
sleep 1

rcon "execute as AlfaBot run nacion crear Alfa $ALFA_COLOR" >/dev/null
rcon "execute as BetaBot run nacion crear Beta $BETA_COLOR" >/dev/null
rcon "execute as GammaBot run nacion crear Gamma $GAMMA_COLOR" >/dev/null
sleep 2

# Territory: Alfa to the east, Beta to the west, Gamma to the south.
# The claims are contiguous (a chunk can only be claimed next to the land the
# nation already owns), so they are claimed in order, growing from the capital.
claim_area() {
  local bot="$1"; shift
  for point in "$@"; do
    set -- $point
    rcon "execute as $bot run tp $bot $1 $2 $3" >/dev/null
    sleep 0.7
    rcon "execute as $bot run nacion claim" >/dev/null
    sleep 0.7
  done
}
# Alfa: chunks (3,3) (4,3) (5,3) (3,4) (4,4) (5,4) -> a 3x2 block.
claim_area AlfaBot "56 64 56" "72 64 56" "88 64 56" "56 64 72" "72 64 72" "88 64 72"
# Beta: chunks (-4,3) (-5,3) (-6,3) (-4,4) (-5,4) (-6,4) -> a 3x2 block.
claim_area BetaBot "-56 64 56" "-72 64 56" "-88 64 56" "-56 64 72" "-72 64 72" "-88 64 72"
# Gamma: chunks (3,-4) (4,-4) (5,-4) (3,-5) (4,-5) (5,-5) -> a 3x2 block.
claim_area GammaBot "56 64 -56" "72 64 -56" "88 64 -56" "56 64 -72" "72 64 -72" "88 64 -72"

say "    nations:"
rcon "nacion lista" | sed 's/^/      /' | tee -a "$DEMO_LOG"

say "==> [3/6] The GUI client joins (its window opens under WSLg)"
rm -f "$MODSERVER_ROOT/run/screenshots/modserver.png"
setsid nohup ./gradlew runClientDemo --console=plain > "$LOG_DIR/demo-client-1.log" 2>&1 < /dev/null &

say "    waiting for the client to join..."
for i in $(seq 1 120); do
  grep -q "AUTOJOIN" "$LOG_DIR/demo-client-1.log" 2>/dev/null && break
  sleep 2
done

say "==> [4/6] Beta attacks a chunk of Alfa (the dispute is opened and warned)"
rcon "execute as BetaBot run nacion atacar 3 3" | sed 's/^/      /' | tee -a "$DEMO_LOG"
sleep 3
rcon "nacion disputas" | sed 's/^/      /' | tee -a "$DEMO_LOG"

for i in $(seq 1 120); do
  [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ] && break
  sleep 2
done
if [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ]; then
  cp "$MODSERVER_ROOT/run/screenshots/modserver.png" "$LOG_DIR/demo-1-minimap.png"
  say "    screenshot: tools/logs/demo-1-minimap.png"
fi
sleep 6

say "==> [5/6] The GUI client joins again with the own map open"
rm -f "$MODSERVER_ROOT/run/screenshots/modserver.png"
setsid nohup ./gradlew runClientDemoMap --console=plain > "$LOG_DIR/demo-client-2.log" 2>&1 < /dev/null &
for i in $(seq 1 120); do
  [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ] && break
  sleep 2
done
if [ -f "$MODSERVER_ROOT/run/screenshots/modserver.png" ]; then
  cp "$MODSERVER_ROOT/run/screenshots/modserver.png" "$LOG_DIR/demo-2-nationmap.png"
  say "    screenshot: tools/logs/demo-2-nationmap.png"
fi

say "==> [6/6] Summary"
{
  echo "Nations mod - demo summary"
  echo "=========================="
  echo
  echo "Nations:"
  rcon "nacion lista" | sed 's/^\[nacion\] /  /'
  echo
  echo "Disputes:"
  rcon "nacion disputas" | sed 's/^\[nacion\] /  /'
  echo
  echo "Screenshots:"
  echo "  tools/logs/demo-1-minimap.png   in-game: Xaero minimap with the nation markers + chat"
  echo "  tools/logs/demo-2-nationmap.png the own map of the mod with the territory colours"
} > "$SUMMARY"
cat "$SUMMARY" | tee -a "$DEMO_LOG"

"$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true

echo ""
echo "DEMO DONE. Open tools/logs/demo-1-minimap.png and tools/logs/demo-2-nationmap.png"
