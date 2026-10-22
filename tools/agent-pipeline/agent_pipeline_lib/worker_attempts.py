"""Worker attempts subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import core as core_module

WORKER_ATTEMPT_PATTERN = re.compile(r"^worker-attempt-([1-9][0-9]*)\.json$")


def collect_worker_attempts(run_dir: Path) -> list[dict[str, Any]]:
    """Build a compact, source-bound index of every persisted worker attempt.

    The authoritative latest ``worker.json`` must match its saved snapshot when
    one exists. Snapshots must form a contiguous numeric history starting at 1.
    Malformed, missing, mismatched, or non-contiguous evidence fails closed.
    """
    worker_path = run_dir / "worker.json"
    worker = core_module.read_json(worker_path) if worker_path.exists() else None
    snapshots: dict[int, tuple[str, dict[str, Any]]] = {}
    for entry in sorted(run_dir.glob("worker-attempt-*.json")):
        match = WORKER_ATTEMPT_PATTERN.match(entry.name)
        if match is None:
            raise ValueError(f"Noncanonical worker attempt snapshot filename: {entry.name}")
        attempt = int(match.group(1))
        if attempt in snapshots:
            raise ValueError(f"Duplicate worker attempt snapshot: {entry.name}")
        snapshots[attempt] = (entry.name, core_module.read_json(entry))
    if not snapshots:
        if worker is None:
            return []
        attempt = worker.get("attempt", 1)
        if type(attempt) is not int or attempt != 1:
            raise ValueError("Authoritative worker attempt identifier is invalid")
        return [_worker_attempt_summary(worker, None, None)]
    expected = list(range(1, max(snapshots) + 1))
    if sorted(snapshots) != expected:
        raise ValueError("Worker attempt snapshots are not a contiguous history")
    if worker is None:
        raise ValueError("Authoritative worker.json is missing for saved attempt snapshots")
    latest_attempt = worker.get("attempt")
    if type(latest_attempt) is not int or latest_attempt < 1:
        raise ValueError("Authoritative worker attempt identifier is invalid")
    if latest_attempt != max(snapshots):
        raise ValueError("Authoritative worker attempt does not match the latest snapshot")
    summaries: list[dict[str, Any]] = []
    for attempt in expected:
        filename, snapshot = snapshots[attempt]
        if snapshot.get("attempt") != attempt or type(snapshot.get("attempt")) is not int:
            raise ValueError(f"Worker attempt snapshot {filename} has an inconsistent attempt identifier")
        if attempt == latest_attempt and snapshot != worker:
            raise ValueError("Authoritative worker.json does not match its saved snapshot")
        summaries.append(_worker_attempt_summary(snapshot, filename, core_module.sha256_file(run_dir / filename)))
    return summaries


def _worker_attempt_summary(
    record: dict[str, Any], evidence_file: str | None, evidence_sha256: str | None
) -> dict[str, Any]:
    """Select compact, non-secret worker attempt metadata for the handoff index."""
    summary = {
        "attempt": record.get("attempt", 1),
        "status": record.get("status"),
        "failureReason": record.get("failureReason"),
        "model": record.get("model"),
        "changeSha256": record.get("changeSha256"),
        "changedFiles": record.get("changedFiles"),
        "taskSha256": record.get("taskSha256"),
        "logSha256": record.get("logSha256"),
    }
    if evidence_file is not None:
        summary["evidenceFile"] = evidence_file
        summary["evidenceSha256"] = evidence_sha256
    return summary
