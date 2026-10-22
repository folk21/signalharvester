"""Shared strict readers and digest validation for source-bound evidence."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from . import core as core_module

DEVIATION_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def _require_json_object(path: Path) -> dict[str, Any]:
    """Read one required JSON object, failing closed on malformed or array-shaped evidence."""
    if not path.is_file():
        raise ValueError(f"Required evidence file is missing: {path.name}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"Required evidence file is not a JSON object: {path.name}")
    return data


def _require_positive_int(value: Any, name: str) -> int:
    """Require a strict positive integer, rejecting booleans and non-integers."""
    if type(value) is not int or value < 1:
        raise ValueError(f"{name} must be a positive integer")
    return value


def _require_sha256(value: Any, name: str) -> str:
    """Require a lowercase 64-digit hexadecimal digest."""
    if not isinstance(value, str) or DEVIATION_SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"Deviation {name} must be a lowercase SHA-256 hex digest")
    return value


def _validate_worker_attempt_task_digest(
    run_dir: Path, manifest: dict[str, Any], attempt: dict[str, Any],
) -> None:
    """Bind an attempt to its initial task or the exact bounded repair task bytes.

    Historical same-task attempt fixtures have no repair-task file; preserve that
    read-only legacy representation only when their digest is the manifest digest.
    A repair-task file, when present, must be a regular, in-run, base-task-bound
    artifact and its own digest must match the corresponding worker attempt.
    """
    number = _require_positive_int(attempt.get("attempt"), "Worker attempt")
    base_digest = _require_sha256(manifest.get("taskSha256"), "manifest taskSha256")
    actual_digest = _require_sha256(attempt.get("taskSha256"), "worker taskSha256")
    repair_path = run_dir / f"repair-task-{number}.md"
    if number == 1:
        if actual_digest != base_digest:
            raise ValueError("Worker taskSha256 does not match the manifest")
        return
    if repair_path.is_symlink():
        raise ValueError("Repair task evidence must not be a symlink")
    if not repair_path.exists():
        if actual_digest != base_digest:
            raise ValueError("Worker repair task evidence is missing")
        return
    if not repair_path.is_file() or core_module.sha256_file(repair_path) != actual_digest:
        raise ValueError("Worker repair task digest does not match its evidence file")
    base_path = run_dir / "task.md"
    if (base_path.is_symlink() or not base_path.is_file()
            or core_module.sha256_file(base_path) != base_digest):
        raise ValueError("Repair task base digest does not match the manifest")
    base = base_path.read_bytes()
    repair = repair_path.read_bytes()
    if (not repair.startswith(base + b"\n## Bounded repair round\n\n")
            or not repair.endswith(
                b"\nDo not change scope or weaken checks. Return control on ambiguity.\n"
            )):
        raise ValueError("Repair task does not preserve its bounded base task")
