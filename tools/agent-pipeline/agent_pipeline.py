#!/usr/bin/env python3
"""Create and verify bounded development-agent runs with machine-readable evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1
STATE_PREPARED = "PREPARED"
STATE_VERIFYING = "VERIFYING"
STATE_REVIEW_REQUIRED = "REVIEW_REQUIRED"
STATE_BLOCKED = "BLOCKED"
STATE_NEEDS_MAIN_REVIEW = "NEEDS_MAIN_REVIEW"
STATE_READY_FOR_MAIN_REVIEW = "READY_FOR_MAIN_REVIEW"

VALID_REVIEW_VERDICTS = {"PASS", "CHANGES_REQUIRED", "ESCALATE"}
VALID_FINDING_SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
VALID_FINDING_STATUSES = {"OPEN", "RESOLVED"}
RISK_ORDER = {"LOW": 1, "MODERATE": 2, "HIGH": 3, "CRITICAL": 4, "UNKNOWN": 5}


def utc_now() -> str:
    """Return a stable UTC timestamp for persisted run evidence."""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def read_json(path: Path) -> dict[str, Any]:
    """Read one UTF-8 JSON object from disk."""
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return data


def write_json(path: Path, data: dict[str, Any]) -> None:
    """Atomically write one formatted UTF-8 JSON object."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temp_path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temp_path, path)


def sha256_file(path: Path) -> str:
    """Return the SHA-256 digest for one file."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_git(repo_root: Path, *args: str) -> str | None:
    """Run a bounded read-only Git query and return trimmed stdout when available."""
    if shutil.which("git") is None:
        return None
    completed = subprocess.run(
        ["git", *args],
        cwd=repo_root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout.strip()


def discover_repository_state(repo_root: Path) -> dict[str, Any]:
    """Capture the Git identity used as the comparison base for a worker run."""
    inside = run_git(repo_root, "rev-parse", "--is-inside-work-tree")
    if inside != "true":
        return {"gitAvailable": False, "baseCommit": None, "branch": None, "clean": None}
    status = run_git(repo_root, "status", "--porcelain")
    return {
        "gitAvailable": True,
        "baseCommit": run_git(repo_root, "rev-parse", "HEAD"),
        "branch": run_git(repo_root, "rev-parse", "--abbrev-ref", "HEAD"),
        "clean": status == "" if status is not None else None,
    }


def validate_policy(policy: dict[str, Any]) -> None:
    """Validate the small policy surface consumed by the foundation tooling."""
    if policy.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"Unsupported policy schemaVersion: {policy.get('schemaVersion')}")
    required_checks = policy.get("requiredChecks")
    if not isinstance(required_checks, list) or not required_checks:
        raise ValueError("Policy must define at least one required check")
    seen: set[str] = set()
    for check in required_checks:
        if not isinstance(check, dict):
            raise ValueError("Each required check must be an object")
        name = check.get("name")
        command = check.get("command")
        if not isinstance(name, str) or not name or name in seen:
            raise ValueError("Required check names must be unique non-empty strings")
        if not isinstance(command, list) or not command or not all(isinstance(part, str) and part for part in command):
            raise ValueError(f"Required check {name} must define a non-empty string command list")
        seen.add(name)


def validate_repository(repo_root: Path, policy: dict[str, Any]) -> None:
    """Fail closed when required repository context is missing."""
    missing = [relative for relative in policy.get("requiredRepositoryFiles", []) if not (repo_root / relative).is_file()]
    if missing:
        raise ValueError(f"Missing required repository files: {', '.join(missing)}")


def validate_active_spec(repo_root: Path, spec_path: str) -> None:
    """Require a declared product specification to point at the active specification tree."""
    if spec_path == "none":
        return
    normalized = Path(spec_path)
    if normalized.is_absolute() or ".." in normalized.parts:
        raise ValueError("Specification path must be repository-relative")
    if normalized.parts[:3] != ("docs", "specs", "active"):
        raise ValueError("Specification path must be under docs/specs/active or be 'none'")
    full_path = repo_root / normalized
    if not full_path.is_file():
        raise ValueError(f"Active specification does not exist: {spec_path}")
    head = "\n".join(full_path.read_text(encoding="utf-8").splitlines()[:20])
    if "spec_status: active" not in head:
        raise ValueError(f"Specification is not marked active: {spec_path}")


def required_check_map(policy: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Index required check definitions by stable name."""
    return {entry["name"]: entry for entry in policy["requiredChecks"]}


