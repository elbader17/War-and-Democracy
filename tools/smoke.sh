#!/usr/bin/env bash
# Full autonomous loop: build -> GameTest -> start server -> the bot joins the
# game -> runs commands -> verifies -> shuts the server down.
#
#   tools/smoke.sh                 -> runs everything
#   tools/smoke.sh --no-build      -> reuses the already built jar
#   tools/smoke.sh --no-gametest   -> skips the GameTests
#
# Exit code 0 = everything OK.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

BUILD=1
GAMETEST=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-gametest) GAMETEST=0 ;;
  esac
done

cd "$MODSERVER_ROOT"
SCENARIO="${MODSERVER_SCENARIO:-tools/bot/scenarios/smoke.json}"
FAILURES=0

cleanup() {
  "$MODSERVER_ROOT/tools/server.sh" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "$BUILD" = "1" ]; then
  echo "==> [1/5] Building the mod"
  ./gradlew build --console=plain -q
  echo "    build OK: $(ls -1 build/libs/*.jar | head -1)"
else
  echo "==> [1/5] Skipping the build (--no-build)"
fi

if [ "$GAMETEST" = "1" ]; then
  echo "==> [2/5] GameTest inside the game"
  if "$MODSERVER_ROOT/tools/gametest.sh"; then
    echo "    gametest OK"
  else
    echo "    gametest FAILED (see $MODSERVER_LOG_DIR/gametest.log)" >&2
    FAILURES=$((FAILURES + 1))
  fi
else
  echo "==> [2/5] Skipping GameTest (--no-gametest)"
fi

echo "==> [3/5] Starting the development server"
# Clean world: the nations scenario starts from zero (nobody has a nation).
rm -rf "$MODSERVER_ROOT/run/devworld"
"$MODSERVER_ROOT/tools/server.sh" start
if ! "$MODSERVER_ROOT/tools/server.sh" wait 240; then
  echo "    the server did not start" >&2
  exit 1
fi

echo "==> [4/5] Giving op permissions to the bot and running the scenarios"
"$MODSERVER_ROOT/tools/console.sh" "op AgentBot" >/dev/null 2>&1 || true
set +e
node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$MODSERVER_ROOT/$SCENARIO" \
     --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AgentBot
BOT_CODE=$?
if [ "$BOT_CODE" -eq 0 ] && [ "$SCENARIO" = "tools/bot/scenarios/smoke.json" ]; then
  echo "--- nations scenario ---"
  node "$MODSERVER_ROOT/tools/bot/run.js" --scenario "$MODSERVER_ROOT/tools/bot/scenarios/nations.json" \
       --host "$MODSERVER_HOST" --port "$MODSERVER_PORT" --user AgentBot
  BOT_CODE=$?
fi
set -e
[ "$BOT_CODE" -ne 0 ] && FAILURES=$((FAILURES + 1))

echo "==> [5/5] Verifying the server log"
SELFTEST_LINES="$(grep -c 'MODSERVER_SELFTEST' "$MODSERVER_LOG_DIR/server.log" 2>/dev/null || echo 0)"
echo "    MODSERVER_SELFTEST lines in the log: $SELFTEST_LINES"
grep 'MODSERVER_SELFTEST RESULT' "$MODSERVER_LOG_DIR/server.log" | tail -3 || true

"$MODSERVER_ROOT/tools/server.sh" stop || true

if [ "$FAILURES" -ne 0 ]; then
  echo ""
  echo "SMOKE TEST FAILED ($FAILURES failure/s). Last server lines:" >&2
  tail -40 "$MODSERVER_LOG_DIR/server.log" >&2 || true
  exit 1
fi

echo ""
echo "SMOKE TEST OK: the mod builds, passes the GameTests and answers inside the game."
