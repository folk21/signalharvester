#!/usr/bin/env python3
"""Read-only strict processing-state validator; not a workflow/acceptance engine."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "docs/specs/STATE.json"
PHASES = {
    "DESIGN", "READY_TO_DELEGATE", "RUNNING", "READY_FOR_REVIEW",
    "BLOCKED", "PAUSED", "AWAITING_DEVELOPER_DECISION", "ACCEPTED",
}
IDENT = re.compile(r"[a-z][a-z0-9-]{1,63}\Z")
STAGE_ID = re.compile(r"[A-Z][A-Za-z0-9.]*\Z")
RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{1,127}\Z")
SPEC_PATH = re.compile(r"docs/specs/active/subspecs/[a-z0-9][a-z0-9-]*\.md\Z")


def no_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate key: {key}")
        result[key] = value
    return result


def strict_object(value: object, names: set[str], label: str) -> dict:
    if not isinstance(value, dict) or set(value) != names:
        raise ValueError(f"Invalid {label} fields")
    return value


def validate_state(source: Path = STATE, repo_root: Path = ROOT) -> dict:
    if source.is_symlink() or not source.is_file():
        raise ValueError("STATE.json must be an existing regular file")
    state = strict_object(json.loads(source.read_text(encoding="utf-8"), object_pairs_hook=no_duplicates),
        {"schemaVersion", "mode", "selectedWorkstreamId", "workstreams"}, "state")
    if type(state["schemaVersion"]) is not int or state["schemaVersion"] != 1:
        raise ValueError("Unsupported state schemaVersion")
    if state["mode"] != "SINGLE":
        raise ValueError("Only SINGLE processing-state mode is supported")
    selected = state["selectedWorkstreamId"]
    if not isinstance(selected, str) or not IDENT.fullmatch(selected):
        raise ValueError("Invalid selectedWorkstreamId")
    records = state["workstreams"]
    if not isinstance(records, list) or not 1 <= len(records) <= 32:
        raise ValueError("Expected bounded workstreams array")
    ids: set[str] = set()
    specs: set[str] = set()
    for record in records:
        item = strict_object(record, {"id", "specPath", "stageId", "phase", "runId", "developerDecision"}, "workstream")
        identifier, spec = item["id"], item["specPath"]
        if not isinstance(identifier, str) or not IDENT.fullmatch(identifier) or identifier in ids:
            raise ValueError("Invalid or duplicate workstream ID")
        if not isinstance(spec, str) or not SPEC_PATH.fullmatch(spec) or spec in specs:
            raise ValueError("Invalid or duplicate specPath")
        ids.add(identifier)
        specs.add(spec)
        spec_file = repo_root / spec
        if spec_file.is_symlink() or not spec_file.is_file():
            raise ValueError(f"Missing/unsafe active spec: {spec}")
        stage = item["stageId"]
        if not isinstance(stage, str) or not STAGE_ID.fullmatch(stage):
            raise ValueError("Invalid stageId")
        if not re.search(r"^(?:#{2,4}\s+|-\s+\[[ xX]\]\s+\*\*)" + re.escape(stage) + r"(?:\s|\b|\s*[—–-])", spec_file.read_text(encoding="utf-8"), flags=re.M):
            raise ValueError(f"Stage {stage} not found in {spec}")
        phase = item["phase"]
        if phase not in PHASES:
            raise ValueError("Unsupported workstream phase")
        run_id = item["runId"]
        if run_id is not None and (not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id)):
            raise ValueError("Invalid runId")
        if phase == "RUNNING" and run_id is None:
            raise ValueError("RUNNING requires runId")
        if item["developerDecision"] not in {"UNRECORDED", "ACCEPTED", "REJECTED"}:
            raise ValueError("Invalid developerDecision")
        if (phase == "ACCEPTED") != (item["developerDecision"] == "ACCEPTED"):
            raise ValueError("ACCEPTED phase and developerDecision must agree")
        if item["developerDecision"] == "REJECTED" and phase not in {"BLOCKED", "PAUSED"}:
            raise ValueError("REJECTED decision requires BLOCKED or PAUSED phase")
        if identifier != selected and phase not in {"PAUSED", "BLOCKED", "ACCEPTED"}:
            raise ValueError("SINGLE mode forbids additional progressing workstreams")
    if selected not in ids:
        raise ValueError("Selected workstream is missing")
    return state


def main() -> int:
    try:
        validate_state()
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"STATE validation FAILED: {exc}", file=sys.stderr)
        return 1
    print("STATE validation PASS (SINGLE, non-authoritative)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
