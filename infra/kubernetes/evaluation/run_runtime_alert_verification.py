#!/usr/bin/env python3
"""Verify the persisted runtime human-attention alert lifecycle in local Kubernetes."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
CALIBRATION_RUNNER = ROOT / "infra" / "kubernetes" / "evaluation" / "run_health_alert_calibration.py"
DEFAULT_REPORT = ROOT / "build" / "reports" / "operational-intelligence" / "runtime-alert-verification.json"

spec = importlib.util.spec_from_file_location("signalharvester_runtime_alert_calibration_support", CALIBRATION_RUNNER)
calibration = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = calibration
spec.loader.exec_module(calibration)

resilience = calibration.resilience
AcceptanceError = calibration.AcceptanceError
ApiSession = calibration.ApiSession
BackendEnvironmentGuard = calibration.BackendEnvironmentGuard
CommandRunner = calibration.CommandRunner
ResourceTracker = calibration.ResourceTracker

HEALTH_POLICY_VERSION = "deterministic-statistical-v1"
ALERT_POLICY_VERSION = "human-attention-v1"
ALERTS_PATH = "/api/v1/admin/operations/health/alerts?limit=200"

ALERT_RUNTIME_ENV = {
    "SIGNALHARVESTER_OPERATIONS_HEALTH_SAMPLING_ENABLED": "false",
    "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_ENABLED": "true",
    "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_VERSION": ALERT_POLICY_VERSION,
    "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_DEGRADED_MIN_CONSECUTIVE_SNAPSHOTS": "3",
    "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_UNHEALTHY_MIN_CONSECUTIVE_SNAPSHOTS": "1",
    "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_HEALTHY_MIN_CONSECUTIVE_SNAPSHOTS_TO_RESOLVE": "2",
    "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_REOPEN_COOLDOWN": "15m",
}


def utc_now() -> str:
    """Returns the current UTC instant in ISO-8601 form."""

    return datetime.now(timezone.utc).isoformat()


def fetch_alerts(admin: ApiSession) -> list[dict[str, Any]]:
    """Reads the bounded persisted human-attention alert history through the public Operations API."""

    status, body = admin.request("GET", ALERTS_PATH)
    if status != 200 or not isinstance(body, list) or not all(isinstance(item, dict) for item in body):
        raise AcceptanceError(f"human-attention alert read returned HTTP {status}: {body!r}")
    return list(body)


def require_snapshot(snapshot: dict[str, Any], expected_status: str, phase: str) -> dict[str, Any]:
    """Validates one explicit Health Snapshot used as alert-decision input."""

    actual_status = snapshot.get("overallStatus")
    if actual_status != expected_status:
        raise AcceptanceError(
            f"{phase} Health Snapshot status was {actual_status!r}, expected {expected_status!r}: {snapshot!r}"
        )
    policy_version = snapshot.get("policyVersion")
    if policy_version != HEALTH_POLICY_VERSION:
        raise AcceptanceError(
            f"{phase} Health Snapshot used policy {policy_version!r}, expected {HEALTH_POLICY_VERSION!r}"
        )
    snapshot_id = snapshot.get("id")
    if not isinstance(snapshot_id, str) or not snapshot_id:
        raise AcceptanceError(f"{phase} Health Snapshot is missing its persisted id: {snapshot!r}")
    return snapshot


def new_alerts(alerts: list[dict[str, Any]], baseline_ids: set[str]) -> list[dict[str, Any]]:
    """Returns only alerts created by the current verification run."""

    created: list[dict[str, Any]] = []
    for alert in alerts:
        alert_id = alert.get("id")
        if not isinstance(alert_id, str) or not alert_id:
            raise AcceptanceError(f"persisted alert is missing its id: {alert!r}")
        if alert_id not in baseline_ids:
            created.append(alert)
    return created


def require_no_new_alerts(admin: ApiSession, baseline_ids: set[str], phase: str) -> list[dict[str, Any]]:
    """Fails when a control or transient phase creates a persisted human-attention alert."""

    alerts = fetch_alerts(admin)
    created = new_alerts(alerts, baseline_ids)
    if created:
        raise AcceptanceError(f"{phase} created unexpected human-attention alerts: {created!r}")
    return alerts


def require_lifecycle_alert(
    admin: ApiSession,
    baseline_ids: set[str],
    *,
    expected_id: str | None,
    expected_state: str,
    expected_snapshot_id: str,
    expected_health_status: str,
    phase: str,
) -> dict[str, Any]:
    """Validates the single persisted critical alert created by this verification run."""

    alerts = fetch_alerts(admin)
    created = new_alerts(alerts, baseline_ids)
    if len(created) != 1:
        raise AcceptanceError(f"{phase} expected exactly one new human-attention alert, got {created!r}")
    alert = created[0]
    if expected_id is not None and alert.get("id") != expected_id:
        raise AcceptanceError(
            f"{phase} changed alert identity from {expected_id!r} to {alert.get('id')!r}: {alert!r}"
        )
    expected = {
        "state": expected_state,
        "severity": "CRITICAL",
        "reason": "UNHEALTHY",
        "policyVersion": ALERT_POLICY_VERSION,
        "latestSnapshotId": expected_snapshot_id,
        "latestHealthStatus": expected_health_status,
    }
    mismatches = {
        key: {"actual": alert.get(key), "expected": value}
        for key, value in expected.items()
        if alert.get(key) != value
    }
    if mismatches:
        raise AcceptanceError(f"{phase} persisted alert did not match runtime policy evidence: {mismatches!r}; {alert!r}")
    if expected_state == "OPEN" and alert.get("resolvedAt") is not None:
        raise AcceptanceError(f"{phase} OPEN alert unexpectedly has resolvedAt: {alert!r}")
    if expected_state == "RESOLVED" and not isinstance(alert.get("resolvedAt"), str):
        raise AcceptanceError(f"{phase} RESOLVED alert is missing resolvedAt: {alert!r}")
    return alert


def add_observation(
    report: dict[str, Any],
    phase: str,
    snapshot: dict[str, Any],
    alerts: list[dict[str, Any]],
    **measurements: int | float | str | bool,
) -> None:
    """Appends one bounded runtime observation to the machine-readable verification report."""

    report["observations"].append({
        "phase": phase,
        "snapshot": snapshot,
        "alerts": alerts,
        "measurements": measurements,
    })


def build_backlog_and_capture(
    *,
    runner: CommandRunner,
    env_guard: BackendEnvironmentGuard,
    admin: ApiSession,
    tracker: ResourceTracker,
    prometheus_port: int,
    total_items: int,
    baseline_pending: int,
    timeout: float,
    label: str,
    expected_status: str,
) -> tuple[dict[str, Any], dict[str, float], int, list[tuple[str, str, int]]]:
    """Creates one real outbox backlog and captures the Health Snapshot that drives runtime alert policy."""

    env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", calibration.OUTBOX_HOLD_POLL_INTERVAL)
    workloads, observed, actual_pending = calibration.prepare_outbox_backlog(
        runner,
        admin,
        tracker,
        prometheus_port,
        total_items,
        baseline_pending,
        timeout,
        label,
    )
    snapshot = require_snapshot(resilience.capture_health_snapshot(admin), expected_status, label)
    return snapshot, observed, actual_pending, workloads


def recover_backlog(
    *,
    runner: CommandRunner,
    env_guard: BackendEnvironmentGuard,
    admin: ApiSession,
    workloads: list[tuple[str, str, int]],
    prometheus_port: int,
    baseline_pending: int,
    timeout: float,
    phase: str,
) -> dict[str, Any]:
    """Restores outbox dispatch, drains the workload, and captures one explicit healthy recovery snapshot."""

    env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", calibration.OUTBOX_RECOVERY_POLL_INTERVAL)
    calibration.wait_for_workload_results(admin, workloads, timeout)
    calibration.wait_for_outbox_recovery(runner, prometheus_port, baseline_pending, timeout)
    return require_snapshot(resilience.capture_health_snapshot(admin), "HEALTHY", phase)


def write_report(path: Path, report: dict[str, Any]) -> None:
    """Writes the bounded runtime alert verification artifact with stable formatting."""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--backend-port", type=int, default=18085)
    parser.add_argument("--prometheus-port", type=int, default=19092)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=240.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--skip-preflight", action="store_true")
    args = parser.parse_args(argv)
    if args.scenario_timeout <= 0:
        parser.error("--scenario-timeout must be positive")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    output = args.output if args.output.is_absolute() else ROOT / args.output
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "artifactType": "signalharvester-runtime-alert-verification",
        "generatedAt": utc_now(),
        "outcome": "RUNNING",
        "failure": None,
        "policy": {
            "healthPolicyVersion": HEALTH_POLICY_VERSION,
            "alertPolicyVersion": ALERT_POLICY_VERSION,
            "alertPolicyEnabled": True,
            "healthSamplingEnabled": False,
            "degradedMinConsecutiveSnapshots": 3,
            "unhealthyMinConsecutiveSnapshots": 1,
            "healthyMinConsecutiveSnapshotsToResolve": 2,
            "reopenCooldown": "15m",
        },
        "workload": {
            "transientOutboxRows": calibration.TRANSIENT_OUTBOX_ROWS,
            "criticalOutboxRows": calibration.CRITICAL_OUTBOX_ROWS,
            "outboxHoldPollInterval": calibration.OUTBOX_HOLD_POLL_INTERVAL,
            "outboxRecoveryPollInterval": calibration.OUTBOX_RECOVERY_POLL_INTERVAL,
        },
        "baseline": {},
        "observations": [],
    }
    runner = CommandRunner(args.namespace)
    env_guard: BackendEnvironmentGuard | None = None
    tracker: ResourceTracker | None = None
    fixture_applied = False
    try:
        resilience.ensure_commands()
        if not args.skip_preflight:
            resilience.run_preflight(runner, args.rollout_timeout)

        print("==> Deploy deterministic runtime alert verification fixture")
        cluster_ip = resilience.deploy_fixture(runner, args.rollout_timeout)
        fixture_applied = True
        env_guard = BackendEnvironmentGuard(runner, args.rollout_timeout)
        existing_allowed = env_guard.effective_value("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS") or ""
        cidr = resilience.fixture_cidr(cluster_ip)
        combined = ",".join(value for value in (existing_allowed, cidr) if value)
        runtime_env = {
            "SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS": combined,
            "SIGNALHARVESTER_COLLECTION_SCHEDULER_ENABLED": "false",
            "SIGNALHARVESTER_ANALYSIS_OUTBOX_METRICS_INTERVAL": calibration.OUTBOX_METRICS_INTERVAL,
            **ALERT_RUNTIME_ENV,
        }
        env_guard.set_many(runtime_env)

        with (
            resilience.port_forward(runner, "service/signalharvester-backend", args.backend_port, 8080),
            resilience.port_forward(runner, "service/prometheus", args.prometheus_port, 9090),
        ):
            base_url = f"http://127.0.0.1:{args.backend_port}"
            resilience.wait_until(
                "backend readiness through runtime alert verification port-forward",
                30,
                0.5,
                lambda: resilience.public_get(base_url + "/health/readiness")[0] == 200,
            )
            bootstrap_username = resilience.read_runtime_secret(
                runner, "SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME"
            )
            bootstrap_password = resilience.read_runtime_secret(
                runner, "SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD"
            )
            admin = ApiSession(base_url, args.http_timeout)
            admin.login(bootstrap_username, bootstrap_password)
            env_guard.set_post_rollout_probe(
                lambda: resilience.wait_for_backend_rollout_api_recovery(admin, args.scenario_timeout)
            )
            tracker = ResourceTracker(admin)

            resilience.wait_until(
                "clean Analysis outbox runtime-alert baseline",
                args.scenario_timeout,
                1.0,
                lambda: resilience.pending_outbox_count(runner) == 0,
            )
            baseline_pending = resilience.pending_outbox_count(runner)
            calibration.wait_for_outbox_recovery(runner, args.prometheus_port, baseline_pending, args.scenario_timeout)

            baseline_alerts = fetch_alerts(admin)
            open_baseline = [alert for alert in baseline_alerts if alert.get("state") == "OPEN"]
            if open_baseline:
                raise AcceptanceError(
                    "runtime alert verification requires no pre-existing OPEN human-attention alert: "
                    f"{open_baseline!r}"
                )
            baseline_ids = {
                alert["id"]
                for alert in baseline_alerts
                if isinstance(alert.get("id"), str) and alert.get("id")
            }
            report["baseline"] = {
                "pendingAnalysisOutboxRows": baseline_pending,
                "existingAlertCount": len(baseline_alerts),
                "existingAlertIds": sorted(baseline_ids),
            }

            baseline_snapshot = require_snapshot(
                resilience.capture_health_snapshot(admin), "HEALTHY", "BASELINE"
            )
            require_no_new_alerts(admin, baseline_ids, "BASELINE")
            add_observation(report, "BASELINE", baseline_snapshot, [])

            print(f"==> Verify transient backlog does not persist attention ({calibration.TRANSIENT_OUTBOX_ROWS} rows)")
            transient_snapshot, transient_signal, transient_pending, transient_workloads = build_backlog_and_capture(
                runner=runner,
                env_guard=env_guard,
                admin=admin,
                tracker=tracker,
                prometheus_port=args.prometheus_port,
                total_items=calibration.TRANSIENT_OUTBOX_ROWS,
                baseline_pending=baseline_pending,
                timeout=args.scenario_timeout,
                label="RUNTIME_ALERT_TRANSIENT",
                expected_status="DEGRADED",
            )
            if transient_signal["oldestAgeSeconds"] >= calibration.TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS:
                raise AcceptanceError(
                    "transient runtime-alert backlog exceeded its bounded age window before Health capture: "
                    f"oldestAgeSeconds={transient_signal['oldestAgeSeconds']:.3f}, "
                    f"limit={calibration.TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS:.3f}"
                )
            require_no_new_alerts(admin, baseline_ids, "TRANSIENT_FAULT")
            add_observation(
                report,
                "TRANSIENT_FAULT",
                transient_snapshot,
                [],
                pendingAnalysisOutboxRows=transient_pending,
                prometheusPendingAnalysisOutboxRows=transient_signal["pending"],
                prometheusOldestPendingOutboxAgeSeconds=transient_signal["oldestAgeSeconds"],
            )
            transient_recovery = recover_backlog(
                runner=runner,
                env_guard=env_guard,
                admin=admin,
                workloads=transient_workloads,
                prometheus_port=args.prometheus_port,
                baseline_pending=baseline_pending,
                timeout=args.scenario_timeout,
                phase="TRANSIENT_RECOVERY",
            )
            require_no_new_alerts(admin, baseline_ids, "TRANSIENT_RECOVERY")
            add_observation(report, "TRANSIENT_RECOVERY", transient_recovery, [])

            print(f"==> Verify critical backlog opens persisted attention ({calibration.CRITICAL_OUTBOX_ROWS} rows)")
            critical_snapshot, critical_signal, critical_pending, critical_workloads = build_backlog_and_capture(
                runner=runner,
                env_guard=env_guard,
                admin=admin,
                tracker=tracker,
                prometheus_port=args.prometheus_port,
                total_items=calibration.CRITICAL_OUTBOX_ROWS,
                baseline_pending=baseline_pending,
                timeout=args.scenario_timeout,
                label="RUNTIME_ALERT_CRITICAL",
                expected_status="UNHEALTHY",
            )
            opened = require_lifecycle_alert(
                admin,
                baseline_ids,
                expected_id=None,
                expected_state="OPEN",
                expected_snapshot_id=critical_snapshot["id"],
                expected_health_status="UNHEALTHY",
                phase="CRITICAL_FAULT",
            )
            alert_id = opened["id"]
            add_observation(
                report,
                "CRITICAL_FAULT",
                critical_snapshot,
                [opened],
                pendingAnalysisOutboxRows=critical_pending,
                prometheusPendingAnalysisOutboxRows=critical_signal["pending"],
                prometheusOldestPendingOutboxAgeSeconds=critical_signal["oldestAgeSeconds"],
            )

            first_recovery = recover_backlog(
                runner=runner,
                env_guard=env_guard,
                admin=admin,
                workloads=critical_workloads,
                prometheus_port=args.prometheus_port,
                baseline_pending=baseline_pending,
                timeout=args.scenario_timeout,
                phase="CRITICAL_RECOVERY_1",
            )
            still_open = require_lifecycle_alert(
                admin,
                baseline_ids,
                expected_id=alert_id,
                expected_state="OPEN",
                expected_snapshot_id=first_recovery["id"],
                expected_health_status="HEALTHY",
                phase="CRITICAL_RECOVERY_1",
            )
            add_observation(report, "CRITICAL_RECOVERY_1", first_recovery, [still_open])

            second_recovery = require_snapshot(
                resilience.capture_health_snapshot(admin), "HEALTHY", "CRITICAL_RECOVERY_2"
            )
            resolved = require_lifecycle_alert(
                admin,
                baseline_ids,
                expected_id=alert_id,
                expected_state="RESOLVED",
                expected_snapshot_id=second_recovery["id"],
                expected_health_status="HEALTHY",
                phase="CRITICAL_RECOVERY_2",
            )
            add_observation(report, "CRITICAL_RECOVERY_2", second_recovery, [resolved])

            report["verifiedAlertId"] = alert_id
            report["outcome"] = "PASSED"
            report["completedAt"] = utc_now()

        write_report(output, report)
        print(f"    runtime alert verification evidence: {output}")
        print("Runtime persisted human-attention alert lifecycle passed.")
        return 0
    except (AcceptanceError, OSError, ValueError) as failure:
        report["outcome"] = "FAILED"
        report["failure"] = {
            "type": failure.__class__.__name__,
            "message": str(failure).strip() or failure.__class__.__name__,
        }
        report["completedAt"] = utc_now()
        try:
            write_report(output, report)
            print(f"    partial runtime alert verification evidence: {output}", file=sys.stderr)
        except OSError as write_failure:
            print(f"WARNING: failed to write partial runtime alert evidence: {write_failure}", file=sys.stderr)
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    finally:
        if tracker is not None:
            tracker.cleanup()
        if env_guard is not None:
            env_guard.restore()
        if fixture_applied:
            runner.kubectl("delete", "-f", str(resilience.FIXTURE_MANIFEST), "--ignore-not-found=true", check=False)


if __name__ == "__main__":
    raise SystemExit(main())
