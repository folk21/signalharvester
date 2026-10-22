"""Resolve source-bound run workspace locations without changing the filesystem."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from . import git_ops as git_ops_module


def execution_root(repo_root: Path, manifest: dict[str, Any]) -> Path:
    """Return the isolated worktree when a run has one, otherwise the repository root."""
    workspace = manifest.get("workspace")
    if not isinstance(workspace, dict):
        return repo_root
    value = workspace.get("path")
    if not isinstance(value, str) or not value:
        raise ValueError("Worker workspace path is missing from the run manifest")
    path = git_ops_module.resolve_manifest_path(repo_root, value).resolve()
    if not path.exists():
        raise ValueError(f"Worker workspace is missing: {path}")
    return path
