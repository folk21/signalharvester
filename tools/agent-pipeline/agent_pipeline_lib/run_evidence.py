"""Run evidence subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from . import core as core_module
from . import deviations as deviations_module
from . import evidence_common as evidence_common_module
from . import worker_attempts as worker_attempts_module

_require_json_object = evidence_common_module._require_json_object
_require_positive_int = evidence_common_module._require_positive_int
_validate_worker_attempt_task_digest = evidence_common_module._validate_worker_attempt_task_digest

EVIDENCE_DIGEST_FILES = ("manifest.json", "worker.json", "checks.json", "review.json", "status.json")







def _bounded_check_summary(entry: dict[str, Any]) -> dict[str, Any]:
    """Select compact, non-secret check metadata without commands or logs."""
    return {
        "name": entry.get("name"),
        "status": entry.get("status"),
        "required": entry.get("required"),
        "attempt": entry.get("attempt"),
    }


def summarize_run_evidence(run_dir: Path) -> dict[str, Any]:
    """Build a compact, read-only, digest-linked index of one run's persisted evidence.

    This helper never invokes Git, the network, the worker, the reviewer, or
    ``update_status()``; it only reads persisted JSON evidence. It is a snapshot
    index bound to the current file bytes, not a historical tamper-proof trust
    anchor. It never infers reviewer PASS or developer acceptance.
    """
    manifest = _require_json_object(run_dir / "manifest.json")
    if manifest.get("schemaVersion") != core_module.SCHEMA_VERSION:
        raise ValueError("Unsupported manifest schemaVersion")
    run_id = manifest.get("runId")
    stage = manifest.get("stage")
    if not isinstance(run_id, str) or not run_id:
        raise ValueError("Manifest runId must be a non-empty string")
    if not isinstance(stage, str) or not stage:
        raise ValueError("Manifest stage must be a non-empty string")
    task_sha256 = manifest.get("taskSha256")
    if not isinstance(task_sha256, str) or not task_sha256:
        raise ValueError("Manifest taskSha256 must be a non-empty string")
    policy_sha256 = manifest.get("policy", {}).get("sha256")
    if not isinstance(policy_sha256, str) or not policy_sha256:
        raise ValueError("Manifest policy sha256 must be a non-empty string")
    base_commit = manifest.get("repository", {}).get("baseCommit")
    required_checks = manifest.get("requiredChecks")
    if not isinstance(required_checks, list) or not all(
        isinstance(name, str) and name for name in required_checks
    ) or len(set(required_checks)) != len(required_checks):
        raise ValueError("Manifest requiredChecks must be unique non-empty strings")

    status = _require_json_object(run_dir / "status.json")
    if status.get("runId") != run_id or status.get("stage") != stage:
        raise ValueError("status.json run/stage identity does not match the manifest")
    state = status.get("state")
    if not isinstance(state, str) or not state:
        raise ValueError("status.json state must be a non-empty string")

    checks_data = _require_json_object(run_dir / "checks.json")
    raw_checks = checks_data.get("checks")
    if not isinstance(raw_checks, list):
        raise ValueError("checks.json must contain a checks array")
    latest_checks: dict[str, dict[str, Any]] = {}
    for entry in raw_checks:
        if not isinstance(entry, dict):
            raise ValueError("checks.json contains an invalid check record")
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            raise ValueError("checks.json check name must be a non-empty string")
        _require_positive_int(entry.get("attempt"), f"Check {name} attempt")
        if type(entry.get("required")) is not bool:
            raise ValueError(f"Check {name} required flag must be a boolean")
        latest_checks[name] = entry

    status_checks = status.get("checks")
    if not isinstance(status_checks, dict):
        raise ValueError("status.json must contain a checks object")
    if set(status_checks) != set(latest_checks):
        raise ValueError("status.json and checks.json contain different check names")
    for name, summary in status_checks.items():
        if not isinstance(summary, dict):
            raise ValueError("status.json check summary must be an object")
        latest = latest_checks.get(name)
        if latest is None:
            raise ValueError(f"status.json references an unknown check: {name}")
        if (latest.get("status") != summary.get("status")
                or latest.get("attempt") != summary.get("attempt")
                or latest.get("required") != summary.get("required")):
            raise ValueError(f"status.json check summary disagrees with checks.json: {name}")

    worker_attempts = worker_attempts_module.collect_worker_attempts(run_dir)
    for attempt in worker_attempts:
        _validate_worker_attempt_task_digest(run_dir, manifest, attempt)

    review_path = run_dir / "review.json"
    if review_path.is_file():
        review = _require_json_object(review_path)
        verdict = review.get("verdict")
        if verdict not in core_module.VALID_REVIEW_VERDICTS:
            raise ValueError("review.json verdict is invalid")
        review_summary = {"present": True, "verdict": verdict}
    else:
        review_summary = {"present": False, "verdict": None}

    status_review = status.get("review")
    if not isinstance(status_review, dict):
        raise ValueError("status.json must contain a review summary")
    if (status_review.get("present") is not review_summary["present"]
            or status_review.get("verdict") != review_summary["verdict"]):
        raise ValueError("status.json review summary disagrees with review.json")

    if state == core_module.STATE_READY_FOR_MAIN_REVIEW:
        for name in required_checks:
            entry = latest_checks.get(name)
            if entry is None or entry.get("status") != "PASS" or entry.get("required") is not True:
                raise ValueError("READY_FOR_MAIN_REVIEW requires every required check to pass")
        if not review_summary["present"] or review_summary["verdict"] != "PASS":
            raise ValueError("READY_FOR_MAIN_REVIEW requires an explicit independent review PASS")

    evidence_digests: dict[str, str] = {}
    for name in EVIDENCE_DIGEST_FILES:
        candidate = run_dir / name
        if candidate.is_file():
            evidence_digests[name] = core_module.sha256_file(candidate)

    deviations = deviations_module.collect_deviation_summaries(run_dir)

    return {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "runId": run_id,
        "stage": stage,
        "baseCommit": base_commit,
        "taskSha256": task_sha256,
        "policySha256": policy_sha256,
        "state": state,
        "workerAttempts": worker_attempts,
        "checks": [_bounded_check_summary(latest_checks[name]) for name in sorted(latest_checks)],
        "review": review_summary,
        "deviations": deviations,
        "evidenceDigests": evidence_digests,
        "requiresExternalAcceptance": True,
    }
