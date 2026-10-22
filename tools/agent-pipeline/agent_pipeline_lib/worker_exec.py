"""Worker exec subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from . import aider_runtime as aider_runtime_module
from . import core as core_module
from . import git_ops as git_ops_module
from . import stage_scope as stage_scope_module
from . import workspaces as workspaces_module

def build_aider_command(
    policy: dict[str, Any], manifest: dict[str, Any], run_dir: Path,
    *, worktree: Path | None = None,
) -> list[str]:
    """Build the repository-owned one-shot Aider command without embedding API keys."""
    worker_policy = policy.get("workerExecution")
    if not isinstance(worker_policy, dict):
        raise ValueError("workerExecution policy is missing")
    configured_tool = worker_policy.get("tool")
    if manifest.get("worker", {}).get("tool") != configured_tool:
        raise ValueError(f"Run worker tool must be {configured_tool}")
    model = manifest.get("worker", {}).get("model")
    if not isinstance(model, str) or not model or model == "unspecified":
        raise ValueError("Direct worker execution requires an explicit model")
    task_file = str((run_dir / "task.md").resolve())
    replacements = {
        "{model}": model,
        "{task_file}": task_file,
        "{input_history_file}": str((run_dir / "aider.input.history").resolve()),
        "{chat_history_file}": str((run_dir / "aider.chat.history.md").resolve()),
        "{config_file}": str((run_dir / "aider.runtime.conf.yml").resolve()),
    }
    arguments: list[str] = []
    for raw in worker_policy["arguments"]:
        value = raw
        for placeholder, replacement in replacements.items():
            value = value.replace(placeholder, replacement)
        arguments.append(value)
    read_files = ["README.md", "AGENTS.md", "docs/specs/README.md"]
    spec_path = manifest.get("specPath")
    if isinstance(spec_path, str) and spec_path != "none":
        read_files.append(spec_path)
    contract = manifest.get("stagePacket")
    if contract is not None:
        read_files = contract["contextPaths"]
    for read_file in read_files:
        arguments.extend(["--read", read_file])
    if contract is not None:
        editable = [path for path in contract["editablePaths"] if not path.endswith("/")]
    else:
        editable = []
    profile = worker_policy.get("contextProfile")
    if profile is not None:
        if worktree is None:
            raise ValueError("Selective Aider context requires the exact worker worktree")
        functions = aider_runtime_module._aider_context_functions()
        flags, selected, _ = functions["prepare_context_flags"](
            profile, worktree, run_dir, editable
        )
        arguments.extend(flags)
        arguments.extend(selected)
    else:
        # Historical packets keep their exact legacy invocation/evidence semantics.
        arguments.extend(editable)
    return [worker_policy["executable"], *arguments]


def execute_worker(
    repo_root: Path,
    run_dir: Path,
    manifest: dict[str, Any],
    policy: dict[str, Any],
    *,
    attempt: int = 1,
    task_override: Path | None = None,
) -> int:
    """Run one bounded Aider attempt in the isolated worktree and persist result evidence."""
    result_path = run_dir / "worker.json"
    if result_path.exists():
        raise ValueError("Worker execution is already recorded; use the bounded repair command")
    worktree = workspaces_module.execution_root(repo_root, manifest)
    workspace = manifest.get("workspace")
    if not isinstance(workspace, dict) or workspace.get("kind") != "git-worktree":
        raise ValueError("Prepare an isolated worker workspace before running the worker")
    base_commit = workspace.get("baseCommit")
    if not isinstance(base_commit, str) or git_ops_module.run_git(worktree, "rev-parse", "HEAD") != base_commit:
        raise ValueError("Worker worktree HEAD no longer matches the run base commit")
    if attempt == 1 and git_ops_module.run_git(worktree, "status", "--porcelain") != "":
        raise ValueError("Worker worktree must be clean before initial Aider execution")
    before_paths, before_available = git_ops_module.discover_changed_files(worktree, base_commit)
    before_sha256 = workspaces_module.workspace_change_digest(worktree, before_paths) if before_available else None
    if not before_available:
        raise ValueError("Worker source evidence is unavailable before execution")

    worker_policy = policy.get("workerExecution")
    if not isinstance(worker_policy, dict):
        raise ValueError("workerExecution policy is missing")
    aider_runtime_module.prepare_aider_runtime_config(run_dir)
    worker_environment, removed_aider_environment = aider_runtime_module.build_aider_environment()
    preflight = aider_runtime_module.preflight_aider(
        worktree, run_dir, worker_policy, worker_environment, removed_aider_environment
    )
    command = build_aider_command(policy, manifest, run_dir, worktree=worktree)
    if task_override is not None:
        task_flag = command.index("--message-file")
        command[task_flag + 1] = str(task_override.resolve())
    log_dir = run_dir / "logs"
    log_dir.mkdir(exist_ok=True)
    log_path = log_dir / f"worker-attempt-{attempt}.log"
    started_at = core_module.utc_now()
    started = time.monotonic()
    return_code = 127
    startup_error: str | None = None
    with log_path.open("w", encoding="utf-8") as log_handle:
        if preflight["status"] != "PASS":
            startup_error = preflight["failureReason"]
            log_handle.write(f"ERROR: Aider preflight failed: {startup_error}\n")
            print(f"ERROR: Aider preflight failed: {startup_error}", file=sys.stderr)
        else:
            try:
                process = subprocess.Popen(
                    command,
                    cwd=worktree,
                    env=worker_environment,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    bufsize=1,
                )
                assert process.stdout is not None
                try:
                    for line in process.stdout:
                        sys.stdout.write(line)
                        log_handle.write(line)
                finally:
                    process.stdout.close()
                return_code = process.wait()
            except OSError as exc:
                startup_error = str(exc)
                log_handle.write(f"ERROR: failed to start worker: {exc}\n")
                print(f"ERROR: failed to start worker: {exc}", file=sys.stderr)
    duration_ms = round((time.monotonic() - started) * 1000)
    head_commit = git_ops_module.run_git(worktree, "rev-parse", "HEAD")
    changed_files, change_evidence = git_ops_module.discover_changed_files(worktree, base_commit)
    change_sha256 = workspaces_module.workspace_change_digest(worktree, changed_files) if change_evidence else None
    unexpected_commit = head_commit != base_commit
    made_progress = attempt == 1 or change_sha256 != before_sha256
    scope_failure = None
    if manifest.get("stagePacket") is not None:
        try:
            stage_scope_module.enforce_stage_scope(repo_root, manifest, changed_files)
            stage_scope_module.ensure_stage_contract(repo_root, run_dir, manifest)
        except ValueError as exc:
            scope_failure = str(exc)
    passed = (return_code == 0 and not unexpected_commit and change_evidence
              and bool(changed_files) and made_progress and scope_failure is None)
    failure_reason = None
    if scope_failure is not None:
        failure_reason = "worker stage scope/context violation"
    elif not change_evidence:
        failure_reason = "worker source evidence unavailable"
    elif unexpected_commit:
        failure_reason = "unexpected worker commit"
    elif not changed_files:
        failure_reason = "worker produced no source changes"
    elif not made_progress:
        failure_reason = "worker repair made no source changes"
    elif return_code != 0:
        failure_reason = "worker command failed"
    result = {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "attempt": attempt,
        "taskSha256": core_module.sha256_file(task_override) if task_override else manifest["taskSha256"],
        "failureReason": failure_reason,
        "scopeFailure": scope_failure,
        "tool": manifest.get("worker", {}).get("tool"),
        "model": manifest.get("worker", {}).get("model"),
        "command": command,
        "startedAt": started_at,
        "finishedAt": core_module.utc_now(),
        "durationMs": duration_ms,
        "exitCode": return_code,
        "status": "PASS" if passed else "FAIL",
        "startupError": startup_error,
        "preflight": preflight,
        "workspace": workspace,
        "headCommit": head_commit,
        "unexpectedCommit": unexpected_commit,
        "changedFiles": changed_files,
        "changeEvidenceAvailable": change_evidence,
        "changeSha256": change_sha256,
        "log": str(log_path.relative_to(run_dir)),
        "logSha256": core_module.sha256_file(log_path),
    }
    core_module.write_json(result_path, result)
    core_module.write_json(run_dir / f"worker-attempt-{attempt}.json", result)
    return 0 if passed else 1
