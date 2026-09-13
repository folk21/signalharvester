#!/bin/sh
set -eu

# Repository verification entry point.
#
# Main verification commands/check groups executed by this script:
# - docker info
# - git diff --check (when executed inside a Git worktree)
# - ./gradlew clean check --no-watch-fs
# - ./gradlew integrationTest --no-watch-fs (container-backed module tests + cross-module HTTP smoke)
# - ./archive.sh <temporary FULL archive>
# - FULL archive content/cleanliness validation with unzip/grep
#   Report: build/reports/verification/archive-cleanliness.txt
#
# The final summary lists every routine verification step that actually ran and points to
# test/problem reports and persistent non-Gradle verification reports. Long-running quality
# tooling is intentionally owned by run_rare_checks.sh.

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

SUMMARY_FILE=$(mktemp "${TMPDIR:-/tmp}/signalharvester-check-summary.XXXXXX")
TMP_DIR=""
REPORT_DIR="$ROOT_DIR/build/reports/verification"
ARCHIVE_REPORT="$REPORT_DIR/archive-cleanliness.txt"

print_summary() {
  status=$?

  echo
  echo "==> Verification summary"
  if [ -s "$SUMMARY_FILE" ]; then
    while IFS='|' read -r result description; do
      printf '  [%s] %s\n' "$result" "$description"
    done < "$SUMMARY_FILE"
  else
    echo "  No verification step completed."
  fi

  echo
  echo "Gradle report locations:"
  echo "  - Default tests: <project>/build/reports/tests/test/index.html"
  echo "  - Integration tests: <project>/build/reports/tests/integrationTest/index.html"
  echo "  - Gradle problems: build/reports/problems/problems-report.html"
  echo
  echo "Non-Gradle verification reports:"
  echo "  - FULL archive validation: build/reports/verification/archive-cleanliness.txt"

  if [ "$status" -eq 0 ]; then
    echo
    echo "All requested checks passed."
  else
    echo
    echo "Checks failed with exit code $status."
  fi

  rm -f "$SUMMARY_FILE"
  if [ -n "$TMP_DIR" ]; then
    rm -rf "$TMP_DIR"
  fi
}
trap print_summary EXIT

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

record_result() {
  result=$1
  description=$2
  printf '%s|%s\n' "$result" "$description" >> "$SUMMARY_FILE"
}

run_step() {
  description=$1
  shift

  echo "==> $description"
  if "$@"; then
    record_result "PASS" "$description"
  else
    status=$?
    record_result "FAIL" "$description"
    return "$status"
  fi
}

run_gradle() {
  if [ -x ./gradlew ]; then
    ./gradlew "$@"
  else
    sh ./gradlew "$@"
  fi
}

check_docker() {
  docker info >/dev/null
}

generate_full_archive() {
  ./archive.sh "$ARCHIVE" >/dev/null
}

validate_full_archive() {
  mkdir -p "$REPORT_DIR"
  : > "$ARCHIVE_REPORT"
  unzip -Z1 "$ARCHIVE" > "$ENTRIES"

  {
    echo "SignalHarvester FULL archive validation"
    echo "Archive: $ARCHIVE"
    echo
  } >> "$ARCHIVE_REPORT"

  failed=0

  if ! grep -Fqx 'signalharvester/gradle/wrapper/gradle-wrapper.jar' "$ENTRIES"; then
    echo "[FAIL] Missing Gradle Wrapper JAR: signalharvester/gradle/wrapper/gradle-wrapper.jar" >> "$ARCHIVE_REPORT"
    failed=1
  fi

  FORBIDDEN_ENTRY_PATTERN='(^|/)(\.gradle|\.idea|\.kotlin|\.vscode|\.venv|__pycache__|build|target|out|dist|node_modules)/|(^|/)\.env$|\.class$|\.log$|\.py[co]$'
  FORBIDDEN_ENTRIES=$(grep -E "$FORBIDDEN_ENTRY_PATTERN" "$ENTRIES" || true)
  if [ -n "$FORBIDDEN_ENTRIES" ]; then
    {
      echo "[FAIL] Forbidden generated/local entries:"
      printf '%s\n' "$FORBIDDEN_ENTRIES"
    } >> "$ARCHIVE_REPORT"
    failed=1
  fi

  OTHER_JARS=$(grep -E '\.jar$' "$ENTRIES" | grep -Fvx 'signalharvester/gradle/wrapper/gradle-wrapper.jar' || true)
  if [ -n "$OTHER_JARS" ]; then
    {
      echo "[FAIL] Unexpected JAR files:"
      printf '%s\n' "$OTHER_JARS"
    } >> "$ARCHIVE_REPORT"
    failed=1
  fi

  if [ "$failed" -ne 0 ]; then
    echo "FULL archive validation failed. Details:" >&2
    cat "$ARCHIVE_REPORT" >&2
    echo "Persistent report: $ARCHIVE_REPORT" >&2
    return 1
  fi

  echo "[PASS] Archive content and cleanliness checks passed." >> "$ARCHIVE_REPORT"
}

[ -f gradle/wrapper/gradle-wrapper.jar ] || fail "gradle/wrapper/gradle-wrapper.jar is missing"
command -v docker >/dev/null 2>&1 || fail "docker is required for integration tests"
command -v zip >/dev/null 2>&1 || fail "zip is required for FULL archive validation"
command -v unzip >/dev/null 2>&1 || fail "unzip is required for FULL archive validation"

run_step "docker info" check_docker

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  run_step "git diff --check" git diff --check
fi

run_step "./gradlew clean check --no-watch-fs" run_gradle clean check --no-watch-fs
run_step "./gradlew integrationTest --no-watch-fs" run_gradle integrationTest --no-watch-fs

mkdir -p "$REPORT_DIR"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/signalharvester-checks.XXXXXX")
ARCHIVE="$TMP_DIR/signalharvester-FULL.zip"
ENTRIES="$TMP_DIR/archive-entries.txt"

run_step "./archive.sh <temporary FULL archive>" generate_full_archive
run_step "FULL archive content and cleanliness validation" validate_full_archive
