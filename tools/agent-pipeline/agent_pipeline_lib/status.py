"""Status subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Iterable

from . import checks as checks_module
from . import core as core_module
from . import git_ops as git_ops_module
from . import runs as runs_module
from . import workspaces as workspaces_module

def classify_risk(paths: Iterable[str], policy: dict[str, Any], evidence_available: bool) -> dict[str, Any]:
    """Apply conservative path-based escalation hints without treating them as semantic proof."""
    path_list = sorted(set(paths))
    if not evidence_available:
        return {"level": "UNKNOWN", "matchedRules": [], "changedFiles": path_list}
    level = "LOW"
    matched: list[dict[str, Any]] = []
    matched_paths: set[str] = set()
    for rule in policy.get("riskRules", []):
        rule_level = rule.get("level")
        patterns = rule.get("patterns", [])
        rule_paths = [path for path in path_list if any(re.search(pattern, path) for pattern in patterns)]
        if rule_paths:
            matched.append({"level": rule_level, "paths": rule_paths})
            matched_paths.update(rule_paths)
            if core_module.RISK_ORDER.get(rule_level, 0) > core_module.RISK_ORDER[level]:
                level = rule_level
    unmatched_paths = [path for path in path_list if path not in matched_paths]
    if unmatched_paths and core_module.RISK_ORDER["MODERATE"] > core_module.RISK_ORDER[level]:
        level = "MODERATE"
    if not path_list:
        level = "LOW"
    return {
        "level": level,
        "matchedRules": matched,
        "unmatchedChangedFiles": unmatched_paths,
        "changedFiles": path_list,
    }


def derive_state(
    policy: dict[str, Any],
    checks_data: dict[str, Any],
    review: dict[str, Any] | None,
    worker_result: dict[str, Any] | None = None,
) -> tuple[str, list[str]]:
    """Derive the fail-closed run state only from persisted verification evidence."""
    reasons: list[str] = []
    if policy.get("workerExecution", {}).get("required", False):
        if worker_result is None:
            reasons.append("required worker execution missing")
            return core_module.STATE_WORKER_REQUIRED, reasons
        if worker_result.get("status") != "PASS":
            reasons.append("required worker execution failed")
            return core_module.STATE_BLOCKED, reasons

    check_by_name: dict[str, dict[str, Any]] = {}
    for entry in checks_data.get("checks", []):
        if isinstance(entry, dict) and isinstance(entry.get("name"), str):
            check_by_name[entry["name"]] = entry
    failed_latest = [name for name, entry in check_by_name.items() if entry.get("status") == "FAIL"]
    if failed_latest:
        reasons.append(f"latest recorded check failed: {', '.join(sorted(failed_latest))}")
        return core_module.STATE_BLOCKED, reasons
    for required in policy.get("requiredChecks", []):
        name = required["name"]
        entry = check_by_name.get(name)
        if entry is None:
            reasons.append(f"required check missing: {name}")
            return core_module.STATE_VERIFYING, reasons
        if entry.get("status") != "PASS":
            reasons.append(f"required check failed: {name}")
            return core_module.STATE_BLOCKED, reasons

    if policy.get("independentReview", {}).get("required", True):
        if review is None:
            reasons.append("independent review missing")
            return core_module.STATE_REVIEW_REQUIRED, reasons
        if review.get("verdict") == "ESCALATE":
            reasons.append("independent review requested escalation")
            return core_module.STATE_NEEDS_MAIN_REVIEW, reasons
        blocking_severities = set(policy.get("independentReview", {}).get("blockingSeverities", []))
        blocking_findings = [
            finding
            for finding in review.get("findings", [])
            if finding.get("status") == "OPEN" and finding.get("severity") in blocking_severities
        ]
        if review.get("verdict") == "CHANGES_REQUIRED" or blocking_findings:
            reasons.append("independent review has unresolved blocking findings")
            return core_module.STATE_BLOCKED, reasons
        escalation_categories = set(policy.get("escalationCategories", []))
        escalation_findings = [
            finding
            for finding in review.get("findings", [])
            if finding.get("status") == "OPEN" and finding.get("category") in escalation_categories
        ]
        if escalation_findings:
            reasons.append("independent review has an open escalation-category finding")
            return core_module.STATE_NEEDS_MAIN_REVIEW, reasons

    reasons.append("required checks and independent review passed")
    return core_module.STATE_READY_FOR_MAIN_REVIEW, reasons


def update_status(repo_root: Path, run_dir: Path, manifest: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    """Recompute state and risk evidence, then persist both in the run manifest."""
    checks_data = core_module.read_json(run_dir / "checks.json")
    review_path = run_dir / "review.json"
    review = core_module.read_json(review_path) if review_path.is_file() else None
    worker_path = run_dir / "worker.json"
    worker_result = core_module.read_json(worker_path) if worker_path.is_file() else None
    if worker_result is not None and worker_result.get("status") == "PASS":
        worker_result = workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
    checks_module.validate_security_check_evidence(policy, checks_data, worker_result)
    state, reasons = derive_state(policy, checks_data, review, worker_result)
    attempt_path = run_dir / "review-attempt.json"
    if attempt_path.exists():
        attempt = core_module.read_json(attempt_path)
        if attempt.get("status") == "FAIL":
            state, reasons = core_module.STATE_BLOCKED, ["automatic independent review failed"]
        elif attempt.get("status") == "PASS" and (
            review is None or worker_result is None
            or attempt.get("workerSha256") != worker_result.get("changeSha256")
        ):
            raise ValueError("Independent review evidence is inconsistent")
    changed_files, available = git_ops_module.discover_changed_files(
        workspaces_module.execution_root(repo_root, manifest), manifest.get("repository", {}).get("baseCommit")
    )
    risk = classify_risk(changed_files, policy, available)
    manifest["riskAssessment"] = risk
    runs_module.append_history(manifest, state, "; ".join(reasons))
    core_module.write_json(run_dir / "manifest.json", manifest)
    latest_checks: dict[str, dict[str, Any]] = {}
    for entry in checks_data.get("checks", []):
        if isinstance(entry, dict) and isinstance(entry.get("name"), str):
            latest_checks[entry["name"]] = {
                "status": entry.get("status"),
                "attempt": entry.get("attempt"),
                "required": entry.get("required"),
            }
    open_findings = [] if review is None else [
        finding for finding in review.get("findings", []) if finding.get("status") == "OPEN"
    ]
    status = {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "runId": manifest["runId"],
        "stage": manifest["stage"],
        "state": state,
        "reasons": reasons,
        "worker": {
            "required": bool(policy.get("workerExecution", {}).get("required", False)),
            "present": worker_result is not None,
            "status": None if worker_result is None else worker_result.get("status"),
        },
        "checks": latest_checks,
        "review": {
            "present": review is not None,
            "verdict": None if review is None else review.get("verdict"),
            "openFindingCount": len(open_findings),
        },
        "riskAssessment": risk,
        "mainReviewRequiredBeforeAcceptance": manifest.get("mainReviewRequiredBeforeAcceptance", True),
    }
    core_module.write_json(run_dir / "status.json", status)
    return status
