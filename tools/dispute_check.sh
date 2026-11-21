#!/usr/bin/env bash
# End-to-end verification of Phase 4 (disputes) with two real bots.
#
#   tools/dispute_check.sh
#
# Flow:
#   1. clean world + development server
#   2. bot Alfa  -> creates the nation and claims the chunk it is on
#   3. bot Beta  -> creates the nation and disputes that chunk (and sees it cannot repeat it)
#   4. bot Alfa  -> defends the dispute
#   5. RCON      -> state of the disputes and of the chunks of each nation
#   6. RCON      -> manual resolution: the chunk goes to the challenging nation
#
# The chunk coordinates are read from the chat of the first bot: bots may
# appear at different positions from the spawn, so they cannot be fixed upfront.
#
# Exit code 0 = everything OK.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
FAILURES=0
WORK="$MODSERVER_ROOT/run/dispute-check"
rm -rf "$WORK"
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

echo "==> [1/6] Starting the server with a clean world"
rm -rf "$MODSERVER_ROOT/run/devworld"
"$MODSERVER_ROOT/tools/server.sh" start
if ! "$MODSERVER_ROOT/tools/server.sh" wait 240; then
  echo "    the server did not start" >&2
  exit 1
fi

echo "==> [2/6] Bot Alfa founds the nation and claims the chunk"
node "$MODSERVER_ROOT/tools/bot/run.js" \
  --scenario "$MODSERVER_ROOT/tools/bot/scenarios/disputes-alfa-setup.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-setup.log"

CHUNK="$(grep -oE 'Chunk -?[0-9]+, -?[0-9]+ claimed' "$WORK/alfa-setup.log" | head -1 \
  | grep -oE '\-?[0-9]+, \-?[0-9]+' | tr -d ' ')"
if [ -z "$CHUNK" ]; then
  echo "    could not infer the claimed chunk" >&2
  exit 1
fi
CX="${CHUNK%%,*}"
CZ="${CHUNK##*,}"
echo "    chunk claimed by Alfa: $CX, $CZ"

# Scenarios with the real coordinates of the disputed chunk.
cat > "$WORK/beta.json" <<JSON
[
  { "command": "/nation create Beta", "expect": ["Nation Beta created"], "timeout": 20000 },
  { "command": "/nation dispute $CX $CZ", "expect": ["Dispute #1 opened"], "timeout": 20000 },
  { "command": "/nation dispute $CX $CZ", "expect": ["There is already an open dispute"], "timeout": 20000 },
  { "command": "/nation defend $CX $CZ", "expect": ["That chunk does not belong to your nation"], "timeout": 20000 }
]
JSON

cat > "$WORK/alfa-defend.json" <<JSON
[
  { "command": "/nation info", "expect": ["chunks 1"], "timeout": 20000 },
  { "command": "/nation defend $CX $CZ", "expect": ["defended"], "timeout": 20000 },
  { "command": "/nation info", "expect": ["chunks 1"], "timeout": 20000 }
]
JSON

echo "==> [3/6] Bot Beta founds its nation and disputes the chunk"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/beta.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user BetaBot \
  2>&1 | tee "$WORK/beta.log"

echo "==> [4/6] Bot Alfa defends the dispute"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/alfa-defend.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-defend.log"

echo "==> [5/6] Checking the state via RCON"
DISPUTES="$("$MODSERVER_ROOT/tools/console.sh" "nation disputes" || true)"
echo "$DISPUTES" | sed 's/^/    | /'
check "the dispute is DEFENDED" "DEFENDED" "$DISPUTES"
check "the dispute is #1" "#1" "$DISPUTES"

LIST="$("$MODSERVER_ROOT/tools/console.sh" "nation list" || true)"
echo "$LIST" | sed 's/^/    | /'
check "Alfa keeps the chunk" "Alfa" "$LIST"

echo "==> [6/6] Manual resolution: the chunk goes to the challenging nation"
RESOLVE="$("$MODSERVER_ROOT/tools/console.sh" "nation admin resolve 1 transfer" || true)"
echo "$RESOLVE" | sed 's/^/    | /'
check "the dispute is transferred" "goes to the challenging nation" "$RESOLVE"

DISPUTES2="$("$MODSERVER_ROOT/tools/console.sh" "nation disputes" || true)"
echo "$DISPUTES2" | sed 's/^/    | /'
check "the dispute ends up TRANSFERRED" "TRANSFERRED" "$DISPUTES2"

LIST2="$("$MODSERVER_ROOT/tools/console.sh" "nation list" || true)"
echo "$LIST2" | sed 's/^/    | /'
check "Beta has the chunk now" "Beta" "$LIST2"

"$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true

if [ "$FAILURES" -ne 0 ]; then
  echo ""
  echo "DISPUTE VERIFICATION FAILED ($FAILURES failure/s)" >&2
  exit 1
fi

echo ""
echo "DISPUTES OK: opening, defending and transferring the chunk work inside the game."
