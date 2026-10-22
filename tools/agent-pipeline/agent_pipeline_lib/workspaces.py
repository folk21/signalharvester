"""Workspaces subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
from typing import Any, Iterable

from . import core as core_module
from . import git_ops as git_ops_module
from . import runs as runs_module
from . import run_paths as run_paths_module
from . import stage_scope as stage_scope_module

def prepare_worker_workspace(
    repo_root: Path,
    worktrees_root: Path,
    run_dir: Path,
    manifest: dict[str, Any],
    policy: dict[str, Any],
) -> dict[str, Any]:
    """Create one isolated branch/worktree at the immutable run base revision."""
    worker_policy = policy.get("workerExecution")
    if not isinstance(worker_policy, dict) or not worker_policy.get("required", False):
        raise ValueError("Pipeline policy does not require direct worker execution")
    if manifest.get("workspace") is not None:
        raise ValueError("Worker workspace is already prepared for this run")
    repository = manifest.get("repository", {})
    base_commit = repository.get("baseCommit")
    if repository.get("gitAvailable") is not True or not isinstance(base_commit, str) or not base_commit:
        raise ValueError("Direct worker execution requires a Git-backed run with an explicit base commit")
    current = git_ops_module.discover_repository_state(repo_root)
    if current.get("clean") is not True:
        raise ValueError("Base Git worktree must remain clean before preparing the worker workspace")
    if current.get("baseCommit") != base_commit:
        raise ValueError("Base Git HEAD changed since run initialization; start a new run")

    branch = f"{worker_policy['branchPrefix']}{git_ops_module.safe_branch_component(manifest['runId'])}"
    if git_ops_module.run_git(repo_root, "show-ref", "--verify", f"refs/heads/{branch}") is not None:
        raise ValueError(f"Worker branch already exists: {branch}")
    worktree_path = (worktrees_root / manifest["runId"]).resolve()
    if worktree_path.exists():
        raise ValueError(f"Worker worktree path already exists: {worktree_path}")
    worktree_path.parent.mkdir(parents=True, exist_ok=True)
    git_ops_module.run_git_mutation(repo_root, "worktree", "add", "-b", branch, str(worktree_path), base_commit)

    head = git_ops_module.run_git(worktree_path, "rev-parse", "HEAD")
    status = git_ops_module.run_git(worktree_path, "status", "--porcelain")
    if head != base_commit or status != "":
        raise ValueError("Prepared worker worktree does not match the clean run base commit")
    workspace = {
        "kind": "git-worktree",
        "path": git_ops_module.relative_or_absolute(worktree_path, repo_root),
        "branch": branch,
        "baseCommit": base_commit,
        "preparedAt": core_module.utc_now(),
    }
    manifest["workspace"] = workspace
    runs_module.append_history(manifest, core_module.STATE_WORKER_REQUIRED, "isolated worker worktree prepared")
    core_module.write_json(run_dir / "manifest.json", manifest)
    return workspace


execution_root = run_paths_module.execution_root


def workspace_change_digest(worktree: Path, changed_files: Iterable[str]) -> str:
    """Digest the worker-visible source diff, including untracked files, without persisting file contents."""
    digest = hashlib.sha256()
    for relative in sorted(set(changed_files)):
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        path = worktree / relative
        if path.is_symlink():
            digest.update(b"SYMLINK\0")
            digest.update(os.readlink(path).encode("utf-8"))
        elif path.is_file():
            digest.update(b"FILE\0")
            with path.open("rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
        elif path.exists():
            digest.update(b"OTHER\0")
        else:
            digest.update(b"DELETED\0")
        digest.update(b"\0")
    return digest.hexdigest()


def ensure_worker_workspace_matches_result(
    repo_root: Path, run_dir: Path, manifest: dict[str, Any]
) -> dict[str, Any]:
    """Reject source drift after the one-shot worker result was captured."""
    result_path = run_dir / "worker.json"
    if not result_path.is_file():
        raise ValueError("Required worker execution evidence is missing")
    result = core_module.read_json(result_path)
    if result.get("status") != "PASS":
        raise ValueError("Required worker execution did not pass")
    ensure_source_matches_worker_result(repo_root, run_dir, manifest, result)
    stage_scope_module.enforce_stage_scope(repo_root, manifest, result.get("changedFiles", []))
    stage_scope_module.ensure_stage_contract(repo_root, run_dir, manifest)
    return result


def ensure_source_matches_worker_result(
    repo_root: Path, run_dir: Path, manifest: dict[str, Any], result: dict[str, Any]
) -> None:
    """Protect the last worker attempt against external mutations before a repair."""
    worktree = execution_root(repo_root, manifest)
    base_commit = manifest.get("repository", {}).get("baseCommit")
    head_commit = git_ops_module.run_git(worktree, "rev-parse", "HEAD")
    if head_commit != result.get("headCommit") or head_commit != base_commit:
        raise ValueError("Worker worktree HEAD changed after worker execution")
    changed_files, available = git_ops_module.discover_changed_files(worktree, base_commit)
    if not available:
        raise ValueError("Worker worktree change evidence is unavailable")
    digest = workspace_change_digest(worktree, changed_files)
    if changed_files != result.get("changedFiles") or digest != result.get("changeSha256"):
        raise ValueError("Worker worktree source changed after worker execution; start a new run")
