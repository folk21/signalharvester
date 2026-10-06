#!/usr/bin/env python3
"""Run a bounded repeated live evidence campaign and build one descriptive calibration report."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"
RESILIENCE_RUNNER = ROOT / "infra" / "kubernetes" / "resilience" / "run_acceptance.py"
CAPACITY_RUNNER = ROOT / "infra" / "kubernetes" / "performance" / "run_baseline.py"
CALIBRATION_RUNNER = EVALUATION / "calibration_report.py"
DEFAULT_OUTPUT_PARENT = ROOT / "build" / "reports" / "operational-intelligence" / "calibration-campaigns"
SCHEMA_VERSION = 1
ARTIFACT_TYPE = "signalharvester-operational-calibration-campaign"
SCENARIO_ARTIFACT_TYPE = "signalharvester-operational-scenario-evidence"
CALIBRATION_ARTIFACT_TYPE = "signalharvester-operational-intelligence-calibration"
MAX_REPEATS = 8
MAX_ARTIFACT_BYTES = 8 * 1024 * 1024


class CampaignError(RuntimeError):
    """Raised when a live calibration campaign cannot complete safely."""


def utc_now() -> str:
    """Returns one UTC timestamp in ISO-8601 form."""

    return datetime.now(timezone.utc).isoformat()


def new_campaign_id() -> str:
    """Returns a filesystem-safe campaign identity."""

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{timestamp}-{uuid.uuid4().hex[:8]}"


def _path_argument(path: Path) -> str:
    resolved = path.resolve()
    try:
        return str(resolved.relative_to(ROOT))
    except ValueError:
        return str(resolved)


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _load_json(path: Path, name: str) -> Mapping[str, Any]:
    try:
        size = path.stat().st_size
        if size > MAX_ARTIFACT_BYTES:
            raise CampaignError(f"{name} artifact {path} exceeds the bounded {MAX_ARTIFACT_BYTES}-byte input limit")
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise CampaignError(f"failed to read {name} artifact {path}: {failure}") from failure
    except json.JSONDecodeError as failure:
        raise CampaignError(f"{name} artifact is not valid JSON: {path}: {failure}") from failure
    if not isinstance(value, dict):
        raise CampaignError(f"{name} artifact must contain one JSON object: {path}")
    return value


def _sha256(path: Path) -> str:
    import hashlib

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _scenario_metadata(path: Path) -> dict[str, Any]:
    payload = _load_json(path, "scenario evidence")
    if payload.get("schemaVersion") != 1 or payload.get("artifactType") != SCENARIO_ARTIFACT_TYPE:
        raise CampaignError(f"unsupported scenario evidence contract: {path}")
    scenarios = payload.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        raise CampaignError(f"scenario evidence contains no scenarios: {path}")
    dataset_run_id = payload.get("datasetRunId")
    if not isinstance(dataset_run_id, str) or not dataset_run_id.strip():
        raise CampaignError(f"scenario evidence has no datasetRunId: {path}")
    passed = sum(1 for item in scenarios if isinstance(item, dict) and item.get("outcome") == "PASSED")
    return {
        "path": _path_argument(path),
        "sha256": _sha256(path),
        "datasetRunId": dataset_run_id,
        "scenarioCount": len(scenarios),
        "passedScenarioCount": passed,
        "allScenariosPassed": passed == len(scenarios),
    }


def _calibration_metadata(path: Path) -> dict[str, Any]:
    payload = _load_json(path, "calibration")
    if payload.get("artifactType") != CALIBRATION_ARTIFACT_TYPE:
        raise CampaignError(f"unsupported calibration artifactType in {path}: {payload.get('artifactType')!r}")
    calibration = payload.get("calibration")
    if not isinstance(calibration, dict) or calibration.get("selectionMode") != "MANUAL_EVIDENCE_REVIEW":
        raise CampaignError(f"calibration report must retain MANUAL_EVIDENCE_REVIEW selection mode: {path}")
    return {
        "path": _path_argument(path),
        "sha256": _sha256(path),
        "selectionMode": "MANUAL_EVIDENCE_REVIEW",
    }


def _execute(command: Sequence[str]) -> int:
    return subprocess.run(list(command), cwd=ROOT, check=False).returncode


def _resilience_command(args: argparse.Namespace, evidence_path: Path) -> list[str]:
    command = [
        sys.executable,
        str(RESILIENCE_RUNNER),
        "--namespace",
        args.namespace,
        "--evidence-output",
        _path_argument(evidence_path),
    ]
    if args.skip_preflight:
        command.append("--skip-preflight")
    return command


def _resilience_repair_command(args: argparse.Namespace) -> list[str]:
    return [
        sys.executable,
        str(RESILIENCE_RUNNER),
        "--namespace",
        args.namespace,
        "--repair-baseline-only",
    ]


def _capacity_command(
    args: argparse.Namespace,
    report_path: Path,
    evidence_path: Path,
) -> list[str]:
    command = [
        sys.executable,
        str(CAPACITY_RUNNER),
        "--namespace",
        args.namespace,
        "--replicas",
        str(args.capacity_replicas),
        "--output",
        _path_argument(report_path),
        "--evidence-output",
        _path_argument(evidence_path),
    ]
    if args.skip_preflight:
        command.append("--skip-preflight")
    return command


def _calibration_command(
    args: argparse.Namespace,
    scenario_paths: Sequence[Path],
    output_path: Path,
) -> list[str]:
    command = [sys.executable, str(CALIBRATION_RUNNER)]
    command.extend(_path_argument(path) for path in scenario_paths)
    command.extend(
        [
            "--output",
            _path_argument(output_path),
            "--alert-policy-version",
            args.alert_policy_version,
            "--degraded-min-consecutive",
            args.degraded_min_consecutive,
            "--unhealthy-min-consecutive",
            args.unhealthy_min_consecutive,
            "--healthy-min-consecutive-to-resolve",
            args.healthy_min_consecutive_to_resolve,
            "--reopen-cooldown-seconds",
            args.reopen_cooldown_seconds,
        ]
    )
    return command


def _campaign_manifest(
    *,
    campaign_id: str,
    args: argparse.Namespace,
    started_at: str,
    status: str,
    runs: Sequence[Mapping[str, Any]],
    scenario_evidence: Sequence[Mapping[str, Any]],
    calibration: Mapping[str, Any] | None,
    failure: str | None = None,
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "artifactType": ARTIFACT_TYPE,
        "campaignId": campaign_id,
        "startedAt": started_at,
        "updatedAt": utc_now(),
        "status": status,
        "configuration": {
            "repeats": args.repeats,
            "includeCapacity": args.include_capacity,
            "capacityReplicas": args.capacity_replicas if args.include_capacity else None,
            "namespace": args.namespace,
            "skipPreflight": args.skip_preflight,
            "healthHistoryResetBetweenRuns": False,
            "alertPolicy": {
                "policyVersion": args.alert_policy_version,
                "degradedMinConsecutiveSnapshots": args.degraded_min_consecutive,
                "unhealthyMinConsecutiveSnapshots": args.unhealthy_min_consecutive,
                "healthyMinConsecutiveSnapshotsToResolve": args.healthy_min_consecutive_to_resolve,
                "reopenCooldownSeconds": args.reopen_cooldown_seconds,
            },
        },
        "runs": list(runs),
        "scenarioEvidence": list(scenario_evidence),
        "calibrationReport": dict(calibration) if calibration is not None else None,
        "failure": failure,
        "notes": [
            "The campaign executes existing live resilience/capacity runners sequentially; it does not mutate Health or alert calibration values.",
            "A failed or inconsistent resilience child triggers the bounded PostgreSQL acceptance-baseline repair path; its outcome is recorded on that run as baselineRecovery.",
            "The campaign does not clear persisted Health history between repetitions; rolling baselines therefore evolve exactly as they do in the live deployment.",
            "Each scenario-evidence artifact keeps its persisted Health policyVersion; comparing another Health policy requires another deployed version and another campaign.",
            "The generated calibration report remains descriptive with selectionMode=MANUAL_EVIDENCE_REVIEW and never selects or writes production configuration.",
            "External/live assisted-investigation trials remain explicit and optional; capture them separately against emitted scenarioRunId/snapshotId identities before comparing provider/model budgets.",
        ],
    }


def run_campaign(
    args: argparse.Namespace,
    *,
    campaign_id: str | None = None,
    execute: Callable[[Sequence[str]], int] = _execute,
) -> Path:
    """Runs repeated live evidence collection and returns the completed manifest path."""

    identifier = campaign_id or new_campaign_id()
    output_dir = args.output_dir
    if output_dir is None:
        output_dir = DEFAULT_OUTPUT_PARENT / identifier
    elif not output_dir.is_absolute():
        output_dir = ROOT / output_dir
    output_dir = output_dir.resolve()
    if output_dir.exists() and not output_dir.is_dir():
        raise CampaignError(f"output path is not a directory: {output_dir}")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise CampaignError(f"output directory must be empty or absent: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)

    manifest_path = output_dir / "campaign-manifest.json"
    calibration_path = output_dir / "calibration-report.json"
    started_at = utc_now()
    runs: list[dict[str, Any]] = []
    evidence_metadata: list[dict[str, Any]] = []
    scenario_paths: list[Path] = []

    def persist(status: str, *, calibration: Mapping[str, Any] | None = None, failure: str | None = None) -> None:
        _write_json(
            manifest_path,
            _campaign_manifest(
                campaign_id=identifier,
                args=args,
                started_at=started_at,
                status=status,
                runs=runs,
                scenario_evidence=evidence_metadata,
                calibration=calibration,
                failure=failure,
            ),
        )

    persist("RUNNING")
    try:
        for index in range(1, args.repeats + 1):
            resilience_evidence = output_dir / f"resilience-run-{index:02d}.json"
            command = _resilience_command(args, resilience_evidence)
            print(f"==> Resilience calibration run {index}/{args.repeats}")
            result = execute(command)
            run_record: dict[str, Any] = {
                "kind": "RESILIENCE",
                "repeat": index,
                "status": "PASSED" if result == 0 else "FAILED",
                "evidencePath": _path_argument(resilience_evidence),
            }
            runs.append(run_record)
            if result != 0:
                if resilience_evidence.exists():
                    try:
                        metadata = _scenario_metadata(resilience_evidence)
                        evidence_metadata.append(metadata)
                        run_record["evidence"] = metadata
                    except CampaignError:
                        pass
                print("==> Restore resilience acceptance baseline after failed live run")
                recovery_result = execute(_resilience_repair_command(args))
                run_record["baselineRecovery"] = {
                    "attempted": True,
                    "status": "PASSED" if recovery_result == 0 else "FAILED",
                    "exitCode": recovery_result,
                }
                if recovery_result != 0:
                    raise CampaignError(
                        f"resilience calibration run {index} failed with exit code {result}; "
                        f"baseline recovery also failed with exit code {recovery_result}"
                    )
                raise CampaignError(f"resilience calibration run {index} failed with exit code {result}")
            metadata = _scenario_metadata(resilience_evidence)
            if not metadata["allScenariosPassed"]:
                print("==> Restore resilience acceptance baseline after inconsistent live evidence")
                recovery_result = execute(_resilience_repair_command(args))
                run_record["baselineRecovery"] = {
                    "attempted": True,
                    "status": "PASSED" if recovery_result == 0 else "FAILED",
                    "exitCode": recovery_result,
                }
                if recovery_result != 0:
                    raise CampaignError(
                        f"resilience calibration run {index} returned success with non-passed evidence; "
                        f"baseline recovery failed with exit code {recovery_result}"
                    )
                raise CampaignError(f"resilience calibration run {index} returned success with non-passed evidence")
            run_record["evidence"] = metadata
            evidence_metadata.append(metadata)
            scenario_paths.append(resilience_evidence)
            persist("RUNNING")

            if args.include_capacity:
                capacity_report = output_dir / f"capacity-run-{index:02d}-report.json"
                capacity_evidence = output_dir / f"capacity-run-{index:02d}.json"
                command = _capacity_command(args, capacity_report, capacity_evidence)
                print(f"==> Capacity calibration run {index}/{args.repeats}")
                result = execute(command)
                capacity_record: dict[str, Any] = {
                    "kind": "CAPACITY",
                    "repeat": index,
                    "status": "PASSED" if result == 0 else "FAILED",
                    "reportPath": _path_argument(capacity_report),
                    "evidencePath": _path_argument(capacity_evidence),
                }
                runs.append(capacity_record)
                if result != 0:
                    if capacity_evidence.exists():
                        try:
                            metadata = _scenario_metadata(capacity_evidence)
                            evidence_metadata.append(metadata)
                            capacity_record["evidence"] = metadata
                        except CampaignError:
                            pass
                    raise CampaignError(f"capacity calibration run {index} failed with exit code {result}")
                metadata = _scenario_metadata(capacity_evidence)
                if not metadata["allScenariosPassed"]:
                    raise CampaignError(f"capacity calibration run {index} returned success with non-passed evidence")
                capacity_record["evidence"] = metadata
                evidence_metadata.append(metadata)
                scenario_paths.append(capacity_evidence)
                persist("RUNNING")

        print("==> Build descriptive repeated-evidence calibration report")
        result = execute(_calibration_command(args, scenario_paths, calibration_path))
        if result != 0:
            raise CampaignError(f"calibration report failed with exit code {result}")
        calibration = _calibration_metadata(calibration_path)
        persist("PASSED", calibration=calibration)
        print(f"    campaign manifest: {manifest_path}")
        print(f"    calibration report: {calibration_path}")
        return manifest_path
    except (CampaignError, OSError, ValueError) as failure:
        persist("FAILED", failure=str(failure))
        raise


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="run_calibration_campaign.py", description=__doc__)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--include-capacity", action="store_true")
    parser.add_argument("--capacity-replicas", type=int, default=1)
    parser.add_argument("--skip-preflight", action="store_true")
    parser.add_argument("--alert-policy-version", default="human-attention-v1")
    parser.add_argument("--degraded-min-consecutive", default="3")
    parser.add_argument("--unhealthy-min-consecutive", default="1")
    parser.add_argument("--healthy-min-consecutive-to-resolve", default="2")
    parser.add_argument("--reopen-cooldown-seconds", default="900")
    args = parser.parse_args(argv)
    if args.repeats < 2 or args.repeats > MAX_REPEATS:
        parser.error(f"--repeats must be between 2 and {MAX_REPEATS}")
    if args.capacity_replicas < 1:
        parser.error("--capacity-replicas must be at least one")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        run_campaign(args)
        return 0
    except CampaignError as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
