"""Stage scope subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import core as core_module
from . import policy as policy_module
from . import run_paths as run_paths_module

def safe_stage_path(repo_root: Path, raw: str, *, directory: bool = False) -> str:
    """Validate a repository-relative stage path and reject symlink/escape boundaries."""
    if not isinstance(raw, str) or not raw or raw != raw.strip():
        raise ValueError("Stage paths must be non-empty repository-relative strings")
    is_prefix = raw.endswith("/") if directory else False
    value = raw[:-1] if is_prefix else raw
    parts = value.split("/")
    if (value.startswith("/") or "\\" in value or any(part in ("", ".", "..") for part in parts)
            or parts[0] in (".git", "build") or any("\x00" in part for part in parts)):
        raise ValueError(f"Unsafe stage path: {raw}")
    path = repo_root / value
    root = repo_root.resolve()
    if not path.resolve().is_relative_to(root):
        raise ValueError(f"Stage path escapes repository: {raw}")
    for parent in (path, *path.parents):
        if parent == repo_root or not parent.is_relative_to(repo_root):
            break
        if parent.is_symlink():
            raise ValueError(f"Stage path crosses a symlink: {raw}")
    if directory and is_prefix and not path.is_dir():
        raise ValueError(f"Stage editable directory does not exist: {raw}")
    return value + ("/" if is_prefix else "")


def stage_scope_contains(relative: str, authorized: list[str]) -> bool:
    """Match exact files or explicit directory prefixes, never approximate path prefixes."""
    return any(relative.startswith(value) if value.endswith("/") else relative == value
               for value in authorized)


def stage_context(repo_root: Path, packet: dict[str, Any]) -> list[str]:
    """Resolve readable repository instructions and module contracts without private cross-module reads."""
    paths = ["README.md", "AGENTS.md", "docs/specs/README.md", packet["specPath"]]
    if packet.get("implementationPlanPath"):
        paths.append(packet["implementationPlanPath"])
    paths.extend(packet["contextPaths"])
    owned = packet["owningPaths"]
    for name in owned:
        area = repo_root / name
        for doc in ("README.md", "contract.md"):
            candidate = area / doc
            if candidate.is_file():
                paths.append(f"{name}/{doc}")
    for value in (*packet["editablePaths"], *paths):
        location = (repo_root / value.rstrip("/"))
        for parent in (location, *location.parents):
            if parent == repo_root:
                break
            candidate = parent / "AGENTS.md"
            if candidate.is_file():
                paths.append(candidate.relative_to(repo_root).as_posix())
    resolved: list[str] = []
    for path in paths:
        normal = safe_stage_path(repo_root, path)
        if not (repo_root / normal).is_file():
            raise ValueError(f"Missing required stage context: {normal}")
        segments = normal.split("/")
        if len(segments) >= 3 and segments[0] == "modules":
            module = "/".join(segments[:2])
            if module not in owned:
                is_public_doc = len(segments) == 3 and segments[2] in ("README.md", "contract.md", "AGENTS.md")
                is_public_api = (len(segments) > 6 and segments[2:5] == ["src", "main", "java"]
                                 and "api" in segments[5:-1])
                if not (is_public_doc or is_public_api):
                    raise ValueError(f"Cross-module private context is prohibited: {normal}")
        if normal not in resolved:
            resolved.append(normal)
    return resolved


def validate_stage_packet(repo_root: Path, packet: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    """Validate the bounded MAIN_MODEL-to-WORKER_MODEL delegation contract."""
    keys = {"schemaVersion", "stageId", "specPath", "implementationPlanPath", "requirementIds",
            "featureIds", "owningPaths", "editablePaths", "contextPaths", "riskLevel",
            "delegation", "task", "completionCriteria"}
    if set(packet) != keys or packet.get("schemaVersion") != core_module.SCHEMA_VERSION:
        raise ValueError("Stage packet must have exactly the documented v1 fields")
    if packet["delegation"] != "BOUNDED" or packet["riskLevel"] != "LOW":
        raise ValueError("Only approved bounded LOW-risk stages are supported in A4.1")
    for name in ("stageId", "specPath", "task"):
        if not isinstance(packet[name], str) or not packet[name].strip():
            raise ValueError(f"Stage packet {name} must be non-empty")
    for name in ("requirementIds", "featureIds", "owningPaths", "editablePaths", "contextPaths", "completionCriteria"):
        items = packet[name]
        if (not isinstance(items, list) or (not items and name not in ("featureIds", "contextPaths"))
                or not all(isinstance(x, str) and x.strip() for x in items)
                or len(set(items)) != len(items)):
            raise ValueError(f"Stage packet {name} must contain unique non-empty strings")
    if any(not item.strip() for item in packet["completionCriteria"]):
        raise ValueError("Stage completion criteria cannot be blank")
    policy_module.validate_active_spec(repo_root, packet["specPath"])
    spec_text = (repo_root / packet["specPath"]).read_text(encoding="utf-8")
    for token in (packet["stageId"], *packet["requirementIds"]):
        if not re.search(r"(?<![A-Za-z0-9_.-])" + re.escape(token) + r"(?![A-Za-z0-9_.-])", spec_text):
            raise ValueError(f"Unknown stage/requirement in normative spec: {token}")
    plan = packet["implementationPlanPath"]
    if plan is not None:
        if not isinstance(plan, str) or plan != packet["specPath"].removesuffix(".md") + "-implementation-plan.md":
            raise ValueError("Implementation plan must be the matching advisory companion")
        plan = safe_stage_path(repo_root, plan)
        if not (repo_root / plan).is_file():
            raise ValueError("Declared advisory implementation plan is missing")
    elif (repo_root / (packet["specPath"].removesuffix(".md") + "-implementation-plan.md")).exists():
        raise ValueError("Existing implementation plan cannot be omitted from stage packet")
    owned = [safe_stage_path(repo_root, path) for path in packet["owningPaths"]]
    if any(not (repo_root / value).is_dir() for value in owned):
        raise ValueError("Stage owning paths must identify existing directories")
    editable = [safe_stage_path(repo_root, path, directory=True) for path in packet["editablePaths"]]
    if any(not any(path == owner or path.startswith(owner + "/") for owner in owned)
           for path in editable):
        raise ValueError("Stage editable paths must belong to a declared owning area")
    protected = {"AGENTS.md", "README.md", "docs/specs/README.md", packet["specPath"],
                 "run_checks.sh", "run_rare_checks.sh", "tools/agent-pipeline/pipeline-policy.json",
                 "tools/agent-pipeline/gitleaks.toml"}
    protected.update(core_module.PROTECTED_WORKER_CONTROL_PATHS)
    if plan:
        protected.add(plan)
    if any(
        stage_scope_contains(path, editable)
        or (path.endswith("/") and any(
            item == path[:-1] or item.startswith(path) for item in editable
        ))
        for path in protected
    ):
        raise ValueError("Stage editable scope includes protected rules, spec, or verification files")
    for item in packet["contextPaths"]:
        safe_stage_path(repo_root, item)
    context = stage_context(repo_root, packet)
    if any(stage_scope_contains(path, editable) for path in context):
        raise ValueError("Stage editable paths overlap required read-only context")
    return packet, context


def stage_source_evidence(repo_root: Path, paths: list[str]) -> dict[str, str]:
    """Bind every read-only context file to its exact source bytes."""
    return {value: core_module.sha256_file(repo_root / value) for value in paths}


def ensure_stage_contract(repo_root: Path, run_dir: Path, manifest: dict[str, Any]) -> None:
    """Check packet/context integrity at each command, including the isolated worktree."""
    contract = manifest.get("stagePacket")
    if contract is None:
        return
    packet_path = run_dir / "stage-packet.json"
    if not packet_path.is_file() or core_module.sha256_file(packet_path) != contract["sha256"]:
        raise ValueError("Stage packet evidence changed after initialization")
    for path, digest in contract["contextSha256"].items():
        source = repo_root / path
        if not source.is_file() or core_module.sha256_file(source) != digest:
            raise ValueError(f"Stage context changed since initialization: {path}")
    if manifest.get("workspace"):
        worktree = run_paths_module.execution_root(repo_root, manifest)
        for path, digest in contract["contextSha256"].items():
            source = worktree / path
            if not source.is_file() or core_module.sha256_file(source) != digest or source.is_symlink():
                raise ValueError(f"Worker altered read-only stage context: {path}")


def enforce_stage_scope(repo_root: Path, manifest: dict[str, Any], changes: list[str]) -> None:
    """Fail closed on unauthorized edits, including new files and symlink paths."""
    contract = manifest.get("stagePacket")
    if contract is None:
        return
    for path in changes:
        safe_stage_path(run_paths_module.execution_root(repo_root, manifest), path)
        if not stage_scope_contains(path, contract["editablePaths"]):
            raise ValueError(f"Worker changed path outside stage editable scope: {path}")
