#!/usr/bin/env python3
"""Run dedicated bounded live Health/alert calibration scenarios in local Kubernetes."""

from __future__ import annotations

import argparse
import importlib.util
import math
import os
import sys
import uuid
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[3]
RESILIENCE_RUNNER = ROOT / "infra" / "kubernetes" / "resilience" / "run_acceptance.py"
DEFAULT_EVIDENCE_REPORT = (
    ROOT / "build" / "reports" / "operational-intelligence" / "health-alert-calibration-evidence.json"
)

spec = importlib.util.spec_from_file_location("signalharvester_calibration_resilience_support", RESILIENCE_RUNNER)
resilience = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = resilience
spec.loader.exec_module(resilience)

AcceptanceError = resilience.AcceptanceError
ApiSession = resilience.ApiSession
BackendEnvironmentGuard = resilience.BackendEnvironmentGuard
CommandRunner = resilience.CommandRunner
ResourceTracker = resilience.ResourceTracker
scenario_evidence = resilience.scenario_evidence

TRANSIENT_OUTBOX_ROWS = 300
CRITICAL_OUTBOX_ROWS = 1200
MAX_ITEMS_PER_SOURCE = 200
OUTBOX_HOLD_POLL_INTERVAL = "10m"
OUTBOX_RECOVERY_POLL_INTERVAL = "1s"
OUTBOX_METRICS_INTERVAL = "1s"
TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS = 120.0
OUTBOX_PENDING_QUERY = 'max(signalharvester_analysis_outbox_pending{job="signalharvester-backend"})'
OUTBOX_OLDEST_PENDING_AGE_QUERY = (
    'max(signalharvester_analysis_outbox_oldest_pending_age_seconds{job="signalharvester-backend"})'
)


def partition_workload(total_items: int, max_items_per_source: int = MAX_ITEMS_PER_SOURCE) -> list[int]:
    """Splits one bounded calibration workload into fixture requests accepted by Results polling."""

    if total_items < 1:
        raise ValueError("total_items must be positive")
    if max_items_per_source < 1 or max_items_per_source > 500:
        raise ValueError("max_items_per_source must be between 1 and 500")
    chunks: list[int] = []
    remaining = total_items
    while remaining > 0:
        chunk = min(max_items_per_source, remaining)
        chunks.append(chunk)
        remaining -= chunk
    return chunks


def prometheus_scalar(port: int, query: str) -> float | None:
    """Returns one scalar from a bounded Prometheus instant query, if currently available."""

    rows = resilience.prometheus_query(port, query)
    values: list[float] = []
    for row in rows:
        value = row.get("value") if isinstance(row, dict) else None
        if not isinstance(value, list) or len(value) < 2:
            continue
        try:
            parsed = float(value[1])
        except (TypeError, ValueError):
            continue
        if math.isfinite(parsed):
            values.append(parsed)
    return max(values) if values else None


def wait_for_outbox_signal(
    prometheus_port: int,
    minimum_pending: int,
    timeout: float,
) -> dict[str, float]:
    """Waits until Prometheus exposes the intended backlog magnitude used by Health evaluation."""

    observed: dict[str, float] = {}

    def converged() -> bool:
        pending = prometheus_scalar(prometheus_port, OUTBOX_PENDING_QUERY)
        oldest_age = prometheus_scalar(prometheus_port, OUTBOX_OLDEST_PENDING_AGE_QUERY)
        if pending is None or oldest_age is None:
            return False
        observed["pending"] = pending
        observed["oldestAgeSeconds"] = oldest_age
        return pending >= minimum_pending

    resilience.wait_until(
        f"Prometheus Analysis outbox pending gauge to reach at least {minimum_pending}",
        timeout,
        1.0,
        converged,
    )
    return dict(observed)