def build_worker_task(
    stage: str,
    spec_path: str,
    worker: str,
    model: str,
    task_text: str,
    policy: dict[str, Any],
) -> str:
    """Build the worker packet that keeps repository rules and escalation boundaries explicit."""
    required_reading = ["README.md", "AGENTS.md", "the nearest applicable local AGENTS.md files"]
    if spec_path != "none":
        required_reading.append(spec_path)
    reading_lines = "\n".join(f"- {item}" for item in required_reading)
    escalation_lines = "\n".join(f"- {item}" for item in policy.get("escalationCategories", []))
    check_lines = "\n".join(
        f"- {entry['name']}: {' '.join(entry['command'])}" for entry in policy.get("requiredChecks", [])
    )
    return f"""# Development worker task\n\n## Identity\n\n- Stage: `{stage}`\n- Worker: `{worker}`\n- Model: `{model}`\n- Active specification: `{spec_path}`\n\n## Required repository reading\n\n{reading_lines}\n\nCurrent repository files override chat history, summaries, and prior plans. Read the relevant implementation, tests, contracts, and owning documentation before editing.\n\n## Worker contract\n\n- Make the smallest coherent change that satisfies the task.\n- Follow repository and nearest local `AGENTS.md` rules.\n- Do not weaken tests, validation, security, architecture, or published contracts to make checks pass.\n- Do not declare a stage accepted or archive an active specification. Acceptance remains outside the worker boundary in this foundation stage.\n- Record unresolved ambiguity or high-risk findings instead of guessing.\n- Keep code/configuration comments and software documentation in English.\n\n## Mandatory escalation categories\n\n{escalation_lines}\n\n## Task\n\n{task_text.strip()}\n\n## Required verification before handoff\n\n{check_lines}\n\nAn independent review result is required before the run can become ready for the main-model checkpoint.\n"""


def create_run(
    repo_root: Path,
    runs_root: Path,
    policy_path: Path,
    policy: dict[str, Any],
    stage: str,
    spec_path: str,
    task_text: str,
    worker: str,
    model: str,
    scope_hints: list[str],
    run_id: str | None,
) -> Path:
    """Create an immutable run identity plus the initial worker task packet."""
    validate_repository(repo_root, policy)
    validate_active_spec(repo_root, spec_path)
    if not task_text.strip():
        raise ValueError("Task text must not be empty")
    resolved_run_id = run_id or f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    if not re.fullmatch(r"[A-Za-z0-9._-]+", resolved_run_id):
        raise ValueError("Run id may contain only letters, numbers, dot, underscore, and dash")
    run_dir = runs_root / resolved_run_id
    if run_dir.exists():
        raise ValueError(f"Run already exists: {resolved_run_id}")
    run_dir.mkdir(parents=True)
    repository_state = discover_repository_state(repo_root)
    if repository_state.get("gitAvailable") and repository_state.get("clean") is not True:
        raise ValueError("Git worktree must be clean before initializing an agent-pipeline run")
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "runId": resolved_run_id,
        "stage": stage,
        "state": STATE_PREPARED,
        "createdAt": utc_now(),
        "updatedAt": utc_now(),
        "specPath": spec_path,
        "worker": {"tool": worker, "model": model},
        "scopeHints": scope_hints,
        "repository": repository_state,
        "policy": {
            "path": str(policy_path.relative_to(repo_root)) if policy_path.is_relative_to(repo_root) else str(policy_path),
            "sha256": sha256_file(policy_path),
        },
        "taskSha256": None,
        "requiredChecks": [entry["name"] for entry in policy["requiredChecks"]],
        "mainReviewRequiredBeforeAcceptance": bool(policy.get("mainReview", {}).get("requiredBeforeAcceptance", True)),
        "history": [{"at": utc_now(), "state": STATE_PREPARED, "reason": "run initialized"}],
    }
    task_path = run_dir / "task.md"
    task_path.write_text(build_worker_task(stage, spec_path, worker, model, task_text, policy), encoding="utf-8")
    manifest["taskSha256"] = sha256_file(task_path)
    write_json(run_dir / "manifest.json", manifest)
    write_json(run_dir / "checks.json", {"schemaVersion": SCHEMA_VERSION, "checks": []})
    write_json(run_dir / "reviews.json", {"schemaVersion": SCHEMA_VERSION, "reviews": []})
    return run_dir


def load_run(runs_root: Path, run_id: str) -> tuple[Path, dict[str, Any]]:
    """Load one known run and reject missing or incompatible manifests."""
    run_dir = runs_root / run_id
    manifest_path = run_dir / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"Unknown run: {run_id}")
    manifest = read_json(manifest_path)
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"Unsupported run schemaVersion: {manifest.get('schemaVersion')}")
    return run_dir, manifest


