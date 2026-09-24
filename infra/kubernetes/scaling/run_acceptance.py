#!/usr/bin/env python3
"""Demonstrate partition-bounded Kafka consumer scaling in the local Kubernetes stack."""

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
from dataclasses import dataclass
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
CommandRunner = resilience.CommandRunner
ApiSession = resilience.ApiSession
BackendEnvironmentGuard = resilience.BackendEnvironmentGuard
ResourceTracker = resilience.ResourceTracker

BACKEND_DEPLOYMENT = resilience.BACKEND_DEPLOYMENT
ANALYSIS_GROUP = "signalharvester-analysis-v1"
RESULTS_GROUP = "signalharvester-results-v1"
EVENT_OBSERVATION_GROUP = "signalharvester-event-observation-v1"
ANALYSIS_DLQ_TOPIC = resilience.ANALYSIS_DLQ_TOPIC
RAW_TOPIC = resilience.RAW_TOPIC


@dataclass(frozen=True)
class ConsumerGroupSnapshot:
    """Summarizes consumer-group lag and active partition ownership from rpk JSON."""

    member_count: int
    member_ids: frozenset[str]
    client_ids: frozenset[str]
    assignments: dict[str, frozenset[tuple[str, int]]]
    lag: int


class BackendReplicaGuard:
    """Restores the backend Deployment replica count after a scaling acceptance run."""

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
        lowered = {str(key).lower().replace("-", "_"): child for key, child in value.items()}
        if "partition" in lowered and "lag" in lowered:
            rows.append(value)
        for child in value.values():
            rows.extend(_partition_rows(child))
    elif isinstance(value, list):
        for child in value:
            rows.extend(_partition_rows(child))
    return rows


def _lookup(row: dict[str, Any], *names: str) -> Any:
    normalized = {str(key).lower().replace("-", "_"): value for key, value in row.items()}
    for name in names:
        if name in normalized:
            return normalized[name]
    return None


def parse_consumer_group_snapshot(payload: str) -> ConsumerGroupSnapshot:
    """Parses rpk group JSON without depending on field ordering or a single rpk minor version."""

    data = json.loads(payload)
    rows = _partition_rows(data)
    if not rows:
        raise AcceptanceError(f"rpk group JSON contained no partition lag rows: {payload}")

    member_ids: set[str] = set()
    client_ids: set[str] = set()
    assignments: dict[str, set[tuple[str, int]]] = {}
    lag = 0
    for row in rows:
        lag_value = _lookup(row, "lag")
        partition_value = _lookup(row, "partition")
        topic_value = _lookup(row, "topic")
        member_value = _lookup(row, "member_id", "memberid", "member")
        client_value = _lookup(row, "client_id", "clientid", "client")
        if isinstance(lag_value, (int, float)):
            lag += max(0, int(lag_value))
        if not isinstance(partition_value, int):
            continue
        if not isinstance(topic_value, str):
            topic_value = ""

        member_id = None
        if isinstance(member_value, str) and member_value.strip() and member_value != "-":
            member_id = member_value.strip()
            member_ids.add(member_id)
        if isinstance(client_value, str) and client_value.strip() and client_value != "-":
            client_ids.add(client_value.strip())

        # Kafka group membership is identified by MEMBER-ID. client.id is only diagnostic
        # metadata and may legitimately be identical across backend replicas.
        assignment_key = member_id
        if assignment_key is None and isinstance(client_value, str) and client_value.strip() and client_value != "-":
            assignment_key = client_value.strip()
        if assignment_key is not None:
            assignments.setdefault(assignment_key, set()).add((topic_value, partition_value))

    member_value = data.get("members") if isinstance(data, dict) else None
    if isinstance(member_value, int):
        member_count = member_value
    elif isinstance(member_value, list):
        member_count = len(member_value)
    else:
        member_count = len(member_ids) if member_ids else len(assignments)
    return ConsumerGroupSnapshot(
        member_count=member_count,
        member_ids=frozenset(member_ids),
        client_ids=frozenset(client_ids),
        assignments={member: frozenset(values) for member, values in assignments.items()},
        lag=lag,
    )


def consumer_group_snapshot(runner: CommandRunner, group: str) -> ConsumerGroupSnapshot:
    result = runner.rpk("group", "describe", group, "--format", "json", check=False)
    if result.returncode != 0 or not result.stdout.strip():
        raise AcceptanceError(f"consumer group {group} is unavailable: {result.stderr.strip()}")
    return parse_consumer_group_snapshot(result.stdout)


def wait_group_members(
    runner: CommandRunner,
    group: str,
    expected: int,
    timeout: float,
) -> ConsumerGroupSnapshot:
    snapshot: ConsumerGroupSnapshot | None = None

    def ready() -> bool:
        nonlocal snapshot
        snapshot = consumer_group_snapshot(runner, group)
        return snapshot.member_count >= expected and len(snapshot.member_ids) >= expected

    try:
        resilience.wait_until(f"{group} to reach {expected} active members", timeout, 1.0, ready)
    except AcceptanceError as failure:
        raise AcceptanceError(f"{failure}; last snapshot={snapshot}") from failure
    assert snapshot is not None
    return snapshot


