#!/usr/bin/env python3
"""Measure one bounded end-to-end capacity baseline in the local Kubernetes stack."""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import json
import os
import subprocess
import sys
import time
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
RESILIENCE_RUNNER = ROOT / "infra" / "kubernetes" / "resilience" / "run_acceptance.py"

spec = importlib.util.spec_from_file_location("signalharvester_resilience_support", RESILIENCE_RUNNER)
resilience = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = resilience
spec.loader.exec_module(resilience)

AcceptanceError = resilience.AcceptanceError
ApiSession = resilience.ApiSession
BackendEnvironmentGuard = resilience.BackendEnvironmentGuard
CommandRunner = resilience.CommandRunner
ResourceTracker = resilience.ResourceTracker

BACKEND_DEPLOYMENT = resilience.BACKEND_DEPLOYMENT
ANALYSIS_GROUP = "signalharvester-analysis-v1"
RESULTS_GROUP = "signalharvester-results-v1"
EVENT_OBSERVATION_GROUP = "signalharvester-event-observation-v1"
ANALYSIS_DLQ_TOPIC = resilience.ANALYSIS_DLQ_TOPIC
DEFAULT_REPORT = ROOT / "build" / "reports" / "performance" / "capacity-baseline.json"


@dataclass(frozen=True)
class PipelineSample:
    """Captures one observed pipeline state relative to workload start."""

    elapsed_seconds: float
    analysis_lag: int
    results_lag: int
    event_observation_lag: int
    analysis_items: int
    result_items: int
    pending_outbox: int


@dataclass(frozen=True)
class ConsumerGroupStatus:
    """Summarizes consumer-group coordination state and non-negative lag from rpk JSON."""

    members: int
    lag: int
    state: str | None
    partition_rows: int


class BackendReplicaGuard:
    """Restores the backend replica count after a local capacity measurement."""

    def __init__(self, runner: CommandRunner, rollout_timeout: str):
        self.runner = runner
        self.rollout_timeout = rollout_timeout
        deployment = json.loads(runner.namespaced("get", "deployment", BACKEND_DEPLOYMENT, "-o", "json").stdout)
        self.original = int(deployment["spec"].get("replicas", 1))
        self.changed = False

    def set(self, replicas: int) -> None:
        if replicas < 1:
            raise AcceptanceError("backend replicas must be at least one")
        self.runner.namespaced("scale", f"deployment/{BACKEND_DEPLOYMENT}", f"--replicas={replicas}")
        resilience.rollout_backend(self.runner, self.rollout_timeout)
        resilience.wait_until(
            f"{replicas} available backend replicas",
            120,
            1.0,
            lambda: resilience.backend_replicas(self.runner) == replicas,
        )
        self.changed = self.changed or replicas != self.original

    def restore(self) -> None:
        if not self.changed:
            return
        self.runner.namespaced(
            "scale",
            f"deployment/{BACKEND_DEPLOYMENT}",
            f"--replicas={self.original}",
            check=False,
        )
        try:
            resilience.rollout_backend(self.runner, self.rollout_timeout)
        except AcceptanceError as failure:
            print(f"WARNING: backend replica restoration failed: {failure}", file=sys.stderr)


def _partition_rows(value: Any) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if isinstance(value, dict):
        normalized = {str(key).lower().replace("-", "_"): child for key, child in value.items()}
        if "partition" in normalized and "lag" in normalized:
            rows.append(value)
        for child in value.values():
            rows.extend(_partition_rows(child))
    elif isinstance(value, list):
        for child in value:
            rows.extend(_partition_rows(child))
    return rows


def _consumer_group_record(data: Any, payload: str) -> dict[str, Any]:
    if isinstance(data, dict):
        return data
    if isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict):
        return data[0]
    raise AcceptanceError(f"unexpected rpk group JSON shape: {payload}")