def append_history(manifest: dict[str, Any], state: str, reason: str) -> None:
    """Persist an auditable state transition without deleting prior state evidence."""
    manifest["state"] = state
    manifest["updatedAt"] = utc_now()
    manifest.setdefault("history", []).append({"at": utc_now(), "state": state, "reason": reason})


def execute_check(repo_root: Path, run_dir: Path, name: str, command: list[str], required: bool) -> int:
    """Execute one command, stream output, and persist its exit status and log digest."""
    checks_path = run_dir / "checks.json"
    checks_data = read_json(checks_path)
    log_dir = run_dir / "logs"
    log_dir.mkdir(exist_ok=True)
    safe_name = re.sub(r"[^A-Za-z0-9._-]+", "-", name).strip("-") or "check"
    prior_attempts = [entry for entry in checks_data.get("checks", []) if entry.get("name") == name]
    attempt = len(prior_attempts) + 1
    log_path = log_dir / f"{safe_name}-attempt-{attempt}.log"
    started_at = utc_now()
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            command,
            cwd=repo_root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            log_handle.write(line)
        return_code = process.wait()
    duration_ms = round((time.monotonic() - started) * 1000)
    result = {
        "name": name,
        "required": required,
        "command": command,
        "startedAt": started_at,
        "finishedAt": utc_now(),
        "durationMs": duration_ms,
        "exitCode": return_code,
        "status": "PASS" if return_code == 0 else "FAIL",
        "log": str(log_path.relative_to(run_dir)),
        "logSha256": sha256_file(log_path),
    }
    result["attempt"] = attempt
    checks_data.setdefault("checks", []).append(result)
    write_json(checks_path, checks_data)
    return return_code


def validate_review(review: dict[str, Any]) -> dict[str, Any]:
    """Validate and normalize one independent review payload."""
    if review.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("Review schemaVersion must be 1")
    reviewer = review.get("reviewer")
    if not isinstance(reviewer, str) or not reviewer.strip():
        raise ValueError("Review must identify a non-empty reviewer")
    verdict = review.get("verdict")
    if verdict not in VALID_REVIEW_VERDICTS:
        raise ValueError(f"Unsupported review verdict: {verdict}")
    findings = review.get("findings")
    if not isinstance(findings, list):
        raise ValueError("Review findings must be a list")
    normalized_findings = []
    seen_ids: set[str] = set()
    for finding in findings:
        if not isinstance(finding, dict):
            raise ValueError("Each review finding must be an object")
        finding_id = finding.get("id")
        severity = finding.get("severity")
        category = finding.get("category")
        summary = finding.get("summary")
        status = finding.get("status")
        if not isinstance(finding_id, str) or not finding_id or finding_id in seen_ids:
            raise ValueError("Finding ids must be unique non-empty strings")
        if severity not in VALID_FINDING_SEVERITIES:
            raise ValueError(f"Unsupported finding severity: {severity}")
        if not isinstance(category, str) or not category:
            raise ValueError("Finding category must be a non-empty string")
        if not isinstance(summary, str) or not summary:
            raise ValueError("Finding summary must be a non-empty string")
        if status not in VALID_FINDING_STATUSES:
            raise ValueError(f"Unsupported finding status: {status}")
        seen_ids.add(finding_id)
        normalized_findings.append(
            {
                "id": finding_id,
                "severity": severity,
                "category": category,
                "summary": summary,
                "status": status,
                "file": finding.get("file"),
                "line": finding.get("line"),
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "reviewer": reviewer.strip(),
        "verdict": verdict,
        "recordedAt": utc_now(),
        "findings": normalized_findings,
    }


def discover_changed_files(repo_root: Path, base_commit: str | None) -> tuple[list[str], bool]:
    """Return changed paths since the run base plus uncommitted/untracked paths."""
    if run_git(repo_root, "rev-parse", "--is-inside-work-tree") != "true" or not base_commit:
        return [], False
    commands: list[list[str]] = [
        ["diff", "--name-only", f"{base_commit}...HEAD"],
        ["diff", "--name-only"],
        ["diff", "--cached", "--name-only"],
        ["ls-files", "--others", "--exclude-standard"],
    ]
    paths: set[str] = set()
    for args in commands:
        output = run_git(repo_root, *args)
        if output is None:
            return [], False
        paths.update(line.strip() for line in output.splitlines() if line.strip())
    return sorted(paths), True


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
            if RISK_ORDER.get(rule_level, 0) > RISK_ORDER[level]:
                level = rule_level
    unmatched_paths = [path for path in path_list if path not in matched_paths]
    if unmatched_paths and RISK_ORDER["MODERATE"] > RISK_ORDER[level]:
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
) -> tuple[str, list[str]]:
    """Derive the fail-closed run state only from persisted verification evidence."""
    reasons: list[str] = []
    check_by_name: dict[str, dict[str, Any]] = {}
    for entry in checks_data.get("checks", []):
        if isinstance(entry, dict) and isinstance(entry.get("name"), str):
            check_by_name[entry["name"]] = entry
    failed_latest = [name for name, entry in check_by_name.items() if entry.get("status") == "FAIL"]
    if failed_latest:
        reasons.append(f"latest recorded check failed: {', '.join(sorted(failed_latest))}")
        return STATE_BLOCKED, reasons
    for required in policy.get("requiredChecks", []):
        name = required["name"]
        entry = check_by_name.get(name)
        if entry is None:
            reasons.append(f"required check missing: {name}")
            return STATE_VERIFYING, reasons
        if entry.get("status") != "PASS":
            reasons.append(f"required check failed: {name}")
            return STATE_BLOCKED, reasons

    if policy.get("independentReview", {}).get("required", True):
        if review is None:
            reasons.append("independent review missing")
            return STATE_REVIEW_REQUIRED, reasons
        if review.get("verdict") == "ESCALATE":
            reasons.append("independent review requested escalation")
            return STATE_NEEDS_MAIN_REVIEW, reasons
        blocking_severities = set(policy.get("independentReview", {}).get("blockingSeverities", []))
        blocking_findings = [
            finding
            for finding in review.get("findings", [])
            if finding.get("status") == "OPEN" and finding.get("severity") in blocking_severities
        ]
        if review.get("verdict") == "CHANGES_REQUIRED" or blocking_findings:
            reasons.append("independent review has unresolved blocking findings")
            return STATE_BLOCKED, reasons
        escalation_categories = set(policy.get("escalationCategories", []))
        escalation_findings = [
            finding
            for finding in review.get("findings", [])
            if finding.get("status") == "OPEN" and finding.get("category") in escalation_categories
        ]
        if escalation_findings:
            reasons.append("independent review has an open escalation-category finding")
            return STATE_NEEDS_MAIN_REVIEW, reasons

    reasons.append("required checks and independent review passed")
    return STATE_READY_FOR_MAIN_REVIEW, reasons