def create_scaling_profile(
    admin: ApiSession,
    tracker: ResourceTracker,
    source_ids: list[str],
) -> str:
    profile = admin.post_json(
        "/api/v1/monitoring-profiles",
        {
            "name": f"Scaling backlog {uuid.uuid4().hex[:8]}",
            "informationCategory": "SCALING",
            "enabled": False,
            "collectionIntervalMinutes": 60,
            "sourceIds": source_ids,
            "criteria": {},
        },
    )
    profile_id = resilience.require_string(profile, "id")
    tracker.profile_ids.append(profile_id)
    return profile_id


def results_count(runner: CommandRunner, profile_id: str) -> int:
    escaped = profile_id.replace("'", "''")
    value = resilience.psql_scalar(
        runner,
        "SELECT count(*) FROM results.analyzed_items "
        f"WHERE monitoring_profile_id = '{escaped}';",
    )
    return int(value)


def analysis_count(runner: CommandRunner, profile_id: str) -> int:
    escaped = profile_id.replace("'", "''")
    value = resilience.psql_scalar(
        runner,
        "SELECT count(*) FROM analysis.normalized_item_claims "
        f"WHERE monitoring_profile_id = '{escaped}';",
    )
    return int(value)


def pending_outbox_count(runner: CommandRunner) -> int:
    value = resilience.psql_scalar(
        runner,
        "SELECT count(*) FROM analysis.event_outbox WHERE published_at IS NULL;",
    )
    return int(value)


