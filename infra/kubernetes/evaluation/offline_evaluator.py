#!/usr/bin/env python3
"""Evaluate bounded operational scenario evidence without changing runtime Health or alert decisions."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from statistics import fmean
from typing import Any, Iterable, Mapping, Sequence

EVIDENCE_SCHEMA_VERSION = 1
EVIDENCE_ARTIFACT_TYPE = "signalharvester-operational-scenario-evidence"
EVALUATION_SCHEMA_VERSION = 1
EVALUATION_ARTIFACT_TYPE = "signalharvester-operational-intelligence-evaluation"
DETECTING_STATUSES = {"DEGRADED", "UNHEALTHY"}
KNOWN_STATUSES = {"HEALTHY", "DEGRADED", "UNHEALTHY", "UNKNOWN"}
MAX_INPUTS = 32
MAX_SCENARIOS = 512
MAX_INPUT_BYTES = 8_000_000
DEFAULT_OUTPUT = Path("build/reports/operational-intelligence/offline-evaluation.json")


class EvaluationError(ValueError):
    """Raised when an evidence artifact cannot be evaluated safely or deterministically."""


@dataclass(frozen=True)
class AlertProjectionPolicy:
    """Explicit parameters used for offline alert-policy projection over captured Health Snapshots."""

    policy_version: str = "human-attention-v1"
    degraded_min_consecutive: int = 3
    unhealthy_min_consecutive: int = 1
    healthy_min_consecutive_to_resolve: int = 2
    reopen_cooldown_seconds: int = 900

    def __post_init__(self) -> None:
        if not self.policy_version.strip():
            raise EvaluationError("alert policy version must not be blank")
        for name, value in (
            ("degraded_min_consecutive", self.degraded_min_consecutive),
            ("unhealthy_min_consecutive", self.unhealthy_min_consecutive),
            ("healthy_min_consecutive_to_resolve", self.healthy_min_consecutive_to_resolve),
        ):
            if value < 1:
                raise EvaluationError(f"{name} must be at least one")
        if self.reopen_cooldown_seconds < 0:
            raise EvaluationError("reopen_cooldown_seconds must not be negative")

    def to_dict(self) -> dict[str, Any]:
        """Returns the projection parameters recorded in the evaluation report."""

        return {
            "policyVersion": self.policy_version,
            "degradedMinConsecutiveSnapshots": self.degraded_min_consecutive,
            "unhealthyMinConsecutiveSnapshots": self.unhealthy_min_consecutive,
            "healthyMinConsecutiveSnapshotsToResolve": self.healthy_min_consecutive_to_resolve,
            "reopenCooldownSeconds": self.reopen_cooldown_seconds,
        }


@dataclass
class AlertProjectionState:
    """Minimal isolated alert lifecycle state used only for offline scenario projection."""

    active_severity: str | None = None
    last_status: str | None = None
    consecutive_status_count: int = 0
    resolved_at: datetime | None = None


def utc_now() -> str:
    """Returns one UTC timestamp in ISO-8601 form."""

    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    """Returns the SHA-256 digest for one input artifact."""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_mapping(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise EvaluationError(f"{name} must be an object")
    return value


def _require_list(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvaluationError(f"{name} must be an array")
    return value


def _require_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvaluationError(f"{name} must be a non-empty string")
    return value.strip()


def _parse_time(value: Any, name: str) -> datetime:
    text = _require_text(value, name)
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as failure:
        raise EvaluationError(f"{name} must be an ISO-8601 timestamp: {text!r}") from failure
    if parsed.tzinfo is None:
        raise EvaluationError(f"{name} must include a timezone offset")
    return parsed.astimezone(timezone.utc)


def _optional_time(value: Any, name: str) -> datetime | None:
    return None if value is None else _parse_time(value, name)


def _millis(delta: timedelta) -> int:
    return max(0, round(delta.total_seconds() * 1000))


def _ratio(numerator: int, denominator: int) -> float | None:
    return None if denominator == 0 else round(numerator / denominator, 6)


def _mean(values: Iterable[int]) -> float | None:
    materialized = list(values)
    return None if not materialized else round(fmean(materialized), 3)


def _normalize_subsystem(value: str) -> str:
    return "".join(character for character in value.upper() if character.isalnum())


def _load_dataset(path: Path) -> Mapping[str, Any]:
    try:
        size = path.stat().st_size
        if size > MAX_INPUT_BYTES:
            raise EvaluationError(
                f"evidence artifact {path} exceeds the bounded {MAX_INPUT_BYTES}-byte input limit"
            )
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise EvaluationError(f"failed to read evidence artifact {path}: {failure}") from failure
    except json.JSONDecodeError as failure:
        raise EvaluationError(f"evidence artifact is not valid JSON: {path}: {failure}") from failure
    root = _require_mapping(payload, str(path))
    if root.get("schemaVersion") != EVIDENCE_SCHEMA_VERSION:
        raise EvaluationError(
            f"unsupported evidence schemaVersion in {path}: {root.get('schemaVersion')!r}; "
            f"expected {EVIDENCE_SCHEMA_VERSION}"
        )
    if root.get("artifactType") != EVIDENCE_ARTIFACT_TYPE:
        raise EvaluationError(f"unsupported artifactType in {path}: {root.get('artifactType')!r}")
    scenarios = _require_list(root.get("scenarios"), f"{path}.scenarios")
    if len(scenarios) > MAX_SCENARIOS:
        raise EvaluationError(f"{path} exceeds the bounded {MAX_SCENARIOS}-scenario evaluation limit")
    return root


def _scenario_snapshots(scenario: Mapping[str, Any]) -> list[dict[str, Any]]:
    evidence = _require_mapping(scenario.get("evidence"), "scenario.evidence")
    entries = _require_list(evidence.get("healthSnapshots"), "scenario.evidence.healthSnapshots")
    snapshots: list[dict[str, Any]] = []
    for index, entry_value in enumerate(entries):
        entry = _require_mapping(entry_value, f"healthSnapshots[{index}]")
        phase = _require_text(entry.get("phase"), f"healthSnapshots[{index}].phase")
        snapshot = _require_mapping(entry.get("snapshot"), f"healthSnapshots[{index}].snapshot")
        generated_at = _parse_time(snapshot.get("generatedAt"), f"healthSnapshots[{index}].snapshot.generatedAt")
        status = _require_text(snapshot.get("overallStatus"), f"healthSnapshots[{index}].snapshot.overallStatus").upper()
        if status not in KNOWN_STATUSES:
            raise EvaluationError(f"unsupported Health status {status!r}")
        component_statuses = _require_mapping(
            snapshot.get("componentStatuses", {}), f"healthSnapshots[{index}].snapshot.componentStatuses"
        )
        snapshots.append(
            {
                "phase": phase,
                "snapshot": snapshot,
                "generatedAt": generated_at,
                "status": status,
                "componentStatuses": component_statuses,
            }
        )
    snapshots.sort(key=lambda item: item["generatedAt"])
    return snapshots


def _marker_time(scenario: Mapping[str, Any], phase: str) -> datetime | None:
    evidence = _require_mapping(scenario.get("evidence"), "scenario.evidence")
    markers = _require_list(evidence.get("changeMarkers"), "scenario.evidence.changeMarkers")
    matches: list[datetime] = []
    for index, entry_value in enumerate(markers):
        entry = _require_mapping(entry_value, f"changeMarkers[{index}]")
        if entry.get("phase") != phase:
            continue
        change = _require_mapping(entry.get("change"), f"changeMarkers[{index}].change")
        matches.append(_parse_time(change.get("changedAt"), f"changeMarkers[{index}].change.changedAt"))
    return min(matches) if matches else None


def _fault_window(scenario: Mapping[str, Any]) -> tuple[datetime, datetime, str]:
    start = _marker_time(scenario, "FAULT_START")
    end = _marker_time(scenario, "FAULT_END")
    if start is not None and end is not None:
        if end < start:
            raise EvaluationError("FAULT_END marker precedes FAULT_START marker")
        return start, end, "FAULT_MARKERS"

    window = _require_mapping(scenario.get("window"), "scenario.window")
    fallback_start = _parse_time(window.get("startedAt"), "scenario.window.startedAt")
    fallback_end = _optional_time(window.get("endedAt"), "scenario.window.endedAt")
    if fallback_end is None:
        fallback_end = fallback_start
    if fallback_end < fallback_start:
        raise EvaluationError("scenario window endedAt precedes startedAt")
    return fallback_start, fallback_end, "SCENARIO_WINDOW_FALLBACK"


def _observed_subsystems(snapshots: Sequence[dict[str, Any]]) -> list[str]:
    observed: set[str] = set()
    for item in snapshots:
        for key, raw_status in item["componentStatuses"].items():
            if isinstance(key, str) and isinstance(raw_status, str) and raw_status.upper() in DETECTING_STATUSES:
                observed.add(key)
    return sorted(observed)


def evaluate_health_scenario(scenario: Mapping[str, Any]) -> dict[str, Any]:
    """Evaluates detector observations against scenario ground truth without inventing expected severity."""

    ground_truth = _require_mapping(scenario.get("groundTruth"), "scenario.groundTruth")
    fault_injected = bool(ground_truth.get("faultInjected"))
    expected_subsystems = [
        _require_text(value, "groundTruth.affectedSubsystems[]")
        for value in _require_list(ground_truth.get("affectedSubsystems", []), "groundTruth.affectedSubsystems")
    ]
    snapshots = _scenario_snapshots(scenario)
    after_snapshots = [item for item in snapshots if item["phase"] == "AFTER"]
    recovery_status = after_snapshots[-1]["status"] if after_snapshots else None

    if not fault_injected:
        false_positive_snapshots = [item for item in snapshots if item["status"] in DETECTING_STATUSES]
        return {
            "evaluable": bool(snapshots),
            "evaluationKind": "NORMAL_OPERATION_FALSE_POSITIVE",
            "snapshotCount": len(snapshots),
            "falsePositive": bool(false_positive_snapshots),
            "falsePositiveSnapshotIds": [item["snapshot"].get("id") for item in false_positive_snapshots],
            "unknownObserved": any(item["status"] == "UNKNOWN" for item in snapshots),
            "recoveryStatus": recovery_status,
        }

    fault_start, fault_end, timing_source = _fault_window(scenario)
    fault_snapshots = [item for item in snapshots if fault_start <= item["generatedAt"] <= fault_end]
    detected = [item for item in fault_snapshots if item["status"] in DETECTING_STATUSES]
    first_detection = detected[0] if detected else None
    observed_subsystems = _observed_subsystems(detected)
    expected_by_normalized = {_normalize_subsystem(value): value for value in expected_subsystems}
    observed_by_normalized = {_normalize_subsystem(value): value for value in observed_subsystems}
    matched_keys = sorted(set(expected_by_normalized) & set(observed_by_normalized))
    missed_keys = sorted(set(expected_by_normalized) - set(observed_by_normalized))
    unexpected_keys = sorted(set(observed_by_normalized) - set(expected_by_normalized))

    return {
        "evaluable": bool(fault_snapshots),
        "evaluationKind": "FAULT_DETECTION",
        "timingSource": timing_source,
        "faultWindow": {"startedAt": fault_start.isoformat(), "endedAt": fault_end.isoformat()},
        "faultSnapshotCount": len(fault_snapshots),
        "detectionSuccess": bool(first_detection) if fault_snapshots else None,
        "firstDetectionAt": first_detection["generatedAt"].isoformat() if first_detection else None,
        "timeToDetectionMs": _millis(first_detection["generatedAt"] - fault_start) if first_detection else None,
        "firstDetectedStatus": first_detection["status"] if first_detection else None,
        "groundTruthAffectedSubsystems": expected_subsystems,
        "observedAffectedSubsystems": observed_subsystems,
        "matchedAffectedSubsystems": [expected_by_normalized[key] for key in matched_keys],
        "missedAffectedSubsystems": [expected_by_normalized[key] for key in missed_keys],
        "unexpectedAffectedSubsystems": [observed_by_normalized[key] for key in unexpected_keys],
        "affectedSubsystemCoverage": _ratio(len(matched_keys), len(expected_by_normalized)),
        "affectedSubsystemPrecision": _ratio(len(matched_keys), len(observed_by_normalized)),
        "unknownObservedDuringFault": any(item["status"] == "UNKNOWN" for item in fault_snapshots),
        "recoveryStatus": recovery_status,
        "recoveredHealthy": recovery_status == "HEALTHY" if recovery_status is not None else None,
    }


def _apply_alert_projection(
    state: AlertProjectionState,
    status: str,
    observed_at: datetime,
    policy: AlertProjectionPolicy,
) -> dict[str, Any] | None:
    if status == state.last_status:
        state.consecutive_status_count += 1
    else:
        state.last_status = status
        state.consecutive_status_count = 1

    if state.active_severity is not None:
        if status == "HEALTHY" and state.consecutive_status_count >= policy.healthy_min_consecutive_to_resolve:
            previous = state.active_severity
            state.active_severity = None
            state.resolved_at = observed_at
            return {"action": "RESOLVE", "at": observed_at.isoformat(), "previousSeverity": previous}
        if (
            status == "UNHEALTHY"
            and state.consecutive_status_count >= policy.unhealthy_min_consecutive
            and state.active_severity != "CRITICAL"
        ):
            state.active_severity = "CRITICAL"
            return {"action": "ESCALATE", "at": observed_at.isoformat(), "severity": "CRITICAL"}
        return None

    if status == "UNHEALTHY" and state.consecutive_status_count >= policy.unhealthy_min_consecutive:
        state.active_severity = "CRITICAL"
        return {"action": "OPEN", "at": observed_at.isoformat(), "severity": "CRITICAL", "reason": "UNHEALTHY"}

    if status == "DEGRADED" and state.consecutive_status_count >= policy.degraded_min_consecutive:
        cooldown_complete = (
            state.resolved_at is None
            or observed_at - state.resolved_at >= timedelta(seconds=policy.reopen_cooldown_seconds)
        )
        if cooldown_complete:
            state.active_severity = "WARNING"
            return {
                "action": "OPEN",
                "at": observed_at.isoformat(),
                "severity": "WARNING",
                "reason": "SUSTAINED_DEGRADED",
            }
    return None


def evaluate_alert_projection_scenario(
    scenario: Mapping[str, Any], policy: AlertProjectionPolicy
) -> dict[str, Any]:
    """Projects the configured alert lifecycle over scenario snapshots without claiming runtime alert output."""

    ground_truth = _require_mapping(scenario.get("groundTruth"), "scenario.groundTruth")
    fault_injected = bool(ground_truth.get("faultInjected"))
    snapshots = _scenario_snapshots(scenario)
    state = AlertProjectionState()
    events: list[dict[str, Any]] = []
    for item in snapshots:
        event = _apply_alert_projection(state, item["status"], item["generatedAt"], policy)
        if event is not None:
            event["snapshotId"] = item["snapshot"].get("id")
            event["healthStatus"] = item["status"]
            event["phase"] = item["phase"]
            events.append(event)

    open_events = [event for event in events if event["action"] == "OPEN"]
    max_severity = None
    if any(event.get("severity") == "CRITICAL" for event in events):
        max_severity = "CRITICAL"
    elif any(event.get("severity") == "WARNING" for event in events):
        max_severity = "WARNING"

    if not fault_injected:
        return {
            "mode": "OFFLINE_POLICY_PROJECTION",
            "evaluable": bool(snapshots),
            "snapshotCount": len(snapshots),
            "attentionTriggered": bool(open_events),
            "falsePositive": bool(open_events),
            "maxSeverity": max_severity,
            "activeAtEnd": state.active_severity is not None,
            "events": events,
        }

    fault_start, fault_end, timing_source = _fault_window(scenario)
    snapshots_in_fault = [item for item in snapshots if fault_start <= item["generatedAt"] <= fault_end]
    opens_during_fault = [
        event
        for event in open_events
        if fault_start <= _parse_time(event["at"], "alert event at") <= fault_end
    ]
    opens_before_fault = [event for event in open_events if _parse_time(event["at"], "alert event at") < fault_start]
    opens_after_fault = [event for event in open_events if _parse_time(event["at"], "alert event at") > fault_end]
    first_during_fault = opens_during_fault[0] if opens_during_fault else None

    return {
        "mode": "OFFLINE_POLICY_PROJECTION",
        "evaluable": bool(snapshots_in_fault) and not opens_before_fault,
        "timingSource": timing_source,
        "faultWindow": {"startedAt": fault_start.isoformat(), "endedAt": fault_end.isoformat()},
        "faultSnapshotCount": len(snapshots_in_fault),
        "attentionTriggeredDuringFault": bool(first_during_fault) if snapshots_in_fault and not opens_before_fault else None,
        "firstAttentionAt": first_during_fault["at"] if first_during_fault else None,
        "timeToAttentionMs": (
            _millis(_parse_time(first_during_fault["at"], "first attention") - fault_start)
            if first_during_fault
            else None
        ),
        "attentionBeforeFault": bool(opens_before_fault),
        "lateAttentionAfterFault": bool(opens_after_fault),
        "maxSeverity": max_severity,
        "activeAtEnd": state.active_severity is not None,
        "resolvedByFinalSnapshot": bool(open_events) and state.active_severity is None,
        "events": events,
    }


def _scenario_identity(dataset: Mapping[str, Any], scenario: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "datasetRunId": _require_text(dataset.get("datasetRunId"), "datasetRunId"),
        "scenarioId": _require_text(scenario.get("scenarioId"), "scenario.scenarioId"),
        "scenarioRunId": _require_text(scenario.get("scenarioRunId"), "scenario.scenarioRunId"),
        "label": _require_text(scenario.get("label"), "scenario.label"),
        "outcome": _require_text(scenario.get("outcome"), "scenario.outcome"),
        "faultInjected": bool(_require_mapping(scenario.get("groundTruth"), "scenario.groundTruth").get("faultInjected")),
    }


def _aggregate(results: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    passed = [result for result in results if result["outcome"] == "PASSED"]
    excluded = len(results) - len(passed)
    fault_health = [
        result["health"]
        for result in passed
        if result["faultInjected"] and result["health"].get("evaluable")
    ]
    normal_health = [
        result["health"]
        for result in passed
        if not result["faultInjected"] and result["health"].get("evaluable")
    ]
    fault_alerts = [
        result["alertProjection"]
        for result in passed
        if result["faultInjected"] and result["alertProjection"].get("evaluable")
    ]
    normal_alerts = [
        result["alertProjection"]
        for result in passed
        if not result["faultInjected"] and result["alertProjection"].get("evaluable")
    ]
    subsystem_expected = sum(len(item.get("groundTruthAffectedSubsystems", [])) for item in fault_health)
    subsystem_matched = sum(len(item.get("matchedAffectedSubsystems", [])) for item in fault_health)

    detected_count = sum(item.get("detectionSuccess") is True for item in fault_health)
    health_false_positive_count = sum(item.get("falsePositive") is True for item in normal_health)
    attention_count = sum(item.get("attentionTriggeredDuringFault") is True for item in fault_alerts)
    alert_false_positive_count = sum(item.get("falsePositive") is True for item in normal_alerts)

    return {
        "scenarios": {
            "total": len(results),
            "passed": len(passed),
            "excludedNonPassed": excluded,
        },
        "health": {
            "faultScenariosEvaluated": len(fault_health),
            "faultScenariosDetected": detected_count,
            "faultScenariosMissed": len(fault_health) - detected_count,
            "detectionRate": _ratio(detected_count, len(fault_health)),
            "normalScenariosEvaluated": len(normal_health),
            "falsePositiveScenarios": health_false_positive_count,
            "falsePositiveRate": _ratio(health_false_positive_count, len(normal_health)),
            "meanTimeToDetectionMs": _mean(
                item["timeToDetectionMs"]
                for item in fault_health
                if item.get("timeToDetectionMs") is not None
            ),
            "groundTruthSubsystemsEvaluated": subsystem_expected,
            "groundTruthSubsystemsMatched": subsystem_matched,
            "affectedSubsystemCoverage": _ratio(subsystem_matched, subsystem_expected),
        },
        "alertProjection": {
            "mode": "OFFLINE_POLICY_PROJECTION",
            "faultScenariosEvaluated": len(fault_alerts),
            "faultScenariosWithAttention": attention_count,
            "attentionRate": _ratio(attention_count, len(fault_alerts)),
            "normalScenariosEvaluated": len(normal_alerts),
            "falsePositiveScenarios": alert_false_positive_count,
            "falsePositiveRate": _ratio(alert_false_positive_count, len(normal_alerts)),
            "meanTimeToAttentionMs": _mean(
                item["timeToAttentionMs"]
                for item in fault_alerts
                if item.get("timeToAttentionMs") is not None
            ),
        },
    }


def build_evaluation(paths: Sequence[Path], policy: AlertProjectionPolicy) -> dict[str, Any]:
    """Builds one deterministic JSON-compatible evaluation report from bounded evidence inputs."""

    if not paths:
        raise EvaluationError("at least one evidence artifact is required")
    if len(paths) > MAX_INPUTS:
        raise EvaluationError(f"at most {MAX_INPUTS} evidence artifacts may be evaluated at once")

    normalized_paths = [path.resolve() for path in paths]
    if len(set(normalized_paths)) != len(normalized_paths):
        raise EvaluationError("duplicate evidence artifact paths are not allowed")

    inputs: list[dict[str, Any]] = []
    results: list[dict[str, Any]] = []
    for path in paths:
        dataset = _load_dataset(path)
        source = _require_mapping(dataset.get("source"), "source")
        inputs.append(
            {
                "path": str(path),
                "sha256": sha256_file(path),
                "datasetRunId": _require_text(dataset.get("datasetRunId"), "datasetRunId"),
                "runner": _require_text(source.get("runner"), "source.runner"),
            }
        )
        for raw_scenario in _require_list(dataset.get("scenarios"), "scenarios"):
            if len(results) >= MAX_SCENARIOS:
                raise EvaluationError(f"evaluation exceeds the bounded {MAX_SCENARIOS}-scenario total limit")
            scenario = _require_mapping(raw_scenario, "scenario")
            identity = _scenario_identity(dataset, scenario)
            if identity["outcome"] != "PASSED":
                results.append(
                    {
                        **identity,
                        "health": {"evaluable": False, "excludedReason": "SCENARIO_NOT_PASSED"},
                        "alertProjection": {
                            "mode": "OFFLINE_POLICY_PROJECTION",
                            "evaluable": False,
                            "excludedReason": "SCENARIO_NOT_PASSED",
                        },
                    }
                )
                continue
            results.append(
                {
                    **identity,
                    "health": evaluate_health_scenario(scenario),
                    "alertProjection": evaluate_alert_projection_scenario(scenario, policy),
                }
            )

    return {
        "schemaVersion": EVALUATION_SCHEMA_VERSION,
        "artifactType": EVALUATION_ARTIFACT_TYPE,
        "generatedAt": utc_now(),
        "inputs": inputs,
        "evaluation": {
            "healthMode": "OBSERVED_HEALTH_SNAPSHOTS",
            "alertMode": "OFFLINE_POLICY_PROJECTION",
            "alertPolicy": policy.to_dict(),
            "notes": [
                "Ground truth identifies injected conditions and affected subsystems; it does not prescribe Health severity.",
                "Alert results are an offline projection over captured Health Snapshots, not persisted runtime alert decisions.",
                "Fault scenarios without a Health Snapshot inside the bounded fault window are excluded from detector/alert rates rather than counted as misses.",
            ],
        },
        "summary": _aggregate(results),
        "scenarios": results,
    }


def write_evaluation(path: Path, report: Mapping[str, Any]) -> None:
    """Writes one stable formatted evaluation artifact."""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", nargs="+", type=Path, help="Scenario evidence JSON artifact(s) to evaluate")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--alert-policy-version", default="human-attention-v1")
    parser.add_argument("--degraded-min-consecutive", type=int, default=3)
    parser.add_argument("--unhealthy-min-consecutive", type=int, default=1)
    parser.add_argument("--healthy-min-consecutive-to-resolve", type=int, default=2)
    parser.add_argument("--reopen-cooldown-seconds", type=int, default=900)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        policy = AlertProjectionPolicy(
            policy_version=args.alert_policy_version,
            degraded_min_consecutive=args.degraded_min_consecutive,
            unhealthy_min_consecutive=args.unhealthy_min_consecutive,
            healthy_min_consecutive_to_resolve=args.healthy_min_consecutive_to_resolve,
            reopen_cooldown_seconds=args.reopen_cooldown_seconds,
        )
        report = build_evaluation(args.evidence, policy)
        write_evaluation(args.output, report)
        print(args.output)
        return 0
    except EvaluationError as failure:
        print(f"ERROR: {failure}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
