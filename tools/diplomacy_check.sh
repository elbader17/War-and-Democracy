#!/usr/bin/env bash
# End-to-end verification of Phase 5 (diplomacy) with two real bots.
#
#   tools/diplomacy_check.sh
#
# Flow:
#   1. clean world + development server
#   2. bot Alfa  -> founds the nation and claims the chunk
#   3. bot Beta  -> founds the nation, signs a pact and cannot dispute
#   4. bot Beta  -> declares war and attacks the chunk (dispute with a reduced window)
#   5. RCON      -> state of the relations, the disputes and the chunks
#
# Exit code 0 = everything OK.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
FAILURES=0
WORK="$MODSERVER_ROOT/run/diplomacy-check"
mkdir -p "$WORK"

cleanup() {
  "$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

check() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == *"$expected"* ]]; then
    echo "    OK  $label"
  else
    echo "    FAIL $label: expected '$expected' but got '$actual'" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

echo "==> [1/5] Starting the server with a clean world"
rm -rf "$MODSERVER_ROOT/run/devworld"
"$MODSERVER_ROOT/tools/server.sh" start
if ! "$MODSERVER_ROOT/tools/server.sh" wait 240; then
  echo "    the server did not start" >&2
  exit 1
fi

echo "==> [2/5] Bot Alfa founds the nation and claims the chunk"
node "$MODSERVER_ROOT/tools/bot/run.js" \
  --scenario "$MODSERVER_ROOT/tools/bot/scenarios/disputes-alfa-setup.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa.log"

CHUNK="$(grep -oE 'Chunk -?[0-9]+, -?[0-9]+ claimed' "$WORK/alfa.log" | head -1 \
  | grep -oE '\-?[0-9]+, \-?[0-9]+' | tr -d ' ')"
if [ -z "$CHUNK" ]; then
  echo "    could not infer the claimed chunk" >&2
  exit 1
fi
CX="${CHUNK%%,*}"
CZ="${CHUNK##*,}"
echo "    chunk claimed by Alfa: $CX, $CZ"
BX=$((CX+1))
"$MODSERVER_ROOT/tools/console.sh" "gamerule spawnRadius 0" >/dev/null
"$MODSERVER_ROOT/tools/console.sh" "execute positioned $((BX*16+8)) 0 $((CZ*16+8)) positioned over world_surface run setworldspawn ~ ~ ~" >/dev/null

cat > "$WORK/pacto.json" <<JSON
[
  { "command": "/nation create Beta", "expect": ["Nation Beta created"], "timeout": 20000 },
  { "command": "/nation claim", "expect": ["claimed by Beta"], "timeout": 20000 },
  { "command": "/nation tactics buy 3", "expect": ["Bought 3 reserve armies"], "timeout": 20000 },
  { "command": "/nation tactics deploy $BX $CZ 3", "expect": ["Deployed 3 armies"], "timeout": 20000 },
  { "command": "/nation pact Alfa", "expect": ["Proposal sent"], "timeout": 20000 }
]
JSON

cat > "$WORK/ratify.json" <<JSON
[
  { "command": "/nation pact Beta", "expect": ["Treaty ratified"], "timeout": 20000 }
]
JSON

cat > "$WORK/check-pact.json" <<JSON
[
  { "command": "/nation tactics roll $BX $CZ $CX $CZ 1", "expect": ["treaty protects"], "timeout": 20000 },
  { "command": "/nation info", "expect": ["Alfa with a non-aggression pact"], "timeout": 20000 }
]
JSON

cat > "$WORK/guerra.json" <<JSON
[
  { "command": "/nation war Alfa", "expect": ["War agreement signed between Beta and Alfa"], "timeout": 20000 },
  { "command": "/nation info", "expect": ["Alfa at war"], "timeout": 20000 },
  { "command": "/nation tactics roll $BX $CZ $CX $CZ 1", "expect": ["Attack launched:"], "timeout": 20000 },
  { "command": "/nation info", "expect": ["Alfa at war"], "timeout": 20000 }
]
JSON

echo "==> [3/5] Bot Beta signs a pact: the disputes are blocked"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/pacto.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user BetaBot \
  2>&1 | tee "$WORK/pacto.log"

node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/ratify.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/check-pact.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user BetaBot

echo "==> [4/5] Bot Beta declares war and attacks"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/guerra.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user BetaBot \
  2>&1 | tee "$WORK/guerra.log"

echo "==> [5/5] Checking the state via RCON"
LIST="$("$MODSERVER_ROOT/tools/console.sh" "nation list" || true)"
echo "$LIST" | sed 's/^/    | /'
check "Alfa appears at war with Beta" "at war with Beta" "$LIST"

DISPUTES="$("$MODSERVER_ROOT/tools/console.sh" "nation disputes" || true)"
echo "$DISPUTES" | sed 's/^/    | /'
check "the dispute is #1" "#1" "$DISPUTES"

if [ "$FAILURES" -ne 0 ]; then
  echo ""
  echo "DIPLOMACY VERIFICATION FAILED ($FAILURES failure/s)" >&2
  exit 1
fi

echo ""
echo "DIPLOMACY OK: the pact blocks the disputes, hostile dice rolls resume after war is declared."
