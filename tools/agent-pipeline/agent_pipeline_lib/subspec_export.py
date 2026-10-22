"""Subspec export subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import os
import re
import shutil
import tempfile
from pathlib import Path
from typing import Any

from . import core as core_module
from . import deviations as deviations_module
from . import run_evidence as run_evidence_module
from . import subspec_evidence as subspec_evidence_module

SUBSPEC_EXPORT_MAX_FILES = 512


SUBSPEC_EXPORT_MAX_FILE_BYTES = 64 * 1024 * 1024


SUBSPEC_EXPORT_MAX_TOTAL_BYTES = 512 * 1024 * 1024


SUBSPEC_EXPORT_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def _subspec_run_entries(run_dir: Path) -> list[tuple[str, Path]]:
    """Enumerate regular run files, including the pipeline-owned logs/ subtree."""
    entries: list[tuple[str, Path]] = []
    for child in sorted(run_dir.iterdir()):
        if child.is_symlink() or not SUBSPEC_EXPORT_FILENAME.fullmatch(child.name):
            raise ValueError("Unsafe private run evidence entry")
        if child.is_dir():
            if child.name != "logs":
                raise ValueError("Unexpected private evidence subdirectory")
            for log in sorted(child.iterdir()):
                if (log.is_symlink() or not log.is_file()
                        or not SUBSPEC_EXPORT_FILENAME.fullmatch(log.name)):
                    raise ValueError("Unsafe private run log entry")
                entries.append((f"logs/{log.name}", log))
        elif child.is_file():
            entries.append((child.name, child))
        else:
            raise ValueError("Private run evidence entry is not a regular file")
    return entries


def _subspec_export_files(runs_root: Path) -> dict[str, str]:
    """Digest bounded stage evidence and logs; reject nonregular entries or growth."""
    hashes: dict[str, str] = {}
    total = 0
    for run_dir in sorted(runs_root.iterdir()):
        if run_dir.is_symlink() or not run_dir.is_dir() or not SUBSPEC_EXPORT_FILENAME.fullmatch(run_dir.name):
            raise ValueError("Unsafe private run evidence directory")
        for relative, file in _subspec_run_entries(run_dir):
            size = file.stat().st_size
            total += size
            if (size > SUBSPEC_EXPORT_MAX_FILE_BYTES
                    or total > SUBSPEC_EXPORT_MAX_TOTAL_BYTES
                    or len(hashes) >= SUBSPEC_EXPORT_MAX_FILES):
                raise ValueError("Private run evidence exceeds bounded export limits")
            hashes[f"runs/{run_dir.name}/{relative}"] = core_module.sha256_file(file)
    return hashes


def _verified_subspec_export(bundle_dir: Path) -> dict[str, Any]:
    """Revalidate digest inventory and A4.3 evidence from a private local export."""
    if bundle_dir.is_symlink() or not bundle_dir.is_dir():
        raise ValueError("Private export directory is missing or symlinked")
    for child in bundle_dir.iterdir():
        if child.is_symlink() or child.name not in ("runs", "review-handoff.json", "bundle-index.json"):
            raise ValueError("Unexpected or linked private export artifact")
    index = run_evidence_module._require_json_object(bundle_dir / "bundle-index.json")
    report = run_evidence_module._require_json_object(bundle_dir / "review-handoff.json")
    if (index.get("schemaVersion") != core_module.SCHEMA_VERSION
            or index.get("kind") != "PRIVATE_SUBSPEC_EVIDENCE"
            or index.get("requiresExternalAcceptance") is not True
            or report.get("schemaVersion") != core_module.SCHEMA_VERSION
            or report.get("kind") != "SUBSPEC_REVIEW_HANDOFF"
            or report.get("requiresExternalAcceptance") is not True
            or report.get("rawEvidenceSensitive") is not True):
        raise ValueError("Invalid external handoff identity or acceptance boundary")
    hashes = index.get("fileSha256")
    if (not isinstance(hashes, dict) or not hashes
            or any(not isinstance(k, str) or not isinstance(v, str)
                   or deviations_module.DEVIATION_SHA256_PATTERN.fullmatch(v) is None
                   for k, v in hashes.items())):
        raise ValueError("Invalid private export digest inventory")
    actual = _subspec_export_files(bundle_dir / "runs")
    if actual != hashes:
        raise ValueError("Private evidence export changed after publication")
    if core_module.sha256_file(bundle_dir / "review-handoff.json") != index.get("reviewHandoffSha256"):
        raise ValueError("Compact external handoff changed after publication")
    selection = index.get("runIds")
    expected_stages = index.get("expectedStages")
    spec = index.get("specPath")
    if (not isinstance(selection, list) or len(selection) == 0
            or len(set(selection)) != len(selection)
            or any(not isinstance(r, str) or not SUBSPEC_EXPORT_FILENAME.fullmatch(r)
                   for r in selection)
            or not isinstance(spec, str) or not isinstance(expected_stages, list)
            or sorted(p.name for p in (bundle_dir / "runs").iterdir()) != sorted(selection)):
        raise ValueError("Invalid private export run selection")
    summary = subspec_evidence_module.summarize_subspec_evidence(
        [bundle_dir / "runs" / run_id for run_id in selection],
        spec_path=spec, expected_stages=expected_stages,
    )
    if (summary != report.get("summary") or
            _compact_subspec_review_details(
                [bundle_dir / "runs" / run_id for run_id in selection]
            ) != report.get("reviewDetails")):
        raise ValueError("Compact handoff disagrees with preserved source evidence")
    return {"schemaVersion": core_module.SCHEMA_VERSION, "status": "PASS",
            "runCount": len(selection), "stageCount": len(expected_stages),
            "requiresExternalAcceptance": True}


def _compact_subspec_review_details(run_dirs: list[Path]) -> list[dict[str, Any]]:
    """Expose bounded finding identities/severity without provider summaries or paths."""
    details: list[dict[str, Any]] = []
    for run_dir in run_dirs:
        status = run_evidence_module._require_json_object(run_dir / "status.json")
        risk = status.get("riskAssessment")
        level = risk.get("level") if isinstance(risk, dict) else None
        if level not in core_module.RISK_ORDER:
            level = "UNKNOWN"
        review_file = run_dir / "review.json"
        findings: list[dict[str, str]] = []
        if review_file.is_file():
            raw = run_evidence_module._require_json_object(review_file).get("findings")
            if not isinstance(raw, list) or len(raw) > 256:
                raise ValueError("Review finding metadata is malformed or unbounded")
            for finding in raw:
                if not isinstance(finding, dict):
                    raise ValueError("Invalid review finding metadata")
                severity = finding.get("severity")
                state = finding.get("status")
                if severity not in core_module.VALID_FINDING_SEVERITIES or state not in core_module.VALID_FINDING_STATUSES:
                    raise ValueError("Unsupported review finding metadata")
                identity = finding.get("id")
                category = finding.get("category")
                # Arbitrary provider text stays only in the private raw evidence.
                finding_id = (identity if isinstance(identity, str)
                              and re.fullmatch(r"[A-Za-z0-9._-]{1,64}", identity)
                              else "UNSAFE_ID_REDACTED")
                category_code = (category if isinstance(category, str)
                                 and re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", category)
                                 else "OTHER")
                findings.append({"id": finding_id, "severity": severity,
                                 "category": category_code, "status": state})
        details.append({"runId": run_dir.name, "riskLevel": level, "findings": findings})
    return details


def export_subspec_handoff(
    run_dirs: list[Path], *, spec_path: str, expected_stages: list[str],
    destination: Path, repo_root: Path,
) -> dict[str, Any]:
    """Create a private, replayable evidence custody copy outside the repository.

    Raw run files can contain credentials, logs or source text: keep the whole
    directory private. Only review-handoff.json is compact and redacted.
    Does not run checks/review, sign evidence, advance Git or confer acceptance.
    """
    summary = subspec_evidence_module.summarize_subspec_evidence(run_dirs, spec_path=spec_path,
                                         expected_stages=expected_stages)
    if (not destination.is_absolute() or destination.exists() or destination.is_symlink()
            or not destination.name or not SUBSPEC_EXPORT_FILENAME.fullmatch(destination.name)):
        raise ValueError("Export requires a new absolute directory with a safe name")
    parent = destination.parent
    if (not parent.is_dir() or parent.is_symlink()
            or repo_root.resolve() == destination.resolve()
            or repo_root.resolve() in destination.resolve().parents):
        raise ValueError("Private export must be outside the repository and build tree")
    staging = Path(tempfile.mkdtemp(prefix=".subspec-export-", dir=parent))
    try:
        raw_root = staging / "runs"
        raw_root.mkdir(mode=0o700)
        total = 0
        file_count = 0
        hashes: dict[str, str] = {}
        for original in run_dirs:
            target = raw_root / original.name
            target.mkdir(mode=0o700)
            for relative, file in _subspec_run_entries(original):
                size = file.stat().st_size
                total += size
                file_count += 1
                if (size > SUBSPEC_EXPORT_MAX_FILE_BYTES
                        or total > SUBSPEC_EXPORT_MAX_TOTAL_BYTES
                        or file_count > SUBSPEC_EXPORT_MAX_FILES):
                    raise ValueError("Source run evidence exceeds bounded export limits")
                copied = target / relative
                if "/" in relative:
                    copied.parent.mkdir(mode=0o700, exist_ok=True)
                shutil.copyfile(file, copied)
                copied.chmod(0o600)
                digest = core_module.sha256_file(file)
                if core_module.sha256_file(copied) != digest:
                    raise ValueError("Source run evidence changed during preservation")
                hashes[f"runs/{original.name}/{relative}"] = digest
        # A second source pass detects additions, removals and changed bytes.
        for original in run_dirs:
            expected = {name for name in hashes if name.startswith(f"runs/{original.name}/")}
            current = {f"runs/{original.name}/{relative}"
                       for relative, _ in _subspec_run_entries(original)}
            if current != expected:
                raise ValueError("Source run evidence file roster changed during preservation")
            for name in expected:
                relative = name.removeprefix(f"runs/{original.name}/")
                file = original / relative
                if file.is_symlink() or not file.is_file() or core_module.sha256_file(file) != hashes[name]:
                    raise ValueError("Source run evidence changed during preservation")
        handoff = {"schemaVersion": core_module.SCHEMA_VERSION, "kind": "SUBSPEC_REVIEW_HANDOFF",
                   "requiresExternalAcceptance": True, "rawEvidenceSensitive": True,
                   "note": "Only the compact summary is shareable after developer inspection; runs/ is private.",
                   "summary": summary,
                   "reviewDetails": _compact_subspec_review_details(run_dirs),
                   "promotionEvidence": "NOT_RECORDED_IN_A4.4"}
        core_module.write_json(staging / "review-handoff.json", handoff)
        index = {"schemaVersion": core_module.SCHEMA_VERSION, "kind": "PRIVATE_SUBSPEC_EVIDENCE",
                 "requiresExternalAcceptance": True, "specPath": spec_path,
                 "expectedStages": list(expected_stages),
                 "runIds": [run_dir.name for run_dir in run_dirs],
                 "fileSha256": hashes,
                 "reviewHandoffSha256": core_module.sha256_file(staging / "review-handoff.json")}
        core_module.write_json(staging / "bundle-index.json", index)
        for file in (staging / "review-handoff.json", staging / "bundle-index.json"):
            file.chmod(0o600)
        _verified_subspec_export(staging)
        if destination.exists() or destination.is_symlink():
            raise ValueError("Export destination already exists")
        os.rename(staging, destination)
        return {"schemaVersion": core_module.SCHEMA_VERSION, "status": "EXPORTED",
                "outputDir": str(destination), "runCount": len(run_dirs),
                "requiresExternalAcceptance": True}
    finally:
        if staging.exists():
            shutil.rmtree(staging)
