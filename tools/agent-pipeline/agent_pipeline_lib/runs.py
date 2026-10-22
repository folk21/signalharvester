"""Runs subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import core as core_module
from . import git_ops as git_ops_module
from . import policy as policy_module

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
        f"- {entry['name']}: "
        + (" ".join(entry["command"]) if entry.get("kind", "command") == "command"
           else "repository-owned Gitleaks worktree scan")
        for entry in policy.get("requiredChecks", [])
    )
    return f"""# Development worker task\n\n## Identity\n\n- Stage: `{stage}`\n- Worker: `{worker}`\n- Model: `{model}`\n- Active specification: `{spec_path}`\n\n## Required repository reading\n\n{reading_lines}\n\nCurrent repository files override chat history, summaries, and prior plans. Read the relevant implementation, tests, contracts, and owning documentation before editing.\n\n## Worker contract\n\n- Make the smallest coherent change that satisfies the task.\n- Follow repository and nearest local `AGENTS.md` rules.\n- Do not weaken tests, validation, security, architecture, or published contracts to make checks pass.\n- Do not declare a stage accepted or archive an active specification. Acceptance remains outside the worker boundary in the current pipeline stage.\n- Record unresolved ambiguity or high-risk findings instead of guessing.\n- Keep code/configuration comments and software documentation in English.\n\n## Mandatory escalation categories\n\n{escalation_lines}\n\n## Task\n\n{task_text.strip()}\n\n## Required verification before handoff\n\n{check_lines}\n\nAn independent review result is required before the run can become ready for the main-model checkpoint.\n"""


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
    policy_module.validate_repository(repo_root, policy)
    policy_module.authorized_worker_model(policy, worker, model)
    policy_module.validate_active_spec(repo_root, spec_path)
    if not task_text.strip():
        raise ValueError("Task text must not be empty")
    resolved_run_id = run_id or f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    if not isinstance(resolved_run_id, str) or not core_module.RUN_ID_GRAMMAR.fullmatch(resolved_run_id):
        raise ValueError("Invalid run id: expected 1-128 ASCII letters/digits/dot/underscore/dash, starting alphanumeric")
    run_dir = runs_root / resolved_run_id
    if run_dir.exists():
        raise ValueError(f"Run already exists: {resolved_run_id}")
    run_dir.mkdir(parents=True)
    repository_state = git_ops_module.discover_repository_state(repo_root)
    if repository_state.get("gitAvailable") and repository_state.get("clean") is not True:
        raise ValueError("Git worktree must be clean before initializing an agent-pipeline run")
    manifest = {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "runId": resolved_run_id,
        "stage": stage,
        "state": core_module.STATE_PREPARED,
        "createdAt": core_module.utc_now(),
        "updatedAt": core_module.utc_now(),
        "specPath": spec_path,
        "worker": {"tool": worker, "model": model},
        "scopeHints": scope_hints,
        "repository": repository_state,
        "policy": {
            "path": str(policy_path.relative_to(repo_root)) if policy_path.is_relative_to(repo_root) else str(policy_path),
            "sha256": core_module.sha256_file(policy_path),
        },
        "taskSha256": None,
        "requiredChecks": [entry["name"] for entry in policy["requiredChecks"]],
        "workerExecutionRequired": bool(policy.get("workerExecution", {}).get("required", False)),
        "mainReviewRequiredBeforeAcceptance": bool(policy.get("mainReview", {}).get("requiredBeforeAcceptance", True)),
        "history": [{"at": core_module.utc_now(), "state": core_module.STATE_PREPARED, "reason": "run initialized"}],
    }
    task_path = run_dir / "task.md"
    task_path.write_text(build_worker_task(stage, spec_path, worker, model, task_text, policy), encoding="utf-8")
    manifest["taskSha256"] = core_module.sha256_file(task_path)
    core_module.write_json(run_dir / "manifest.json", manifest)
    core_module.write_json(run_dir / "checks.json", {"schemaVersion": core_module.SCHEMA_VERSION, "checks": []})
    core_module.write_json(run_dir / "reviews.json", {"schemaVersion": core_module.SCHEMA_VERSION, "reviews": []})
    return run_dir


def load_run(runs_root: Path, run_id: str) -> tuple[Path, dict[str, Any]]:
    """Load one known run and reject missing or incompatible manifests."""
    if not isinstance(run_id, str) or not core_module.RUN_ID_GRAMMAR.fullmatch(run_id):
        raise ValueError("Invalid run identifier")
    run_dir = runs_root / run_id
    manifest_path = run_dir / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"Unknown run: {run_id}")
    manifest = core_module.read_json(manifest_path)
    if manifest.get("schemaVersion") != core_module.SCHEMA_VERSION:
        raise ValueError(f"Unsupported run schemaVersion: {manifest.get('schemaVersion')}")
    return run_dir, manifest


def append_history(manifest: dict[str, Any], state: str, reason: str) -> None:
    """Persist an auditable state transition without deleting prior state evidence."""
    manifest["state"] = state
    manifest["updatedAt"] = core_module.utc_now()
    manifest.setdefault("history", []).append({"at": core_module.utc_now(), "state": state, "reason": reason})


def ensure_run_contract_matches(policy_path: Path, run_dir: Path, manifest: dict[str, Any]) -> None:
    """Reject mid-run policy or task drift so the worker contract cannot silently weaken."""
    expected_policy = manifest.get("policy", {}).get("sha256")
    actual_policy = core_module.sha256_file(policy_path)
    if expected_policy != actual_policy:
        raise ValueError("Pipeline policy changed since run initialization; start a new run or request main-model review")
    task_path = run_dir / "task.md"
    if not task_path.is_file() or manifest.get("taskSha256") != core_module.sha256_file(task_path):
        raise ValueError("Worker task changed since run initialization; start a new run or request main-model review")
