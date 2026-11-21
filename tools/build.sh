#!/usr/bin/env bash
# Builds the mod and shows the resulting jar.
#   tools/build.sh            -> full build (build)
#   tools/build.sh clean      -> clean
#   tools/build.sh jar        -> jar only
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
modserver_check_toolchain

cd "$MODSERVER_ROOT"
TARGET="${1:-build}"

case "$TARGET" in
  clean) exec ./gradlew clean ;;
  jar)   exec ./gradlew jar ;;
  *)     exec ./gradlew build ;;
esac
