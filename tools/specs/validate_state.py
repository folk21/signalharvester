
#!/usr/bin/env python3
"""Read-only strict processing-state validator; not a workflow/acceptance engine."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "docs/specs/STATE.json"
# The JSON Schema owns structure/regex/enums; Python adds repository semantics.
SCHEMA = json.loads((ROOT / "docs/specs/state.schema.json").read_text(encoding="utf-8"))
ROOT_FIELDS = SCHEMA["properties"]
ITEM_SCHEMA = ROOT_FIELDS["workstreams"]["items"]
ITEM_FIELDS = ITEM_SCHEMA["properties"]
PHASES = set(ITEM_FIELDS["phase"]["enum"])
IDENT = re.compile(ROOT_FIELDS["selectedWorkstreamId"]["pattern"])
STAGE_ID = re.compile(ITEM_FIELDS["stageId"]["pattern"])
RUN_ID = re.compile(ITEM_FIELDS["runId"]["oneOf"][1]["pattern"])
SPEC_PATH = re.compile(ITEM_FIELDS["specPath"]["pattern"])


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
        set(SCHEMA["required"]), "state")
    if type(state["schemaVersion"]) is not int or state["schemaVersion"] != ROOT_FIELDS["schemaVersion"]["const"]:
        raise ValueError("Unsupported state schemaVersion")
    if state["mode"] != ROOT_FIELDS["mode"]["const"]:
        raise ValueError("Only SINGLE processing-state mode is supported")
    selected = state["selectedWorkstreamId"]
    if not isinstance(selected, str) or not IDENT.fullmatch(selected):
        raise ValueError("Invalid selectedWorkstreamId")
    records = state["workstreams"]
    if not isinstance(records, list) or not ROOT_FIELDS["workstreams"]["minItems"] <= len(records) <= ROOT_FIELDS["workstreams"]["maxItems"]:
        raise ValueError("Expected bounded workstreams array")
    ids: set[str] = set()
    specs: set[str] = set()
    for record in records:
        item = strict_object(record, set(ITEM_SCHEMA["required"]), "workstream")
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
        if not isinstance(phase, str) or phase not in PHASES:
            raise ValueError("Unsupported workstream phase")
        run_id = item["runId"]
        if run_id is not None and (not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id)):
            raise ValueError("Invalid runId")
        if phase in {"READY_FOR_REVIEW", "AWAITING_DEVELOPER_DECISION"} and run_id is None:
            raise ValueError("Review/decision checkpoint requires runId")
        if not isinstance(item["developerDecision"], str) or item["developerDecision"] not in ITEM_FIELDS["developerDecision"]["enum"]:
            raise ValueError("Unsupported developerDecision (ACCEPTED requires separate acceptance evidence)")
        if item["developerDecision"] == "REJECTED" and phase not in {"BLOCKED", "PAUSED", "ON_HOLD"}:
            raise ValueError("REJECTED decision requires BLOCKED, PAUSED or ON_HOLD phase")
        if identifier == selected and phase == "ON_HOLD":
            raise ValueError("ON_HOLD workstream cannot be selected")
        if identifier != selected and phase not in {"PAUSED", "BLOCKED", "ON_HOLD"}:
            raise ValueError("SINGLE mode forbids additional progressing workstreams")
    if selected not in ids:
        raise ValueError("Selected workstream is missing")
    # Git-tracked state is updated only at developer-controlled, clean Git checkpoints.
    # Never infer an active worker process or final acceptance from this projection.
    return state


def main() -> int:
    try:
        validate_state()
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"STATE validation FAILED: {exc}", file=sys.stderr)
        return 1
    print("STATE checkpoint validation PASS (SINGLE, structural/semantic only; no runtime or acceptance attestation)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