def wait_for_outbox_recovery(
    runner: CommandRunner, prometheus_port: int, baseline_pending: int, timeout: float
) -> None:
    """Waits for PostgreSQL and Prometheus backlog evidence to return to the clean baseline."""

    resilience.wait_until(
        "Analysis outbox pending rows to return to calibration baseline",
        timeout,
        1.0,
        lambda: resilience.pending_outbox_count(runner) <= baseline_pending,
    )
    resilience.wait_until(
        "Prometheus Analysis outbox pending gauge to return to calibration baseline",
        timeout,
        1.0,
        lambda: (value := prometheus_scalar(prometheus_port, OUTBOX_PENDING_QUERY)) is not None
        and value <= baseline_pending,
    )


def create_backlog_workload(
    admin: ApiSession,
    tracker: ResourceTracker,
    total_items: int,
    timeout: float,
    label: str,
) -> list[tuple[str, str, int]]:
    """Publishes one deterministic bounded workload and returns profile/source/count identities for drain checks."""

    token = uuid.uuid4().hex[:8]
    workloads: list[tuple[str, str, int]] = []
    for index, item_count in enumerate(partition_workload(total_items)):
        source_id = tracker.source(
            f"/scale/health-alert-{token}-{index:02d}.xml?items={item_count}",
            f"{label}-{index:02d}",
        )
        profile_id = tracker.profile(source_id, f"{label}-{index:02d}")
        run = resilience.run_collection(admin, profile_id, timeout=timeout)
        published = int(run.get("publishedCount", 0))
        if published != item_count:
            raise AcceptanceError(
                f"calibration fixture published {published} items for {label}, expected {item_count}: {run!r}"
            )
        workloads.append((profile_id, source_id, item_count))
    return workloads


def wait_for_workload_results(admin: ApiSession, workloads: Iterable[tuple[str, str, int]], timeout: float) -> None:
    """Waits until every calibration item reaches Results after the dispatcher is restored."""

    for profile_id, source_id, item_count in workloads:
        resilience.wait_results(admin, profile_id, source_id, item_count, timeout)


def prepare_outbox_backlog(
    runner: CommandRunner,
    admin: ApiSession,
    tracker: ResourceTracker,
    prometheus_port: int,
    total_items: int,
    baseline_pending: int,
    timeout: float,
    label: str,
) -> tuple[list[tuple[str, str, int]], dict[str, float], int]:
    """Builds and observes one persistent outbox backlog while dispatcher polling is intentionally delayed."""

    workloads = create_backlog_workload(admin, tracker, total_items, timeout, label)
    target = baseline_pending + total_items
    resilience.wait_until(
        f"Analysis outbox backlog to reach {target} pending rows",
        timeout,
        1.0,
        lambda: resilience.pending_outbox_count(runner) >= target,
    )
    actual_pending = resilience.pending_outbox_count(runner)
    if actual_pending != target:
        raise AcceptanceError(
            f"calibration outbox backlog was contaminated: expected exactly {target} pending rows, got {actual_pending}"
        )
    observed = wait_for_outbox_signal(prometheus_port, target, timeout)
    return workloads, observed, actual_pending


