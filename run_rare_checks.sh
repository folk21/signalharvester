#!/bin/sh
set -u

# Rare/expensive repository verification entry point.
#
# Commands/check groups executed by this script, in order:
# - project-metrics regression tests
# - project metrics preview (prints concise size and SpecFactor lines)
# - ./run_checks.sh (routine correctness, integration, and FULL-archive verification)
# - project metrics refresh (recreates reports removed by Gradle clean)
# - ./gradlew jacocoAggregateReport --no-watch-fs
# - ./gradlew spotbugsMain --no-watch-fs
# - ./gradlew buildHealth --no-watch-fs
#
# The early metrics preview runs before potentially failing checks. Because run_checks.sh executes
# Gradle clean, the metrics are generated once more afterward so their report files remain available.
# This script continues after individual failures, reports every result, and exits non-zero at the end
# if at least one requested check failed.

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

SUMMARY_FILE=$(mktemp "${TMPDIR:-/tmp}/signalharvester-rare-check-summary.XXXXXX")
OVERALL_STATUS=0

cleanup() {
  rm -f "$SUMMARY_FILE"
}
trap cleanup EXIT HUP INT TERM

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
    OVERALL_STATUS=$status
  fi
}

run_gradle() {
  if [ -x ./gradlew ]; then
    ./gradlew "$@"
  else
    sh ./gradlew "$@"
  fi
}

refresh_metrics_reports() {
  ./tools/project-metrics/run_signalharvester_metrics.sh >/dev/null
}

print_metrics_summary() {
  metrics_json="build/reports/metrics/project-size.json"
  if [ ! -f "$metrics_json" ]; then
    echo "Metrics: [not generated]"
    return
  fi

  if ! PYTHONDONTWRITEBYTECODE=1 python3 - "$metrics_json" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))

def metric(name):
    value = data[name]
    return value["lines"], value["bytes"]

def fmt(value):
    return f"{value:,}"

code_lines, code_bytes = metric("production_code")
test_lines, test_bytes = metric("tests")
code_tests_lines, code_tests_bytes = metric("code_plus_tests")
project_lines, project_bytes = metric("project_non_test")
project_tests_lines, project_tests_bytes = metric("project_with_tests")
docs_lines, docs_bytes = metric("markdown_docs")
spec_lines, spec_bytes = metric("specification_markdown")
project_docs_lines, project_docs_bytes = metric("project_with_docs")
spec_factor = data.get("spec_factor")

print(
    "Code: "
    f"production SLOC {fmt(code_lines)} lines / {fmt(code_bytes)} bytes; "
    f"test SLOC {fmt(test_lines)} lines / {fmt(test_bytes)} bytes; "
    f"code+tests {fmt(code_tests_lines)} lines / {fmt(code_tests_bytes)} bytes"
)
print(
    "Project: "
    f"markdown {fmt(docs_lines)} lines / {fmt(docs_bytes)} bytes; "
    f"total excluding tests {fmt(project_docs_lines)} lines / {fmt(project_docs_bytes)} bytes"
)
print(
    "Spec: "
    f"all specs {fmt(spec_lines)} lines / {fmt(spec_bytes)} bytes; "
    "SpecFactor "
    + ("n/a" if spec_factor is None else f"{spec_factor:.2f}")
    + " (all spec lines / production SLOC)"
)
PY
  then
    echo "Metrics: [failed to read $metrics_json]"
  fi
}

print_summary() {
  echo
  echo "==> Rare verification summary"
  if [ -s "$SUMMARY_FILE" ]; then
    while IFS='|' read -r result description; do
      printf '  [%s] %s\n' "$result" "$description"
    done < "$SUMMARY_FILE"
  else
    echo "  No rare verification step completed."
  fi

  echo
  echo "==> Project metrics"
  print_metrics_summary

  echo
  echo "Reports are stored under build/reports and subproject build/reports directories."

  if [ "$OVERALL_STATUS" -eq 0 ]; then
    echo
    echo "All requested rare checks passed."
  else
    echo
    echo "One or more rare checks failed. Review the summary and generated reports above."
  fi
}

run_step "project metrics self-tests" env PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/project-metrics/tests -p 'test_*.py'
run_step "project metrics preview" ./tools/project-metrics/run_signalharvester_metrics.sh
run_step "./run_checks.sh" ./run_checks.sh
run_step "persist project metrics reports after clean" refresh_metrics_reports
run_step "./gradlew jacocoAggregateReport --no-watch-fs" run_gradle jacocoAggregateReport --no-watch-fs
run_step "./gradlew spotbugsMain --no-watch-fs" run_gradle spotbugsMain --no-watch-fs
run_step "./gradlew buildHealth --no-watch-fs" run_gradle buildHealth --no-watch-fs

print_summary
exit "$OVERALL_STATUS"