def parse_consumer_group_status(payload: str) -> ConsumerGroupStatus:
    """Parses the small group-status subset needed by the capacity report."""

    data = json.loads(payload)
    group = _consumer_group_record(data, payload)
    rows = _partition_rows(group)

    lag = 0
    member_ids: set[str] = set()
    for row in rows:
        normalized = {str(key).lower().replace("-", "_"): value for key, value in row.items()}
        lag_value = normalized.get("lag")
        if isinstance(lag_value, (int, float)):
            lag += max(0, int(lag_value))
        member_value = normalized.get("member_id", normalized.get("memberid", normalized.get("member")))
        if isinstance(member_value, str) and member_value.strip() and member_value != "-":
            member_ids.add(member_value.strip())

    if not rows:
        aggregate_lag = group.get("total_lag")
        if not isinstance(aggregate_lag, (int, float)):
            raise AcceptanceError(f"rpk group JSON contained neither partition lag nor total_lag: {payload}")
        lag = max(0, int(aggregate_lag))

    declared_members = group.get("members")
    if isinstance(declared_members, int):
        members = declared_members
    elif isinstance(declared_members, list):
        members = len(declared_members)
    else:
        members = len(member_ids)

    state_value = group.get("state")
    state = state_value.strip() if isinstance(state_value, str) and state_value.strip() else None
    return ConsumerGroupStatus(members=members, lag=lag, state=state, partition_rows=len(rows))


def consumer_group_status(runner: CommandRunner, group: str) -> ConsumerGroupStatus:
    result = runner.rpk("group", "describe", group, "--format", "json", check=False)
    if result.returncode != 0 or not result.stdout.strip():
        raise AcceptanceError(f"consumer group {group} is unavailable: {result.stderr.strip()}")
    return parse_consumer_group_status(result.stdout)


def consumer_group_ready_for_baseline(status: ConsumerGroupStatus) -> bool:
    """Returns whether consumer-group coordination is settled enough for a comparable baseline."""

    if status.state is not None:
        return status.state.casefold() == "stable"
    return status.partition_rows > 0


def wait_for_consumer_group_ready(
    runner: CommandRunner,
    group: str,
    timeout: float,
) -> ConsumerGroupStatus:
    status: ConsumerGroupStatus | None = None

    def ready() -> bool:
        nonlocal status
        status = consumer_group_status(runner, group)
        return consumer_group_ready_for_baseline(status)

    try:
        resilience.wait_until(f"{group} consumer group to become stable", timeout, 1.0, ready)
    except AcceptanceError as failure:
        raise AcceptanceError(f"{failure}; last status={status}") from failure
    assert status is not None
    return status


def durable_counts(runner: CommandRunner, profile_id: str) -> tuple[int, int, int]:
    """Reads Analysis, Results, and pending-outbox counts in one PostgreSQL round trip."""

    escaped = profile_id.replace("'", "''")
    value = resilience.psql_scalar(
        runner,
        "SELECT "
        f"(SELECT count(*) FROM analysis.normalized_item_claims WHERE monitoring_profile_id = '{escaped}') || '|' || "
        f"(SELECT count(*) FROM results.analyzed_items WHERE monitoring_profile_id = '{escaped}') || '|' || "
        "(SELECT count(*) FROM analysis.event_outbox WHERE published_at IS NULL);",
    )
    parts = value.split("|")
    if len(parts) != 3:
        raise AcceptanceError(f"unexpected durable-count response: {value!r}")
    return int(parts[0]), int(parts[1]), int(parts[2])


def pending_outbox_count(runner: CommandRunner) -> int:
    return int(resilience.psql_scalar(
        runner,
        "SELECT count(*) FROM analysis.event_outbox WHERE published_at IS NULL;",
    ))