def update_status(repo_root: Path, run_dir: Path, manifest: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    """Recompute state and risk evidence, then persist both in the run manifest."""
    checks_data = read_json(run_dir / "checks.json")
    review_path = run_dir / "review.json"
    review = read_json(review_path) if review_path.is_file() else None
    state, reasons = derive_state(policy, checks_data, review)
    changed_files, available = discover_changed_files(repo_root, manifest.get("repository", {}).get("baseCommit"))
    risk = classify_risk(changed_files, policy, available)
    manifest["riskAssessment"] = risk
    append_history(manifest, state, "; ".join(reasons))
    write_json(run_dir / "manifest.json", manifest)
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
        "schemaVersion": SCHEMA_VERSION,
        "runId": manifest["runId"],
        "stage": manifest["stage"],
        "state": state,
        "reasons": reasons,
        "checks": latest_checks,
        "review": {
            "present": review is not None,
            "verdict": None if review is None else review.get("verdict"),
            "openFindingCount": len(open_findings),
        },
        "riskAssessment": risk,
        "mainReviewRequiredBeforeAcceptance": manifest.get("mainReviewRequiredBeforeAcceptance", True),
    }
    write_json(run_dir / "status.json", status)
    return status


def ensure_run_contract_matches(policy_path: Path, run_dir: Path, manifest: dict[str, Any]) -> None:
    """Reject mid-run policy or task drift so the worker contract cannot silently weaken."""
    expected_policy = manifest.get("policy", {}).get("sha256")
    actual_policy = sha256_file(policy_path)
    if expected_policy != actual_policy:
        raise ValueError("Pipeline policy changed since run initialization; start a new run or request main-model review")
    task_path = run_dir / "task.md"
    if not task_path.is_file() or manifest.get("taskSha256") != sha256_file(task_path):
        raise ValueError("Worker task changed since run initialization; start a new run or request main-model review")


