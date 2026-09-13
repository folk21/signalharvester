#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

run_gradle() {
  if [ -x ./gradlew ]; then
    ./gradlew "$@"
  else
    sh ./gradlew "$@"
  fi
}

[ -f gradle/wrapper/gradle-wrapper.jar ] || fail "gradle/wrapper/gradle-wrapper.jar is missing"
command -v docker >/dev/null 2>&1 || fail "docker is required for integration tests"
docker info >/dev/null 2>&1 || fail "Docker daemon is not available"
command -v zip >/dev/null 2>&1 || fail "zip is required for FULL archive validation"
command -v unzip >/dev/null 2>&1 || fail "unzip is required for FULL archive validation"

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --check
fi

echo "==> Running default verification"
run_gradle clean check --no-watch-fs

echo "==> Running container-backed integration tests"
run_gradle integrationTest --no-watch-fs

echo "==> Verifying reproducible FULL archive"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/signalharvester-checks.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
ARCHIVE="$TMP_DIR/signalharvester-FULL.zip"
ENTRIES="$TMP_DIR/archive-entries.txt"

./archive.sh "$ARCHIVE" >/dev/null
unzip -Z1 "$ARCHIVE" > "$ENTRIES"

grep -Fqx 'signalharvester/gradle/wrapper/gradle-wrapper.jar' "$ENTRIES" \
  || fail "FULL archive does not contain the Gradle Wrapper JAR"

FORBIDDEN_ENTRY_PATTERN='(^|/)(\.gradle|\.idea|\.kotlin|\.vscode|\.venv|__pycache__|build|target|out|dist|node_modules)/|(^|/)\.env$|\.class$|\.log$'
if grep -Eq "$FORBIDDEN_ENTRY_PATTERN" "$ENTRIES"; then
  echo "Forbidden generated/local entries found in FULL archive:" >&2
  grep -E "$FORBIDDEN_ENTRY_PATTERN" "$ENTRIES" >&2
  exit 1
fi

OTHER_JARS=$(grep -E '\.jar$' "$ENTRIES" | grep -Fvx 'signalharvester/gradle/wrapper/gradle-wrapper.jar' || true)
if [ -n "$OTHER_JARS" ]; then
  echo "Unexpected JAR files found in FULL archive:" >&2
  printf '%s\n' "$OTHER_JARS" >&2
  exit 1
fi

echo "==> All checks passed"