@contextlib.contextmanager
def authenticated_admin_session(runner: CommandRunner, backend_port: int, http_timeout: float):
    """Opens an authenticated backend tunnel after all requested rollouts are complete."""

    with resilience.port_forward(runner, "service/signalharvester-backend", backend_port, 8080):
        base_url = f"http://127.0.0.1:{backend_port}"
        resilience.wait_until(
            "backend readiness through port-forward",
            30,
            0.5,
            lambda: resilience.public_get(base_url + "/health/readiness")[0] == 200,
        )
        admin = ApiSession(base_url, http_timeout)
        admin.login(
            resilience.read_runtime_secret(runner, "SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME"),
            resilience.read_runtime_secret(runner, "SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD"),
        )
        yield admin


def create_profile(admin: ApiSession, tracker: ResourceTracker, source_ids: list[str]) -> str:
    profile = admin.post_json(
        "/api/v1/monitoring-profiles",
        {
            "name": f"Capacity baseline {uuid.uuid4().hex[:8]}",
            "informationCategory": "CAPACITY_BASELINE",
            "enabled": False,
            "collectionIntervalMinutes": 60,
            "sourceIds": source_ids,
            "criteria": {},
            "analysisSettings": {"keywords": [], "minimumMatches": 0},
        },
    )
    profile_id = resilience.require_string(profile, "id")
    tracker.profile_ids.append(profile_id)
    return profile_id


def capture_sample(runner: CommandRunner, profile_id: str, started: float) -> PipelineSample:
    analysis_group = consumer_group_status(runner, ANALYSIS_GROUP)
    results_group = consumer_group_status(runner, RESULTS_GROUP)
    event_observation_group = consumer_group_status(runner, EVENT_OBSERVATION_GROUP)
    analysis_items, result_items, pending_outbox = durable_counts(runner, profile_id)
    return PipelineSample(
        elapsed_seconds=round(time.monotonic() - started, 3),
        analysis_lag=analysis_group.lag,
        results_lag=results_group.lag,
        event_observation_lag=event_observation_group.lag,
        analysis_items=analysis_items,
        result_items=result_items,
        pending_outbox=pending_outbox,
    )


def wait_for_completion(
    runner: CommandRunner,
    profile_id: str,
    expected: int,
    baseline_outbox: int,
    started: float,
    timeout: float,
    sample_interval: float,
) -> list[PipelineSample]:
    """Samples durable/Kafka progress until the bounded workload fully drains."""

    deadline = time.monotonic() + timeout
    samples: list[PipelineSample] = []
    while True:
        sample = capture_sample(runner, profile_id, started)
        samples.append(sample)
        if (
            sample.analysis_lag == 0
            and sample.results_lag == 0
            and sample.event_observation_lag == 0
            and sample.analysis_items == expected
            and sample.result_items == expected
            and sample.pending_outbox == baseline_outbox
        ):
            return samples
        if time.monotonic() >= deadline:
            raise AcceptanceError(
                "capacity baseline did not drain before timeout; "
                f"last sample={asdict(sample)}, expected_items={expected}, baseline_outbox={baseline_outbox}"
            )
        time.sleep(sample_interval)


def first_elapsed(samples: list[PipelineSample], predicate) -> float | None:
    for sample in samples:
        if predicate(sample):
            return sample.elapsed_seconds
    return None


def build_summary(
    expected: int,
    collection_seconds: float,
    samples: list[PipelineSample],
) -> dict[str, float | int | None]:
    final_elapsed = samples[-1].elapsed_seconds
    post_collection_seconds = max(0.0, final_elapsed - collection_seconds)
    return {
        "expectedItems": expected,
        "collectionSeconds": round(collection_seconds, 3),
        "pipelineCompletionSeconds": final_elapsed,
        "postCollectionDrainSeconds": round(post_collection_seconds, 3),
        "analysisCompletionSeconds": first_elapsed(samples, lambda sample: sample.analysis_items == expected),
        "resultsCompletionSeconds": first_elapsed(samples, lambda sample: sample.result_items == expected),
        "analysisLagDrainSeconds": first_elapsed(samples, lambda sample: sample.analysis_lag == 0),
        "resultsLagDrainSeconds": first_elapsed(samples, lambda sample: sample.results_lag == 0),
        "eventObservationLagDrainSeconds": first_elapsed(samples, lambda sample: sample.event_observation_lag == 0),
        "outboxDrainSeconds": first_elapsed(samples, lambda sample: sample.pending_outbox == 0),
        "collectionPublishRateItemsPerSecond": round(expected / collection_seconds, 3) if collection_seconds > 0 else None,
        "endToEndRateItemsPerSecond": round(expected / final_elapsed, 3) if final_elapsed > 0 else None,
    }


