#!/usr/bin/env bash
# Runs the project's Gradle wrapper with the documented toolchain on this machine.
# Usage: g.sh <logname> <gradle args...>
LOG="${LOG_DIR:-$(dirname "$0")/../../build}/$1.log"
shift
export JAVA_HOME="$USERPROFILE/dev/jdk-21"
cd "$(dirname "$0")/../.." || exit 1
./gradlew --console=plain \
  -Pandroid.aapt2FromMavenOverride=C:/Users/SherwinBush/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt2.exe \
  "$@" > "$LOG" 2>&1
CODE=$?
echo "EXIT=$CODE"
grep -E "FAILED|What went wrong|BUILD (SUCCESSFUL|FAILED)|^e: |warning:" -A3 "$LOG" | head -60
exit $CODE