def run_outbox_scenario(
    *,
    runner: CommandRunner,
    env_guard: BackendEnvironmentGuard,
    admin: ApiSession,
    tracker: ResourceTracker,
    evidence_dataset: Any,
    prometheus_port: int,
    scenario_id: str,
    label: str,
    total_items: int,
    baseline_pending: int,
    health_expectation: str,
    alert_expectation: str,
    timeout: float,
    transient: bool,
) -> None:
    """Runs one explicit outbox-backed detector/alert calibration case and restores a clean pipeline baseline."""

    with evidence_dataset.scenario(
        scenario_id,
        label,
        fault_injected=True,
        health_expectation=health_expectation,
        alert_expectation=alert_expectation,
        affected_subsystems=("ANALYSIS_OUTBOX",),
    ) as scenario:
        resilience.record_scenario_start(admin, scenario)
        workloads, observed, actual_pending = prepare_outbox_backlog(
            runner,
            admin,
            tracker,
            prometheus_port,
            total_items,
            baseline_pending,
            timeout,
            scenario_id,
        )
        if transient and observed["oldestAgeSeconds"] >= TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS:
            raise AcceptanceError(
                "transient outbox calibration exceeded its bounded age window before Health capture: "
                f"oldestAgeSeconds={observed['oldestAgeSeconds']:.3f}, "
                f"limit={TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS:.3f}"
            )

        scenario.add_measurement(
            "targetPendingOutboxRows", total_items, phase="FAULT", unit="rows", source="calibration-harness"
        )
        scenario.add_measurement(
            "pendingAnalysisOutboxRows", actual_pending, phase="FAULT", unit="rows", source="postgresql"
        )
        scenario.add_measurement(
            "prometheusPendingAnalysisOutboxRows",
            observed["pending"],
            phase="FAULT",
            unit="rows",
            source="prometheus",
        )
        scenario.add_measurement(
            "prometheusOldestPendingOutboxAgeSeconds",
            observed["oldestAgeSeconds"],
            phase="FAULT",
            unit="seconds",
            source="prometheus",
        )
        resilience.record_scenario_fault_boundary(admin, scenario, "FAULT_START")
        scenario.add_health_snapshot("FAULT", resilience.capture_health_snapshot(admin))
        resilience.record_scenario_fault_boundary(admin, scenario, "FAULT_END")

        env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", OUTBOX_RECOVERY_POLL_INTERVAL)
        wait_for_workload_results(admin, workloads, timeout)
        wait_for_outbox_recovery(runner, prometheus_port, baseline_pending, timeout)
        resilience.record_scenario_end(admin, scenario)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--backend-port", type=int, default=18085)
    parser.add_argument("--prometheus-port", type=int, default=19092)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=240.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--evidence-output", type=Path, default=DEFAULT_EVIDENCE_REPORT)
    parser.add_argument("--skip-preflight", action="store_true")
    args = parser.parse_args(argv)
    if args.scenario_timeout <= 0:
        parser.error("--scenario-timeout must be positive")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    evidence_output = args.evidence_output if args.evidence_output.is_absolute() else ROOT / args.evidence_output
    evidence_dataset = scenario_evidence.ScenarioEvidenceDataset(
        "kubernetes-health-alert-calibration",
        {
            "namespace": args.namespace,
            "explicitHealthSnapshotsPersisted": True,
            "sharedHealthHistoryAcrossScenarios": True,
            "transientOutboxRows": TRANSIENT_OUTBOX_ROWS,
            "criticalOutboxRows": CRITICAL_OUTBOX_ROWS,
            "outboxHoldPollInterval": OUTBOX_HOLD_POLL_INTERVAL,
            "outboxRecoveryPollInterval": OUTBOX_RECOVERY_POLL_INTERVAL,
            "outboxMetricsInterval": OUTBOX_METRICS_INTERVAL,
            "transientMaxOldestPendingAgeSeconds": TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS,
        },
    )
    runner = CommandRunner(args.namespace)
    env_guard: BackendEnvironmentGuard | None = None
    tracker: ResourceTracker | None = None
    fixture_applied = False
    try:
        resilience.ensure_commands()
        if not args.skip_preflight:
            resilience.run_preflight(runner, args.rollout_timeout)

        print("==> Deploy deterministic Health/alert calibration fixture")
        cluster_ip = resilience.deploy_fixture(runner, args.rollout_timeout)
        fixture_applied = True
        env_guard = BackendEnvironmentGuard(runner, args.rollout_timeout)
        existing_allowed = env_guard.effective_value("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS") or ""
        cidr = resilience.fixture_cidr(cluster_ip)
        combined = ",".join(value for value in (existing_allowed, cidr) if value)
        env_guard.set_many({
            "SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS": combined,
            "SIGNALHARVESTER_COLLECTION_SCHEDULER_ENABLED": "false",
            "SIGNALHARVESTER_ANALYSIS_OUTBOX_METRICS_INTERVAL": OUTBOX_METRICS_INTERVAL,
        })

        with (
            resilience.port_forward(runner, "service/signalharvester-backend", args.backend_port, 8080),
            resilience.port_forward(runner, "service/prometheus", args.prometheus_port, 9090),
        ):
            base_url = f"http://127.0.0.1:{args.backend_port}"
            resilience.wait_until(
                "backend readiness through calibration port-forward",
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
                "clean Analysis outbox calibration baseline",
                args.scenario_timeout,
                1.0,
                lambda: resilience.pending_outbox_count(runner) == 0,
            )
            baseline_pending = resilience.pending_outbox_count(runner)
            wait_for_outbox_recovery(runner, args.prometheus_port, baseline_pending, args.scenario_timeout)

            with evidence_dataset.scenario(
                "calibration-normal-control",
                "NORMAL_OPERATION",
                fault_injected=False,
                health_expectation=scenario_evidence.HEALTH_EXPECTATION_REMAIN_HEALTHY,
                alert_expectation=scenario_evidence.ALERT_EXPECTATION_NO_ATTENTION,
            ) as scenario:
                resilience.record_scenario_start(admin, scenario)
                scenario.add_measurement(
                    "pendingAnalysisOutboxRows",
                    baseline_pending,
                    phase="AFTER",
                    unit="rows",
                    source="postgresql",
                )
                resilience.record_scenario_end(admin, scenario)

            env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", OUTBOX_HOLD_POLL_INTERVAL)
            print(f"==> Transient degraded outbox calibration ({TRANSIENT_OUTBOX_ROWS} pending rows)")
            run_outbox_scenario(
                runner=runner,
                env_guard=env_guard,
                admin=admin,
                tracker=tracker,
                evidence_dataset=evidence_dataset,
                prometheus_port=args.prometheus_port,
                scenario_id="calibration-transient-outbox-backlog",
                label="OUTBOX_BACKLOG_TRANSIENT",
                total_items=TRANSIENT_OUTBOX_ROWS,
                baseline_pending=baseline_pending,
                health_expectation=scenario_evidence.HEALTH_EXPECTATION_DETECT,
                alert_expectation=scenario_evidence.ALERT_EXPECTATION_NO_ATTENTION,
                timeout=args.scenario_timeout,
                transient=True,
            )

            env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", OUTBOX_HOLD_POLL_INTERVAL)
            print(f"==> Critical outbox calibration ({CRITICAL_OUTBOX_ROWS} pending rows)")
            run_outbox_scenario(
                runner=runner,
                env_guard=env_guard,
                admin=admin,
                tracker=tracker,
                evidence_dataset=evidence_dataset,
                prometheus_port=args.prometheus_port,
                scenario_id="calibration-critical-outbox-backlog",
                label="OUTBOX_BACKLOG_CRITICAL",
                total_items=CRITICAL_OUTBOX_ROWS,
                baseline_pending=baseline_pending,
                health_expectation=scenario_evidence.HEALTH_EXPECTATION_DETECT,
                alert_expectation=scenario_evidence.ALERT_EXPECTATION_ATTENTION_REQUIRED,
                timeout=args.scenario_timeout,
                transient=False,
            )

        evidence_dataset.write(evidence_output)
        print(f"    operational calibration evidence: {evidence_output}")
        print("All SignalHarvester Health/alert calibration scenarios passed.")
        return 0
    except (
        AcceptanceError,
        OSError,
        ValueError,
        scenario_evidence.ScenarioEvidenceError,
    ) as failure:
        try:
            evidence_dataset.write(evidence_output)
            print(f"    partial operational calibration evidence: {evidence_output}", file=sys.stderr)
        except OSError as write_failure:
            print(f"WARNING: failed to write partial calibration evidence: {write_failure}", file=sys.stderr)
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
