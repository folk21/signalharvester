"""Deviations subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import core as core_module
from . import evidence_common as evidence_common_module
from . import worker_attempts as worker_attempts_module

DEVIATION_FILENAME_PATTERN = re.compile(r"^worker-deviations-attempt-([1-9][0-9]*)\.json$")


DEVIATION_SCHEMA_VERSION = 1


DEVIATION_REPORTED_BY = "WORKER_MODEL_CLAIM"


DEVIATION_STATE_UNREPORTED = "UNREPORTED"


DEVIATION_STATE_REPORTED_NONE = "REPORTED_NONE"


DEVIATION_STATE_REPORTED = "REPORTED"


DEVIATION_KINDS = {"DESIGN_ALTERNATIVE", "TEST_STRATEGY", "FILE_LAYOUT", "OTHER"}


DEVIATION_RISK_IMPACTS = {"NONE_IDENTIFIED", "POTENTIAL", "REQUIRES_DECISION"}


DEVIATION_MAX_RECORDS = 20


DEVIATION_MAX_TEXT = 500


DEVIATION_ID_PATTERN = re.compile(r"^DEV-[0-9]{3}$")


DEVIATION_SHA256_PATTERN = evidence_common_module.DEVIATION_SHA256_PATTERN


DEVIATION_TOP_LEVEL_KEYS = {
    "schemaVersion", "runId", "stage", "attempt", "taskSha256", "changeSha256",
    "reportedBy", "deviations",
}


DEVIATION_ENTRY_KEYS = {
    "id", "kind", "requirementIds", "summary", "rationale", "testImpact",
    "affectedPaths", "riskImpact",
}


_require_sha256 = evidence_common_module._require_sha256


def _require_bounded_text(value: Any, name: str) -> str:
    """Require a non-empty bounded string without leaking raw narrative into compact output."""
    if not isinstance(value, str) or not value.strip() or len(value) > DEVIATION_MAX_TEXT:
        raise ValueError(f"Deviation {name} must be a non-empty bounded string")
    return value



def _normalize_deviation_path(raw: Any) -> str:
    """Validate a lexical repository-relative path without accessing the filesystem."""
    if not isinstance(raw, str) or not raw or raw != raw.strip():
        raise ValueError("Deviation affectedPaths entries must be non-empty strings")
    if raw.startswith("/") or "\\" in raw or "\x00" in raw:
        raise ValueError(f"Unsafe deviation path: {raw}")
    parts = raw.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError(f"Unsafe deviation path: {raw}")
    return raw


def _validate_deviation_payload(
    payload: dict[str, Any],
    *,
    run_dir: Path,
    manifest: dict[str, Any],
    worker: dict[str, Any],
    file_attempt: int,
) -> list[dict[str, Any]]:
    """Validate one explicit worker-deviation record against manifest and worker evidence."""
    if not isinstance(payload, dict) or set(payload) != DEVIATION_TOP_LEVEL_KEYS:
        raise ValueError("Deviation record must have exactly the documented v1 fields")
    if payload.get("schemaVersion") != DEVIATION_SCHEMA_VERSION or type(payload.get("schemaVersion")) is not int:
        raise ValueError("Deviation schemaVersion must be integer 1")
    for name in ("runId", "stage"):
        authoritative = manifest.get(name)
        if not isinstance(authoritative, str) or not authoritative.strip():
            raise ValueError(f"Manifest {name} must be a non-empty string")
        if payload.get(name) != authoritative:
            raise ValueError(f"Deviation {name} does not match the manifest")
    attempt = payload.get("attempt")
    if type(attempt) is not int or attempt != file_attempt:
        raise ValueError("Deviation attempt must be a strict integer matching its filename")
    evidence_common_module._validate_worker_attempt_task_digest(run_dir, manifest, worker)
    if (_require_sha256(payload.get("taskSha256"), "taskSha256")
            != _require_sha256(worker.get("taskSha256"), "worker taskSha256")):
        raise ValueError("Deviation taskSha256 does not match the worker attempt")
    worker_change = _require_sha256(worker.get("changeSha256"), "worker changeSha256")
    if _require_sha256(payload.get("changeSha256"), "changeSha256") != worker_change:
        raise ValueError("Deviation changeSha256 does not match the worker attempt")
    if payload.get("reportedBy") != DEVIATION_REPORTED_BY:
        raise ValueError("Deviation reportedBy must be WORKER_MODEL_CLAIM")
    entries = payload.get("deviations")
    if not isinstance(entries, list) or len(entries) > DEVIATION_MAX_RECORDS:
        raise ValueError("Deviation deviations must be a bounded array")
    stage_packet = manifest.get("stagePacket")
    allowed_requirements: set[str] | None = None
    if stage_packet is not None:
        if not isinstance(stage_packet, dict):
            raise ValueError("Manifest stagePacket must be an object")
        requirement_ids = stage_packet.get("requirementIds")
        if (not isinstance(requirement_ids, list) or
                any(not isinstance(value, str) or not value for value in requirement_ids) or
                len(set(requirement_ids)) != len(requirement_ids)):
            raise ValueError("Manifest stagePacket requirementIds are invalid")
        allowed_requirements = set(requirement_ids)
    changed_files = worker.get("changedFiles")
    if not isinstance(changed_files, list) or not all(isinstance(name, str) for name in changed_files):
        raise ValueError("Worker changedFiles evidence is invalid")
    changed_set = set(changed_files)
    last_id_number = -1
    normalized: list[dict[str, Any]] = []
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != DEVIATION_ENTRY_KEYS:
            raise ValueError("Deviation entry must have exactly the documented v1 fields")
        entry_id = entry.get("id")
        if not isinstance(entry_id, str) or DEVIATION_ID_PATTERN.fullmatch(entry_id) is None:
            raise ValueError("Deviation id must match DEV-[0-9]{3}")
        id_number = int(entry_id[4:])
        if id_number <= last_id_number:
            raise ValueError("Deviation ids must be unique and numerically ascending")
        last_id_number = id_number
        kind = entry.get("kind")
        if not isinstance(kind, str) or kind not in DEVIATION_KINDS:
            raise ValueError(f"Unsupported deviation kind: {kind}")
        risk_impact = entry.get("riskImpact")
        if not isinstance(risk_impact, str) or risk_impact not in DEVIATION_RISK_IMPACTS:
            raise ValueError(f"Unsupported deviation riskImpact: {risk_impact}")
        requirement_ids = entry.get("requirementIds")
        if not isinstance(requirement_ids, list) or not all(
            isinstance(value, str) and value for value in requirement_ids
        ) or len(set(requirement_ids)) != len(requirement_ids):
            raise ValueError("Deviation requirementIds must be unique non-empty strings")
        if allowed_requirements is not None:
            for value in requirement_ids:
                if value not in allowed_requirements:
                    raise ValueError(f"Deviation requirementIds outside stage packet: {value}")
        elif requirement_ids:
            raise ValueError("Deviation requirementIds require an explicit stage packet authority")
        summary = _require_bounded_text(entry.get("summary"), "summary")
        rationale = _require_bounded_text(entry.get("rationale"), "rationale")
        test_impact = _require_bounded_text(entry.get("testImpact"), "testImpact")
        affected_paths = entry.get("affectedPaths")
        if not isinstance(affected_paths, list) or not affected_paths:
            raise ValueError("Deviation affectedPaths must be a non-empty array")
        normalized_paths: list[str] = []
        seen_paths: set[str] = set()
        for raw_path in affected_paths:
            normalized_path = _normalize_deviation_path(raw_path)
            if normalized_path in seen_paths:
                raise ValueError("Deviation affectedPaths must be unique within each record")
            if normalized_path not in changed_set:
                raise ValueError("Deviation affectedPaths references a file not changed by the worker")
            seen_paths.add(normalized_path)
            normalized_paths.append(normalized_path)
        normalized.append({
            "id": entry_id,
            "kind": kind,
            "requirementIds": list(requirement_ids),
            "summary": summary,
            "rationale": rationale,
            "testImpact": test_impact,
            "affectedPaths": normalized_paths,
            "riskImpact": risk_impact,
        })
    return normalized


def _compact_deviation_entry(entry: dict[str, Any]) -> dict[str, Any]:
    """Select only validated categorical metadata for the compact public projection."""
    return {
        "id": entry["id"],
        "kind": entry["kind"],
        "riskImpact": entry["riskImpact"],
    }


def collect_deviation_summaries(run_dir: Path) -> list[dict[str, Any]]:
    """Read optional explicit worker-deviation records as untrusted worker claims.

    This helper never invokes Git, the network, the worker, the reviewer, or
    ``update_status()``; it only reads persisted JSON evidence. It records
    ``WORKER_MODEL_CLAIM`` presence states and never verifies, accepts, or
    authorizes a deviation. A published digest binds the current file bytes,
    not a historical trust anchor.
    """
    manifest = evidence_common_module._require_json_object(run_dir / "manifest.json")
    if manifest.get("schemaVersion") != core_module.SCHEMA_VERSION:
        raise ValueError("Unsupported manifest schemaVersion")
    workers = worker_attempts_module.collect_worker_attempts(run_dir)
    by_attempt: dict[int, dict[str, Any]] = {}
    for item in workers:
        attempt = item.get("attempt")
        if type(attempt) is not int or attempt < 1:
            raise ValueError("Worker attempt identifier is invalid")
        by_attempt[attempt] = item

    files: dict[int, Path] = {}
    for candidate in sorted(run_dir.glob("worker-deviations-attempt-*")):
        match = DEVIATION_FILENAME_PATTERN.fullmatch(candidate.name)
        if match is None or candidate.is_symlink() or not candidate.is_file():
            raise ValueError("Invalid deviation evidence filename or file type")
        number = int(match.group(1))
        if number in files:
            raise ValueError("Duplicate deviation evidence filename")
        if number not in by_attempt:
            raise ValueError("Deviation evidence references an absent worker attempt")
        files[number] = candidate

    result: list[dict[str, Any]] = []
    for attempt in sorted(by_attempt):
        worker = by_attempt[attempt]
        path = files.get(attempt)
        if path is None:
            result.append({"attempt": attempt, "state": DEVIATION_STATE_UNREPORTED})
            continue
        payload = evidence_common_module._require_json_object(path)
        entries = _validate_deviation_payload(
            payload, run_dir=run_dir, manifest=manifest, worker=worker, file_attempt=attempt,
        )
        result.append({
            "attempt": attempt,
            "state": DEVIATION_STATE_REPORTED if entries else DEVIATION_STATE_REPORTED_NONE,
            "source": DEVIATION_REPORTED_BY,
            "count": len(entries),
            "entries": [_compact_deviation_entry(entry) for entry in entries],
            "evidenceFile": path.name,
            "evidenceSha256": core_module.sha256_file(path),
        })
    return result
