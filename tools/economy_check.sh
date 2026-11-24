#!/usr/bin/env bash
# End-to-end verification of Phase 6 (economy) with two real bots.
#
#   tools/economy_check.sh
#
# Two nations buy troops. Alfa deploys one, closes the day with an empty
# treasury, then checks its income and the defending force.

# Exit code 0 = everything OK.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
FAILURES=0
WORK="$MODSERVER_ROOT/run/economy-check"
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

cat > "$WORK/beta.json" <<JSON
[
  { "command": "/nation create Beta", "expect": ["Nation Beta created"], "timeout": 20000 },
  { "command": "/nation economy", "expect": ["Balance: 100.00 coins"], "timeout": 20000 },
  { "command": "/nation tactics buy 5", "expect": ["Bought 5 reserve armies"], "timeout": 20000 },
  { "command": "/nation economy", "expect": ["Balance: 50.00 coins"], "timeout": 20000 }
]
JSON

cat > "$WORK/alfa-deploy.json" <<JSON
[
  { "command": "/nation tactics buy 1", "expect": ["Bought 1 reserve armies"], "timeout": 20000 },
  { "command": "/nation tactics deploy $CX $CZ 1", "expect": ["Deployed 1 armies"], "timeout": 20000 }
]
JSON

cat > "$WORK/alfa-after-close.json" <<JSON
[
  { "command": "/nation economy", "expect": ["Balance: 12.00 coins"], "timeout": 20000 },
  { "command": "/nation tactics $CX $CZ", "expect": ["2 armies"], "timeout": 20000 },
  { "command": "/nation tactics buy 99", "expect": ["treasury cannot cover"], "timeout": 20000 }
]
JSON

echo "==> [3/6] Bot Beta buys five armies"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/beta.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user BetaBot \
  2>&1 | tee "$WORK/beta.log"

echo "==> [4/6] Alfa buys and deploys a ground troop"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/alfa-deploy.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-deploy.log"

echo "==> [5/6] The admin empties Alfa's treasury and closes the economic day"
BALANCE="$("$MODSERVER_ROOT/tools/console.sh" "nation admin economy balance Alfa 0" || true)"
echo "$BALANCE" | sed 's/^/    | /'
check "Alfa starts the day with an empty treasury" "0.00 coins" "$BALANCE"

TICK="$("$MODSERVER_ROOT/tools/console.sh" "nation admin economy tick" || true)"
echo "$TICK" | sed 's/^/    | /'
check "the economic day is closed" "Economic day closed" "$TICK"
check "Alfa receives the full income of one chunk" "Economic day of Alfa: +12.00 in income" "$TICK"

echo "==> [6/6] Alfa keeps its income and both deployed troops"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/alfa-after-close.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-after-close.log"

if [ "$FAILURES" -ne 0 ]; then
  echo ""
  echo "ECONOMY VERIFICATION FAILED ($FAILURES failure/s)" >&2
  exit 1
fi

echo ""
echo "ECONOMY OK: purchases, deployed troops and daily income verified."
