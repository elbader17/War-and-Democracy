#!/usr/bin/env bash
# End-to-end verification of Phase 6 (economy) with two real bots.
#
#   tools/economy_check.sh
#
# Flow:
#   1. clean world + development server
#   2. bot Alfa  -> founds the nation, claims a chunk and sees its economy
#   3. bot Beta  -> founds the nation, disputes the chunk (pays the attack deposit)
#   4. RCON      -> Beta's balance went down (the attack costs coins)
#   5. RCON      -> the admin empties Alfa's treasury and closes the economic day
#   6. bot Alfa  -> can no longer defend: the nation is UNPROTECTED
#   7. RCON      -> Alfa has balance again and pays for the defence
#
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
  { "command": "/nation economy", "expect": ["Balance: 100.00 coins", "PROTECTED"], "timeout": 20000 },
  { "command": "/nation dispute $CX $CZ", "expect": ["Dispute #1 opened"], "timeout": 20000 },
  { "command": "/nation economy", "expect": ["Balance: 50.00 coins"], "timeout": 20000 }
]
JSON

cat > "$WORK/alfa-defend.json" <<JSON
[
  { "command": "/nation economy", "expect": ["PROTECTED"], "timeout": 20000 },
  { "command": "/nation defend $CX $CZ", "expect": ["defended"], "timeout": 20000 }
]
JSON

cat > "$WORK/alfa-unprotected.json" <<JSON
[
  { "command": "/nation defend $CX $CZ", "expect": ["UNPROTECTED"], "timeout": 20000 },
  { "command": "/nation economy", "expect": ["UNPROTECTED"], "timeout": 20000 }
]
JSON

cat > "$WORK/alfa-paid.json" <<JSON
[
  { "command": "/nation defense pay", "expect": ["You have paid the defense"], "timeout": 20000 },
  { "command": "/nation economy", "expect": ["PROTECTED"], "timeout": 20000 }
]
JSON

echo "==> [3/6] Bot Beta founds its nation and attacks (pays the deposit)"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/beta.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user BetaBot \
  2>&1 | tee "$WORK/beta.log"

echo "==> [4/6] Alfa defends (the defence is already paid)"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/alfa-defend.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-defend.log"

echo "==> [5/6] The admin leaves Alfa without treasury and closes the economic day"
BALANCE="$("$MODSERVER_ROOT/tools/console.sh" "nation admin economy balance Alfa 0" || true)"
echo "$BALANCE" | sed 's/^/    | /'
check "the admin empties Alfa's treasury" "0.00 coins" "$BALANCE"

TICK="$("$MODSERVER_ROOT/tools/console.sh" "nation admin economy tick" || true)"
echo "$TICK" | sed 's/^/    | /'
check "the economic day is closed" "Economic day closed" "$TICK"
check "Alfa ends up unprotected" "UNPROTECTED" "$TICK"

echo "==> [6/6] Alfa can no longer defend until it pays"
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/alfa-unprotected.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-unprotected.log"

"$MODSERVER_ROOT/tools/console.sh" "nation admin economy balance Alfa 500" >/dev/null 2>&1 || true
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$WORK/alfa-paid.json" \
  --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AlfaBot \
  2>&1 | tee "$WORK/alfa-paid.log"

"$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true

if [ "$FAILURES" -ne 0 ]; then
  echo ""
  echo "ECONOMY VERIFICATION FAILED ($FAILURES failure/s)" >&2
  exit 1
fi

echo ""
echo "ECONOMY OK: the currency is earned, it is spent on attacking and an unpaid defence leaves the nation unprotected."
