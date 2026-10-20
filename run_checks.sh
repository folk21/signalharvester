
#!/bin/sh
set -eu

# Repository verification entry point.
#
# Main verification commands/check groups executed by this script:
# - docker info
# - git diff --check (when executed inside a Git worktree)
# - ./tools/agent-pipeline/run_tests.sh
# - ./tools/source-import/run_tests.sh
# - ./tools/live-backend/run_tests.sh
# - operational-evaluation Python compile/focused unit checks
# - ./infra/kubernetes/run_tests.sh
# - ./gradlew clean check --no-watch-fs
# - ./gradlew integrationTest --no-watch-fs --no-parallel (container-backed module tests + cross-module HTTP smoke)
#
# The final summary lists every routine verification step that actually ran and points to
# test/problem reports and persistent non-Gradle verification reports. Long-running quality
# tooling is intentionally owned by run_rare_checks.sh.

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

SUMMARY_FILE=$(mktemp "${TMPDIR:-/tmp}/signalharvester-check-summary.XXXXXX")

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

  if [ "$status" -eq 0 ]; then
    echo
    echo "All requested checks passed."
  else
    echo
    echo "Checks failed with exit code $status."
  fi

  rm -f "$SUMMARY_FILE"
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

run_operational_evaluation_compile() {
  cache_dir=$(mktemp -d "${TMPDIR:-/tmp}/signalharvester-pycache.XXXXXX")
  status=0
  PYTHONPYCACHEPREFIX="$cache_dir" python3 -m py_compile \
    infra/kubernetes/evaluation/scenario_evidence.py \
    infra/kubernetes/evaluation/offline_evaluator.py \
    infra/kubernetes/evaluation/assisted_investigation_evaluator.py \
    infra/kubernetes/evaluation/calibration_report.py \
    infra/kubernetes/evaluation/run_health_alert_calibration.py \
    infra/kubernetes/evaluation/run_runtime_alert_verification.py \
    infra/kubernetes/evaluation/run_calibration_campaign.py \
    infra/kubernetes/tests/test_assisted_investigation_evaluator.py \
    infra/kubernetes/tests/test_calibration_report.py \
    infra/kubernetes/tests/test_health_alert_calibration.py \
    infra/kubernetes/tests/test_runtime_alert_verification.py \
    infra/kubernetes/tests/test_calibration_campaign.py || status=$?
  rm -rf "$cache_dir"
  return "$status"
}

run_operational_evaluation_unit_tests() {
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v \
    infra.kubernetes.tests.test_assisted_investigation_evaluator \
    infra.kubernetes.tests.test_calibration_report \
    infra.kubernetes.tests.test_health_alert_calibration \
    infra.kubernetes.tests.test_runtime_alert_verification \
    infra.kubernetes.tests.test_calibration_campaign
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

[ -f gradle/wrapper/gradle-wrapper.jar ] || fail "gradle/wrapper/gradle-wrapper.jar is missing"
command -v docker >/dev/null 2>&1 || fail "docker is required for integration tests"
command -v python3 >/dev/null 2>&1 || fail "python3 is required for operational evaluation checks"

run_step "docker info" check_docker

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  run_step "git diff --check" git diff --check
fi

run_step "spec processing-state validation" python3 tools/specs/validate_state.py
run_step "spec processing-state unit tests" python3 -B -m unittest discover -s tools/specs/tests -p "test_*.py"
run_step "./tools/agent-pipeline/run_tests.sh" ./tools/agent-pipeline/run_tests.sh
run_step "./tools/source-import/run_tests.sh" ./tools/source-import/run_tests.sh
run_step "./tools/live-backend/run_tests.sh" ./tools/live-backend/run_tests.sh
run_step "operational evaluation Python compilation" run_operational_evaluation_compile
run_step "focused operational evaluation unit tests" run_operational_evaluation_unit_tests
run_step "./infra/kubernetes/run_tests.sh" ./infra/kubernetes/run_tests.sh
run_step "./gradlew clean check --no-watch-fs" run_gradle clean check --no-watch-fs
run_step "./gradlew integrationTest --no-watch-fs --no-parallel" run_gradle integrationTest --no-watch-fs --no-parallel
