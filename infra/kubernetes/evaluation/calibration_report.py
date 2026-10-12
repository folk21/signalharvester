#!/usr/bin/env python3
"""Compare repeated operational-intelligence evidence without mutating runtime policy."""

from __future__ import annotations

import argparse
import itertools
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

import assisted_investigation_evaluator as assisted_evaluator
import offline_evaluator

CALIBRATION_SCHEMA_VERSION = 1
CALIBRATION_ARTIFACT_TYPE = "signalharvester-operational-intelligence-calibration"
MAX_SCENARIO_INPUTS = 32
MAX_INVESTIGATION_INPUTS = 32
MAX_ALERT_CANDIDATES = 64
MAX_INPUT_BYTES = 8_000_000
DEFAULT_OUTPUT = Path("build/reports/operational-intelligence/calibration-report.json")


class CalibrationError(ValueError):
    """Raised when calibration inputs cannot be compared safely and deterministically."""


def utc_now() -> str:
    """Returns one UTC timestamp in ISO-8601 form."""

    return datetime.now(timezone.utc).isoformat()


def _require_mapping(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise CalibrationError(f"{name} must be an object")
    return value


def _require_list(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise CalibrationError(f"{name} must be an array")
    return value


def _require_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise CalibrationError(f"{name} must be a non-empty string")
    return value.strip()


def _require_non_negative_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise CalibrationError(f"{name} must be a non-negative integer")
    return value


def _require_positive_int(value: Any, name: str) -> int:
    result = _require_non_negative_int(value, name)
    if result < 1:
        raise CalibrationError(f"{name} must be at least one")
    return result


def _load_json(path: Path, kind: str) -> Mapping[str, Any]:
    try:
        size = path.stat().st_size
        if size > MAX_INPUT_BYTES:
            raise CalibrationError(f"{kind} artifact {path} exceeds the bounded {MAX_INPUT_BYTES}-byte input limit")
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise CalibrationError(f"failed to read {kind} artifact {path}: {failure}") from failure
    except json.JSONDecodeError as failure:
        raise CalibrationError(f"{kind} artifact is not valid JSON: {path}: {failure}") from failure
    return _require_mapping(payload, str(path))


def _unique_paths(paths: Sequence[Path], name: str, maximum: int) -> list[Path]:
    if not paths:
        raise CalibrationError(f"at least one {name} artifact is required")
    if len(paths) > maximum:
        raise CalibrationError(f"at most {maximum} {name} artifacts may be calibrated at once")
    normalized = [path.resolve() for path in paths]
    if len(set(normalized)) != len(normalized):
        raise CalibrationError(f"duplicate {name} artifact paths are not allowed")
    return list(paths)


def _health_policy_version(dataset: Mapping[str, Any], path: Path) -> str:
    versions: set[str] = set()
    snapshot_count = 0
    for raw_scenario in _require_list(dataset.get("scenarios"), "scenarios"):
        scenario = _require_mapping(raw_scenario, "scenario")
        evidence = _require_mapping(scenario.get("evidence"), "scenario.evidence")
        for raw_entry in _require_list(evidence.get("healthSnapshots"), "scenario.evidence.healthSnapshots"):
            entry = _require_mapping(raw_entry, "health snapshot evidence")
            snapshot = _require_mapping(entry.get("snapshot"), "health snapshot evidence.snapshot")
            snapshot_count += 1
            versions.add(_require_text(snapshot.get("policyVersion"), "health snapshot policyVersion"))
    if snapshot_count == 0:
        raise CalibrationError(f"scenario evidence {path} has no captured Health Snapshots")
    if len(versions) != 1:
        raise CalibrationError(
            f"scenario evidence {path} mixes Health policy versions: {sorted(versions)}; "
            "one dataset run must use one effective Health policy"
        )
    return next(iter(versions))


def _scenario_metadata(path: Path) -> tuple[Mapping[str, Any], dict[str, Any]]:
    dataset = _load_json(path, "scenario evidence")
    if dataset.get("schemaVersion") != offline_evaluator.EVIDENCE_SCHEMA_VERSION:
        raise CalibrationError(f"unsupported scenario evidence schemaVersion in {path}: {dataset.get('schemaVersion')!r}")
    if dataset.get("artifactType") != offline_evaluator.EVIDENCE_ARTIFACT_TYPE:
        raise CalibrationError(f"unsupported scenario evidence artifactType in {path}: {dataset.get('artifactType')!r}")
    source = _require_mapping(dataset.get("source"), "source")
    scenarios = _require_list(dataset.get("scenarios"), "scenarios")
    labels = Counter()
    passed_labels = Counter()
    scenario_run_ids: list[str] = []
    for raw in scenarios:
        scenario = _require_mapping(raw, "scenario")
        scenario_run_ids.append(_require_text(scenario.get("scenarioRunId"), "scenario.scenarioRunId"))
        label = _require_text(scenario.get("label"), "scenario.label")
        labels[label] += 1
        if _require_text(scenario.get("outcome"), "scenario.outcome") == "PASSED":
            passed_labels[label] += 1
    policy_version = _health_policy_version(dataset, path)
    metadata = {
        "path": str(path),
        "sha256": offline_evaluator.sha256_file(path),
        "datasetRunId": _require_text(dataset.get("datasetRunId"), "datasetRunId"),
        "runner": _require_text(source.get("runner"), "source.runner"),
        "healthPolicyVersion": policy_version,
        "scenarioCount": len(scenarios),
        "scenarioLabels": dict(sorted(labels.items())),
        "passedScenarioLabels": dict(sorted(passed_labels.items())),
        "scenarioRunIds": scenario_run_ids,
    }
    return dataset, metadata


def _parse_int_values(text: str, name: str, *, minimum: int) -> tuple[int, ...]:
    values: list[int] = []
    for raw in text.split(","):
        candidate = raw.strip()
        if not candidate:
            raise CalibrationError(f"{name} contains an empty value")
        try:
            value = int(candidate)
        except ValueError as failure:
            raise CalibrationError(f"{name} must contain comma-separated integers") from failure
        if value < minimum:
            raise CalibrationError(f"{name} values must be at least {minimum}")
        if value not in values:
            values.append(value)
    if not values:
        raise CalibrationError(f"{name} must contain at least one value")
    return tuple(values)


def _alert_policies(
    policy_version: str,
    degraded: Sequence[int],
    unhealthy: Sequence[int],
    healthy: Sequence[int],
    cooldowns: Sequence[int],
) -> list[offline_evaluator.AlertProjectionPolicy]:
    combinations = list(itertools.product(degraded, unhealthy, healthy, cooldowns))
    if len(combinations) > MAX_ALERT_CANDIDATES:
        raise CalibrationError(
            f"alert-policy sweep expands to {len(combinations)} candidates; maximum is {MAX_ALERT_CANDIDATES}"
        )
    return [
        offline_evaluator.AlertProjectionPolicy(
            policy_version=policy_version,
            degraded_min_consecutive=degraded_value,
            unhealthy_min_consecutive=unhealthy_value,
            healthy_min_consecutive_to_resolve=healthy_value,
            reopen_cooldown_seconds=cooldown_value,
        )
        for degraded_value, unhealthy_value, healthy_value, cooldown_value in combinations
    ]


def _assisted_metadata(path: Path) -> dict[str, Any]:
    artifact = _load_json(path, "assisted-investigation evidence")
    if artifact.get("schemaVersion") != assisted_evaluator.INVESTIGATION_SCHEMA_VERSION:
        raise CalibrationError(
            f"unsupported assisted-investigation schemaVersion in {path}: {artifact.get('schemaVersion')!r}"
        )
    if artifact.get("artifactType") != assisted_evaluator.INVESTIGATION_ARTIFACT_TYPE:
        raise CalibrationError(
            f"unsupported assisted-investigation artifactType in {path}: {artifact.get('artifactType')!r}"
        )
    source = _require_mapping(artifact.get("source"), "source")
    budgets = _require_mapping(artifact.get("budgets"), "budgets")
    trials = _require_list(artifact.get("trials"), "trials")
    trial_ids = [
        _require_text(_require_mapping(trial, "trial").get("trialId"), "trial.trialId")
        for trial in trials
    ]
    return {
        "path": str(path),
        "sha256": assisted_evaluator.sha256_file(path),
        "runner": _require_text(source.get("runner"), "source.runner"),
        "provider": _require_text(source.get("provider"), "source.provider"),
        "model": _require_text(source.get("model"), "source.model"),
        "budgets": {
            "maxToolCalls": _require_non_negative_int(budgets.get("maxToolCalls"), "budgets.maxToolCalls"),
            "maxRounds": _require_positive_int(budgets.get("maxRounds"), "budgets.maxRounds"),
            "maxInvestigationDurationMs": _require_positive_int(
                budgets.get("maxInvestigationDurationMs"), "budgets.maxInvestigationDurationMs"
            ),
        },
        "trialCount": len(trials),
        "trialIds": trial_ids,
    }


def build_calibration(
    scenario_paths: Sequence[Path],
    alert_policies: Sequence[offline_evaluator.AlertProjectionPolicy],
    investigation_paths: Sequence[Path] = (),
) -> dict[str, Any]:
    """Builds a descriptive repeated-run calibration matrix over bounded evidence inputs."""

    scenarios = _unique_paths(scenario_paths, "scenario evidence", MAX_SCENARIO_INPUTS)
    if not alert_policies:
        raise CalibrationError("at least one alert-policy candidate is required")
    if len(alert_policies) > MAX_ALERT_CANDIDATES:
        raise CalibrationError(f"at most {MAX_ALERT_CANDIDATES} alert-policy candidates may be compared")

    scenario_inputs: list[dict[str, Any]] = []
    by_health_policy: dict[str, list[Path]] = defaultdict(list)
    dataset_run_ids: set[str] = set()
    scenario_run_ids: set[str] = set()
    for path in scenarios:
        _, metadata = _scenario_metadata(path)
        dataset_run_id = metadata["datasetRunId"]
        if dataset_run_id in dataset_run_ids:
            raise CalibrationError(f"duplicate datasetRunId across scenario evidence: {dataset_run_id}")
        dataset_run_ids.add(dataset_run_id)
        for scenario_run_id in metadata.pop("scenarioRunIds"):
            if scenario_run_id in scenario_run_ids:
                raise CalibrationError(f"duplicate scenarioRunId across scenario evidence: {scenario_run_id}")
            scenario_run_ids.add(scenario_run_id)
        scenario_inputs.append(metadata)
        by_health_policy[metadata["healthPolicyVersion"]].append(path)

    health_observed: list[dict[str, Any]] = []
    alert_candidates: list[dict[str, Any]] = []
    baseline_alert_policy = alert_policies[0]
    for health_policy_version in sorted(by_health_policy):
        group_paths = by_health_policy[health_policy_version]
        observed = offline_evaluator.build_evaluation(group_paths, baseline_alert_policy)
        labels = Counter()
        for item in scenario_inputs:
            if item["healthPolicyVersion"] == health_policy_version:
                labels.update(item["passedScenarioLabels"])
        health_observed.append(
            {
                "healthPolicyVersion": health_policy_version,
                "datasetRuns": len(group_paths),
                "passedScenarioLabels": dict(sorted(labels.items())),
                "summary": observed["summary"]["health"],
            }
        )
        for policy in alert_policies:
            projected = offline_evaluator.build_evaluation(group_paths, policy)
            alert_candidates.append(
                {
                    "healthPolicyVersion": health_policy_version,
                    "alertPolicy": policy.to_dict(),
                    "datasetRuns": len(group_paths),
                    "summary": projected["summary"]["alertProjection"],
                }
            )

    investigation_inputs: list[dict[str, Any]] = []
    assisted_candidates: list[dict[str, Any]] = []
    if investigation_paths:
        investigation = _unique_paths(
            investigation_paths, "assisted-investigation evidence", MAX_INVESTIGATION_INPUTS
        )
        grouped: dict[tuple[str, str, int, int, int], list[Path]] = defaultdict(list)
        metadata_by_path: dict[Path, dict[str, Any]] = {}
        trial_ids: set[str] = set()
        for path in investigation:
            metadata = _assisted_metadata(path)
            for trial_id in metadata.pop("trialIds"):
                if trial_id in trial_ids:
                    raise CalibrationError(f"duplicate trialId across assisted-investigation evidence: {trial_id}")
                trial_ids.add(trial_id)
            metadata_by_path[path] = metadata
            investigation_inputs.append(metadata)
            budgets = metadata["budgets"]
            key = (
                metadata["provider"],
                metadata["model"],
                budgets["maxToolCalls"],
                budgets["maxRounds"],
                budgets["maxInvestigationDurationMs"],
            )
            grouped[key].append(path)
        for key in sorted(grouped):
            provider, model, max_tool_calls, max_rounds, max_duration = key
            paths = grouped[key]
            try:
                evaluation = assisted_evaluator.build_evaluation(scenarios, paths)
            except assisted_evaluator.AssistedEvaluationError as failure:
                raise CalibrationError(str(failure)) from failure
            assisted_candidates.append(
                {
                    "provider": provider,
                    "model": model,
                    "budgets": {
                        "maxToolCalls": max_tool_calls,
                        "maxRounds": max_rounds,
                        "maxInvestigationDurationMs": max_duration,
                    },
                    "artifactCount": len(paths),
                    "trialCount": sum(metadata_by_path[path]["trialCount"] for path in paths),
                    "summary": evaluation["summary"],
                }
            )

    return {
        "schemaVersion": CALIBRATION_SCHEMA_VERSION,
        "artifactType": CALIBRATION_ARTIFACT_TYPE,
        "generatedAt": utc_now(),
        "inputs": {
            "scenarioEvidence": scenario_inputs,
            "assistedInvestigationEvidence": investigation_inputs,
        },
        "calibration": {
            "mode": "DESCRIPTIVE_REPEATED_EVIDENCE_COMPARISON",
            "selectionMode": "MANUAL_EVIDENCE_REVIEW",
            "notes": [
                "The report never mutates runtime Health, alert, or assisted-investigation configuration.",
                "Observed Health summaries compare repeated datasets by persisted Health policyVersion; alternate Health thresholds require new scenario runs under another versioned policy.",
                "Detector and alert quality rates use only explicit per-scenario evaluationExpectations; legacy or descriptive-only evidence remains visible but cannot influence calibration rates.",
                "Alert candidates are offline projections over the captured Health Snapshots and are compared separately for each observed Health policyVersion.",
                "Assisted-investigation candidates group real/replay trials by provider, model, and recorded runtime budgets; budget changes require new captured trials rather than counterfactual projection.",
                "No candidate is ranked or selected automatically. Production calibration remains an explicit engineering decision backed by repeated scenario evidence.",
            ],
        },
        "healthObserved": health_observed,
        "alertPolicyCandidates": alert_candidates,
        "assistedInvestigationCandidates": assisted_candidates,
    }


def write_calibration(path: Path, report: Mapping[str, Any]) -> None:
    """Writes one stable formatted calibration artifact."""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario_evidence", nargs="+", type=Path, help="Version-1 scenario evidence artifact(s)")
    parser.add_argument(
        "--investigation-evidence",
        nargs="*",
        type=Path,
        default=(),
        help="Optional version-1 assisted-investigation evidence artifact(s)",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--alert-policy-version", default="human-attention-v1")
    parser.add_argument("--degraded-min-consecutive", default="3")
    parser.add_argument("--unhealthy-min-consecutive", default="1")
    parser.add_argument("--healthy-min-consecutive-to-resolve", default="2")
    parser.add_argument("--reopen-cooldown-seconds", default="900")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        policies = _alert_policies(
            args.alert_policy_version,
            _parse_int_values(args.degraded_min_consecutive, "degraded-min-consecutive", minimum=1),
            _parse_int_values(args.unhealthy_min_consecutive, "unhealthy-min-consecutive", minimum=1),
            _parse_int_values(
                args.healthy_min_consecutive_to_resolve,
                "healthy-min-consecutive-to-resolve",
                minimum=1,
            ),
            _parse_int_values(args.reopen_cooldown_seconds, "reopen-cooldown-seconds", minimum=0),
        )
        report = build_calibration(args.scenario_evidence, policies, args.investigation_evidence)
        write_calibration(args.output, report)
        print(args.output)
        return 0
    except (CalibrationError, offline_evaluator.EvaluationError) as failure:
        print(f"ERROR: {failure}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