def write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--backend-port", type=int, default=18084)
    parser.add_argument("--sources", type=int, default=6)
    parser.add_argument("--items-per-source", type=int, default=200)
    parser.add_argument("--replicas", type=int, default=1)
    parser.add_argument("--sample-interval", type=float, default=1.0)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=240.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--skip-preflight", action="store_true")
    args = parser.parse_args(argv)
    if args.sources < 1 or args.sources > 20:
        parser.error("--sources must be between 1 and 20")
    if args.items_per_source < 1 or args.items_per_source > 500:
        parser.error("--items-per-source must be between 1 and 500")
    if args.replicas < 1:
        parser.error("--replicas must be at least one")
    if args.sample_interval <= 0:
        parser.error("--sample-interval must be positive")
    if args.scenario_timeout <= 0:
        parser.error("--scenario-timeout must be positive")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    output_path = args.output if args.output.is_absolute() else ROOT / args.output
    runner = CommandRunner(args.namespace)
    env_guard: BackendEnvironmentGuard | None = None
    replica_guard: BackendReplicaGuard | None = None
    tracker: ResourceTracker | None = None
    fixture_applied = False
    try:
        resilience.ensure_commands()
        if not args.skip_preflight:
            resilience.run_preflight(runner, args.rollout_timeout)

        print("==> Deploy deterministic capacity fixture")
        cluster_ip = resilience.deploy_fixture(runner, args.rollout_timeout)
        fixture_applied = True
        env_guard = BackendEnvironmentGuard(runner, args.rollout_timeout)
        existing_allowed = env_guard.effective_value("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS") or ""
        fixture_network = resilience.fixture_cidr(cluster_ip)
        allowed = ",".join(value for value in (existing_allowed, fixture_network) if value)
        env_guard.set_many({
            "SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS": allowed,
            "SIGNALHARVESTER_COLLECTION_SCHEDULER_ENABLED": "false",
        })

        replica_guard = BackendReplicaGuard(runner, args.rollout_timeout)
        replica_guard.set(args.replicas)

        print("==> Wait for stable Kafka consumer groups")
        groups_before = {
            ANALYSIS_GROUP: wait_for_consumer_group_ready(runner, ANALYSIS_GROUP, args.scenario_timeout),
            RESULTS_GROUP: wait_for_consumer_group_ready(runner, RESULTS_GROUP, args.scenario_timeout),
            EVENT_OBSERVATION_GROUP: wait_for_consumer_group_ready(
                runner, EVENT_OBSERVATION_GROUP, args.scenario_timeout
            ),
        }
        baseline_outbox = pending_outbox_count(runner)
        lagged_groups = {group: status.lag for group, status in groups_before.items() if status.lag != 0}
        if lagged_groups:
            raise AcceptanceError(
                f"Kafka consumer groups must start with zero lag for a comparable baseline, got {lagged_groups}"
            )
        if baseline_outbox != 0:
            raise AcceptanceError(
                f"Analysis outbox must start empty for a comparable baseline, got {baseline_outbox} pending rows"
            )
        dlq_before = resilience.rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)

        with authenticated_admin_session(runner, args.backend_port, args.http_timeout) as admin:
            tracker = ResourceTracker(admin)
            run_token = uuid.uuid4().hex[:8]
            source_ids = [
                tracker.source(
                    f"/scale/capacity-{run_token}-{index:02d}.xml?items={args.items_per_source}",
                    f"capacity-{index:02d}",
                )
                for index in range(args.sources)
            ]
            profile_id = create_profile(admin, tracker, source_ids)
            expected = args.sources * args.items_per_source

            analysis_before, results_before, pending_before = durable_counts(runner, profile_id)
            if analysis_before != 0 or results_before != 0 or pending_before != baseline_outbox:
                raise AcceptanceError(
                    "fresh capacity profile unexpectedly has durable pipeline state before the run: "
                    f"analysis={analysis_before}, results={results_before}, pending_outbox={pending_before}"
                )

            print(
                f"==> Measure pipeline baseline "
                f"({args.sources} sources x {args.items_per_source} items = {expected}, replicas={args.replicas})"
            )
            started_at = datetime.now(timezone.utc).isoformat()
            started = time.monotonic()
            run = resilience.run_collection(admin, profile_id, timeout=args.scenario_timeout)
            collection_seconds = time.monotonic() - started
            published = int(run.get("publishedCount", 0))
            if published != expected:
                raise AcceptanceError(
                    f"capacity fixture published {published} items, expected {expected}: {run!r}"
                )

            samples = wait_for_completion(
                runner,
                profile_id,
                expected,
                baseline_outbox,
                started,
                args.scenario_timeout,
                args.sample_interval,
            )
            dlq_after = resilience.rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
            if dlq_after != dlq_before:
                raise AcceptanceError(f"Analysis DLQ advanced during healthy capacity baseline: {dlq_before} -> {dlq_after}")

            deployment = json.loads(
                runner.namespaced("get", "deployment", BACKEND_DEPLOYMENT, "-o", "json").stdout
            )
            container = deployment["spec"]["template"]["spec"]["containers"][0]
            report = {
                "schemaVersion": 1,
                "scenario": "pipeline-capacity-baseline",
                "startedAt": started_at,
                "environment": {
                    "namespace": args.namespace,
                    "backendReplicas": args.replicas,
                    "backendImage": container.get("image"),
                    "schedulerDisabledForMeasurement": True,
                    "consumerMembersBefore": {group: status.members for group, status in groups_before.items()},
                    "consumerStatesBefore": {group: status.state for group, status in groups_before.items()},
                },
                "workload": {
                    "sources": args.sources,
                    "itemsPerSource": args.items_per_source,
                    "expectedItems": expected,
                },
                "baseline": {
                    "consumerLag": {group: status.lag for group, status in groups_before.items()},
                    "pendingOutbox": baseline_outbox,
                    "analysisDlqRecords": dlq_before,
                },
                "summary": build_summary(expected, collection_seconds, samples),
                "samples": [asdict(sample) for sample in samples],
            }
            write_report(output_path, report)
            print(f"    report: {output_path}")
            print(json.dumps(report["summary"], indent=2, sort_keys=True))

            tracker.cleanup()
            tracker = None

        print("Capacity baseline measurement completed.")
        return 0
    except (AcceptanceError, subprocess.TimeoutExpired, OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    finally:
        if replica_guard is not None:
            replica_guard.restore()
        if env_guard is not None:
            env_guard.restore()
        if tracker is not None:
            try:
                with authenticated_admin_session(runner, args.backend_port, args.http_timeout) as cleanup_admin:
                    tracker.admin = cleanup_admin
                    tracker.cleanup()
            except Exception as failure:  # noqa: BLE001 - cleanup must not hide the measurement result
                print(f"WARNING: capacity resource cleanup failed: {failure}", file=sys.stderr)
        if fixture_applied:
            runner.kubectl("delete", "-f", str(resilience.FIXTURE_MANIFEST), "--ignore-not-found=true", check=False)


if __name__ == "__main__":
    raise SystemExit(main())
