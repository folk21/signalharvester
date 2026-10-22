"""Git ops subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

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


def run_git_mutation(repo_root: Path, *args: str) -> str:
    """Run one explicit Git mutation and fail with bounded stderr on error."""
    if shutil.which("git") is None:
        raise ValueError("git is required for isolated worker execution")
    completed = subprocess.run(
        ["git", *args],
        cwd=repo_root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or f"exit code {completed.returncode}"
        raise ValueError(f"git {' '.join(args)} failed: {detail}")
    return completed.stdout.strip()


def relative_or_absolute(path: Path, repo_root: Path) -> str:
    """Prefer repository-relative paths in persisted evidence when possible."""
    try:
        return str(path.resolve().relative_to(repo_root.resolve()))
    except ValueError:
        return str(path.resolve())


def resolve_manifest_path(repo_root: Path, value: str) -> Path:
    """Resolve one path persisted in a run manifest."""
    path = Path(value)
    return path if path.is_absolute() else repo_root / path


def safe_branch_component(value: str) -> str:
    """Convert a run id to a conservative Git branch-name component."""
    cleaned = re.sub(r"[^A-Za-z0-9_-]+", "-", value).strip("-")
    if not cleaned:
        raise ValueError("Run id does not contain a usable Git branch component")
    return cleaned