@contextlib.contextmanager
def authenticated_admin_session(
    runner: CommandRunner,
    backend_port: int,
    http_timeout: float,
):
    """Opens a fresh backend tunnel that never spans a backend rollout."""

    with resilience.port_forward(runner, "service/signalharvester-backend", backend_port, 8080):
        base_url = f"http://127.0.0.1:{backend_port}"
        resilience.wait_until(
            "backend readiness through port-forward",
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
        admin = ApiSession(base_url, http_timeout)
        admin.login(bootstrap_username, bootstrap_password)
        yield admin


def verify_scaling(
    runner: CommandRunner,
    env_guard: BackendEnvironmentGuard,
    replica_guard: BackendReplicaGuard,
    *,
    profile_id: str,
    expected: int,
    dlq_before: int,
    outbox_before: int,
    target_replicas: int,
    timeout: float,
) -> None:
    if target_replicas != 3:
        raise AcceptanceError("the current local scaling contract uses exactly three Kafka partitions and three replicas")

    resilience.wait_until(
        "positive Analysis lag while the consumer is disabled",
        timeout,
        1.0,
        lambda: consumer_group_snapshot(runner, ANALYSIS_GROUP).lag > 0,
    )

    print("==> Restore one Analysis worker while backlog remains")
    env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", None)
    one = wait_group_members(runner, ANALYSIS_GROUP, 1, timeout)
    if one.lag <= 0:
        raise AcceptanceError(
            "Analysis backlog drained before scale-up could be observed; increase --sources or --items-per-source"
        )
    lag_at_one = one.lag
    print(f"    one-replica Analysis lag: {lag_at_one}")

    print(f"==> Scale backend from one to {target_replicas} replicas")
    replica_guard.set(target_replicas)
    analysis_scaled = wait_group_members(runner, ANALYSIS_GROUP, target_replicas, timeout)
    results_scaled = wait_group_members(runner, RESULTS_GROUP, target_replicas, timeout)
    observation_scaled = wait_group_members(runner, EVENT_OBSERVATION_GROUP, target_replicas, timeout)

    raw_assignments = {
        partition
        for assignments in analysis_scaled.assignments.values()
        for topic, partition in assignments
        if not topic or topic == RAW_TOPIC
    }
    if raw_assignments != {0, 1, 2}:
        raise AcceptanceError(
            f"Analysis consumers did not own all three raw-event partitions after scale-up: {analysis_scaled.assignments}"
        )
    if any(not assignments for assignments in analysis_scaled.assignments.values()):
        raise AcceptanceError(f"an Analysis scaling client had no partition assignment: {analysis_scaled.assignments}")

    print(f"    Analysis member IDs: {sorted(analysis_scaled.member_ids)}")
    print(f"    Analysis client IDs: {sorted(analysis_scaled.client_ids)}")
    print(f"    Analysis assignments: {analysis_scaled.assignments}")
    print(f"    Results active clients: {results_scaled.member_count}")
    print(f"    Event Observation active clients: {observation_scaled.member_count}")

    resilience.wait_until(
        "Analysis lag to decrease after scale-up",
        timeout,
        0.5,
        lambda: consumer_group_snapshot(runner, ANALYSIS_GROUP).lag < lag_at_one,
    )
    resilience.wait_until(
        "Analysis lag to drain after scale-up",
        timeout,
        1.0,
        lambda: consumer_group_snapshot(runner, ANALYSIS_GROUP).lag == 0,
    )
    print("    final Analysis lag: 0")

    resilience.wait_until(
        "all scaling Analysis state",
        timeout,
        1.0,
        lambda: analysis_count(runner, profile_id) == expected,
    )
    resilience.wait_until(
        "all scaling Results materialization",
        timeout,
        1.0,
        lambda: results_count(runner, profile_id) == expected,
    )
    resilience.wait_until(
        "Analysis outbox to return to baseline",
        timeout,
        1.0,
        lambda: pending_outbox_count(runner) == outbox_before,
    )
    dlq_after = resilience.rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
    if dlq_after != dlq_before:
        raise AcceptanceError(f"Analysis DLQ advanced during healthy scaling: {dlq_before} -> {dlq_after}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--backend-port", type=int, default=18082)
    parser.add_argument("--sources", type=int, default=12)
    parser.add_argument("--items-per-source", type=int, default=500)
    parser.add_argument("--target-replicas", type=int, default=3)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=240.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--skip-preflight", action="store_true")
    args = parser.parse_args(argv)
    if args.sources < 1 or args.sources > 20:
        parser.error("--sources must be between 1 and 20")
    if args.items_per_source < 1 or args.items_per_source > 500:
        parser.error("--items-per-source must be between 1 and 500")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    runner = CommandRunner(args.namespace)
    env_guard: BackendEnvironmentGuard | None = None
    replica_guard: BackendReplicaGuard | None = None
    tracker: ResourceTracker | None = None
    fixture_applied = False
    try:
        resilience.ensure_commands()
        if not args.skip_preflight:
            resilience.run_preflight(runner, args.rollout_timeout)

        replica_guard = BackendReplicaGuard(runner, args.rollout_timeout)
        print("==> Deploy deterministic scaling fixture")
        cluster_ip = resilience.deploy_fixture(runner, args.rollout_timeout)
        fixture_applied = True
        env_guard = BackendEnvironmentGuard(runner, args.rollout_timeout)
        existing_allowed = env_guard.effective_value("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS") or ""
        cidr = resilience.fixture_cidr(cluster_ip)
        combined = ",".join(value for value in (existing_allowed, cidr) if value)
        env_guard.set("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS", combined)

        print("==> Establish one-replica scaling baseline")
        replica_guard.set(1)
        env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", "false")

        with authenticated_admin_session(runner, args.backend_port, args.http_timeout) as admin:
            tracker = ResourceTracker(admin)
            source_ids = [
                tracker.source(f"/scale/{index:02d}.xml?items={args.items_per_source}", f"scale-{index:02d}")
                for index in range(args.sources)
            ]
            profile_id = create_scaling_profile(admin, tracker, source_ids)
            expected = args.sources * args.items_per_source
            dlq_before = resilience.rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
            outbox_before = pending_outbox_count(runner)

            print(
                f"==> Generate bounded backlog "
                f"({args.sources} sources x {args.items_per_source} items = {expected})"
            )
            run = resilience.run_collection(admin, profile_id, timeout=args.scenario_timeout)
            published = int(run.get("publishedCount", 0))
            if published != expected:
                raise AcceptanceError(
                    f"scaling fixture published {published} items, expected {expected}: {run!r}"
                )

        verify_scaling(
            runner,
            env_guard,
            replica_guard,
            profile_id=profile_id,
            expected=expected,
            dlq_before=dlq_before,
            outbox_before=outbox_before,
            target_replicas=args.target_replicas,
            timeout=args.scenario_timeout,
        )

        with authenticated_admin_session(runner, args.backend_port, args.http_timeout) as admin:
            status, body = admin.request("GET", "/api/v1/admin/collection-runs?limit=1")
            if status != 200:
                raise AcceptanceError(
                    f"authenticated HTTP availability failed after scale-up: HTTP {status} {body!r}"
                )
            assert tracker is not None
            tracker.admin = admin
            tracker.cleanup()
            tracker = None

        print("Kafka consumer horizontal scaling acceptance passed.")
        return 0
    except (AcceptanceError, subprocess.TimeoutExpired, OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    finally:
        if env_guard is not None:
            env_guard.restore()
        if replica_guard is not None:
            replica_guard.restore()
        if tracker is not None:
            try:
                with authenticated_admin_session(runner, args.backend_port, args.http_timeout) as cleanup_admin:
                    tracker.admin = cleanup_admin
                    tracker.cleanup()
            except Exception as failure:  # noqa: BLE001 - cleanup must not hide the acceptance result
                print(f"WARNING: scaling resource cleanup failed: {failure}", file=sys.stderr)
        if fixture_applied:
            runner.kubectl("delete", "-f", str(resilience.FIXTURE_MANIFEST), "--ignore-not-found=true", check=False)


if __name__ == "__main__":
    raise SystemExit(main())