def parse_command_tail(parts: list[str]) -> list[str]:
    """Normalize argparse remainder syntax for a command executed without a shell."""
    if parts and parts[0] == "--":
        parts = parts[1:]
    if not parts:
        raise ValueError("A command must be provided after --")
    return parts


def build_parser() -> argparse.ArgumentParser:
    """Build the command-line interface for the foundation workflow."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", help="Repository root, default: current directory")
    parser.add_argument(
        "--policy",
        default="tools/agent-pipeline/pipeline-policy.json",
        help="Repository-relative or absolute pipeline policy path",
    )
    parser.add_argument(
        "--runs-root",
        default="build/reports/agent-pipeline",
        help="Repository-relative or absolute run evidence directory",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    init_parser = subparsers.add_parser("init", help="Initialize one bounded worker run")
    init_parser.add_argument("--stage", required=True)
    init_parser.add_argument("--spec", required=True, help="Active spec path or 'none' for contributor-tooling work")
    init_parser.add_argument("--task-file", required=True)
    init_parser.add_argument("--worker", default="aider")
    init_parser.add_argument("--model", default="unspecified")
    init_parser.add_argument("--scope", action="append", default=[])
    init_parser.add_argument("--run-id")

    check_parser = subparsers.add_parser("run-check", help="Run one policy-defined required check")
    check_parser.add_argument("--run-id", required=True)
    check_parser.add_argument("--name", required=True)

    command_parser = subparsers.add_parser("run-command", help="Run and record one additional focused check")
    command_parser.add_argument("--run-id", required=True)
    command_parser.add_argument("--name", required=True)
    command_parser.add_argument("command_args", nargs=argparse.REMAINDER)

    review_parser = subparsers.add_parser("record-review", help="Record an independent structured review")
    review_parser.add_argument("--run-id", required=True)
    review_parser.add_argument("--input", required=True)

    status_parser = subparsers.add_parser("status", help="Recompute and print one run status")
    status_parser.add_argument("--run-id", required=True)
    return parser


def resolve_under_repo(repo_root: Path, value: str) -> Path:
    """Resolve a path against the repository while preserving explicit absolute paths."""
    path = Path(value)
    return path if path.is_absolute() else repo_root / path


def main(argv: list[str] | None = None) -> int:
    """Execute one agent-pipeline foundation command."""
    parser = build_parser()
    args = parser.parse_args(argv)
    repo_root = Path(args.repo_root).resolve()
    policy_path = resolve_under_repo(repo_root, args.policy).resolve()
    runs_root = resolve_under_repo(repo_root, args.runs_root).resolve()
    try:
        policy = read_json(policy_path)
        validate_policy(policy)
        validate_repository(repo_root, policy)

        if args.command == "init":
            task_path = resolve_under_repo(repo_root, args.task_file)
            task_text = task_path.read_text(encoding="utf-8")
            run_dir = create_run(
                repo_root,
                runs_root,
                policy_path,
                policy,
                args.stage,
                args.spec,
                task_text,
                args.worker,
                args.model,
                args.scope,
                args.run_id,
            )
            print(run_dir)
            return 0

        run_dir, manifest = load_run(runs_root, args.run_id)
        ensure_run_contract_matches(policy_path, run_dir, manifest)

        if args.command == "run-check":
            definitions = required_check_map(policy)
            if args.name not in definitions:
                raise ValueError(f"Unknown required check: {args.name}")
            result = execute_check(repo_root, run_dir, args.name, definitions[args.name]["command"], True)
            update_status(repo_root, run_dir, manifest, policy)
            return result

        if args.command == "run-command":
            if args.name in required_check_map(policy):
                raise ValueError(f"Use run-check for policy-defined check: {args.name}")
            command = parse_command_tail(args.command_args)
            result = execute_check(repo_root, run_dir, args.name, command, False)
            update_status(repo_root, run_dir, manifest, policy)
            return result

        if args.command == "record-review":
            review_input = resolve_under_repo(repo_root, args.input)
            review = validate_review(read_json(review_input))
            reviews_path = run_dir / "reviews.json"
            reviews = read_json(reviews_path)
            reviews.setdefault("reviews", []).append(review)
            write_json(reviews_path, reviews)
            write_json(run_dir / "review.json", review)
            status = update_status(repo_root, run_dir, manifest, policy)
            print(json.dumps(status, indent=2, sort_keys=True))
            return 0

        if args.command == "status":
            status = update_status(repo_root, run_dir, manifest, policy)
            print(json.dumps(status, indent=2, sort_keys=True))
            return 0

        raise ValueError(f"Unsupported command: {args.command}")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
