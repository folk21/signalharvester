"""Core subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import hashlib
import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1


RUN_ID_GRAMMAR = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


STATE_PREPARED = "PREPARED"


STATE_WORKER_REQUIRED = "WORKER_REQUIRED"


STATE_VERIFYING = "VERIFYING"


STATE_REVIEW_REQUIRED = "REVIEW_REQUIRED"


STATE_BLOCKED = "BLOCKED"


STATE_NEEDS_MAIN_REVIEW = "NEEDS_MAIN_REVIEW"


STATE_READY_FOR_MAIN_REVIEW = "READY_FOR_MAIN_REVIEW"


VALID_REVIEW_VERDICTS = {"PASS", "CHANGES_REQUIRED", "ESCALATE"}


VALID_FINDING_SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"}


VALID_FINDING_STATUSES = {"OPEN", "RESOLVED"}


RISK_ORDER = {"LOW": 1, "MODERATE": 2, "HIGH": 3, "CRITICAL": 4, "UNKNOWN": 5}


PROTECTED_WORKER_CONTROL_PATHS = frozenset({
    "run_checks.sh", "tools/agent-pipeline/pipeline-policy.json",
    "tools/agent-pipeline/run_aider_stage.sh", "tools/agent-pipeline/reviewer-endpoints.json",
    "docs/specs/STATE.json", "docs/specs/state.schema.json",
    "tools/specs/validate_state.py", "tools/agent-pipeline/aider_context.py",
    "tools/agent-pipeline/agent_pipeline_lib/",
})


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
