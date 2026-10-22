"""Stage init subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from . import core as core_module
from . import promotion as promotion_module
from . import run_evidence as run_evidence_module
from . import runs as runs_module
from . import stage_scope as stage_scope_module
from . import subspec_evidence as subspec_evidence_module

def initialize_stage_run(repo_root: Path, runs_root: Path, policy_path: Path,
                         policy: dict[str, Any], packet: dict[str, Any],
                         context: list[str], run_id: str | None, *,
                         predecessor: dict[str, Any] | None = None) -> Path:
    """Initialize one immutable stage packet, optionally bound to a promoted predecessor."""
    task = (f"Implement stage {packet['stageId']} of the active specification.\n"
            f"Requirements: {', '.join(packet['requirementIds'])}\n"
            f"Feature IDs: {', '.join(packet['featureIds']) or 'none (contributor tooling)'}\n"
            f"Owning areas: {', '.join(packet['owningPaths'])}\n"
            f"Editable paths: {', '.join(packet['editablePaths'])}\n"
            f"Read-only context: {', '.join(context)}\n"
            f"The implementation plan is advisory, not a contract. Explain material deviations.\n"
            f"\nTask:\n{packet['task']}\n\nCompletion criteria:\n"
            + "\n".join(f"- {item}" for item in packet['completionCriteria']))
    run_dir = runs_module.create_run(
        repo_root, runs_root, policy_path, policy, packet["stageId"], packet["specPath"],
        task, policy.get("workerExecution", {}).get("tool", "aider"),
        policy.get("workerExecution", {}).get("defaultModel", "unspecified"),
        packet["editablePaths"], run_id,
    )
    (run_dir / "stage-packet.json").write_text(
        json.dumps(packet, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    manifest = core_module.read_json(run_dir / "manifest.json")
    manifest["stagePacket"] = {
        "sha256": core_module.sha256_file(run_dir / "stage-packet.json"),
        "specPath": packet["specPath"], "planPath": packet["implementationPlanPath"],
        "contextPaths": context, "contextSha256": stage_scope_module.stage_source_evidence(repo_root, context),
        "editablePaths": packet["editablePaths"], "requirementIds": packet["requirementIds"],
        "featureIds": packet["featureIds"], "riskLevel": packet["riskLevel"],
    }
    if predecessor is not None:
        manifest["stageProgression"] = predecessor
    core_module.write_json(run_dir / "manifest.json", manifest)
    return run_dir


def prepare_next_stage(repo_root: Path, runs_root: Path, policy_path: Path,
                       policy: dict[str, Any], *, previous_run_dir: Path,
                       next_packet_path: Path, expected_stages: list[str],
                       feature_branch: str, expected_head: str,
                       run_id: str | None, developer_approved: bool = False) -> Path:
    """Start only the next LOW-risk stage at an explicitly checked-out promoted HEAD.

    This is a one-shot, local Git/evidence transition: worker execution, later
    stage promotion and developer acceptance remain separate explicit actions.
    """
    if not developer_approved:
        raise ValueError("Explicit developer approval of the next stage packet is required")
    if (not isinstance(expected_stages, list) or not 2 <= len(expected_stages) <= subspec_evidence_module.SUBSPEC_MAX_STAGES
            or any(not isinstance(x, str) or not x.strip() or len(x) > 128
                   for x in expected_stages)
            or len(set(expected_stages)) != len(expected_stages)):
        raise ValueError("Next-stage preparation requires a bounded, ordered, unique stage roster")
    if (not promotion_module.PROMOTION_BRANCH_PATTERN.fullmatch(feature_branch)
            or ".." in feature_branch or "//" in feature_branch
            or feature_branch.endswith("/") or feature_branch.endswith(".lock")):
        raise ValueError("Next-stage preparation requires an approved feature/* branch")
    if not re.fullmatch(r"[0-9a-f]{40}", expected_head):
        raise ValueError("Next-stage expected HEAD must be a full commit SHA")
    if (not previous_run_dir.is_absolute() or previous_run_dir.is_symlink()
            or not previous_run_dir.is_dir() or next_packet_path.is_symlink()):
        raise ValueError("Previous evidence and next packet must be ordinary paths")
    if any(child.is_symlink() for child in previous_run_dir.iterdir()):
        raise ValueError("Previous stage evidence contains a symlink")
    packet, context = stage_scope_module.validate_stage_packet(repo_root, core_module.read_json(next_packet_path))
    if packet["stageId"] not in expected_stages or expected_stages.index(packet["stageId"]) == 0:
        raise ValueError("Next stage is not after any predecessor in the declared roster")
    previous_stage = expected_stages[expected_stages.index(packet["stageId"]) - 1]
    previous = core_module.read_json(previous_run_dir / "manifest.json")
    old_packet_file = previous_run_dir / "stage-packet.json"
    intent_file = previous_run_dir / "promotion-intent.json"
    receipt_file = previous_run_dir / "promotion.json"
    review_file = previous_run_dir / "review-attempt.json"
    for source in (old_packet_file, intent_file, receipt_file, review_file,
                   previous_run_dir / "worker.json"):
        if source.is_symlink() or not source.is_file():
            raise ValueError("Previous stage promotion evidence is missing or unsafe")
    runs_module.ensure_run_contract_matches(policy_path, previous_run_dir, previous)
    old_packet = core_module.read_json(old_packet_file)
    contract = previous.get("stagePacket")
    if (previous.get("schemaVersion") != core_module.SCHEMA_VERSION
            or previous.get("runId") != previous_run_dir.name
            or previous.get("stage") != previous_stage
            or previous.get("specPath") != packet["specPath"]
            or not isinstance(contract, dict)
            or contract.get("riskLevel") != "LOW"
            or contract.get("specPath") != packet["specPath"]
            or contract.get("planPath") != packet["implementationPlanPath"]
            or old_packet.get("stageId") != previous_stage
            or old_packet.get("specPath") != packet["specPath"]
            or old_packet.get("implementationPlanPath") != packet["implementationPlanPath"]
            or contract.get("sha256") != core_module.sha256_file(old_packet_file)):
        raise ValueError("Previous stage source or roster identity does not match")
    intent = core_module.read_json(intent_file)
    receipt = core_module.read_json(receipt_file)
    reviewer = core_module.read_json(review_file)
    worker = core_module.read_json(previous_run_dir / "worker.json")
    old_repo = previous.get("repository", {})
    old_summary = run_evidence_module.summarize_run_evidence(previous_run_dir)
    if (old_summary["state"] != core_module.STATE_READY_FOR_MAIN_REVIEW
            or not old_summary["review"]["present"]
            or old_summary["review"]["verdict"] != "PASS"):
        raise ValueError("Previous promoted run no longer has verified review/check evidence")
    if previous.get("policy", {}).get("sha256") != core_module.sha256_file(policy_path):
        raise ValueError("Pipeline policy changed between sequential stages")
    promotion_module._promotion_safe_check_logs(previous_run_dir, policy, worker)
    if (intent != receipt or receipt.get("schemaVersion") != core_module.SCHEMA_VERSION
            or receipt.get("runId") != previous["runId"]
            or receipt.get("stage") != previous_stage
            or receipt.get("featureBranch") != feature_branch
            or receipt.get("stageCommit") != expected_head
            or receipt.get("parentCommit") != old_repo.get("baseCommit")
            or old_repo.get("branch") != feature_branch
            or not re.fullmatch(r"[0-9a-f]{40}", str(receipt.get("parentCommit", "")))
            or receipt.get("workerChangeSha256") != worker.get("changeSha256")
            or receipt.get("reviewAttemptSha256") != core_module.sha256_file(review_file)
            or reviewer.get("status") != "PASS"
            or reviewer.get("workerSha256") != worker.get("changeSha256")
            or worker.get("status") != "PASS"
            or receipt.get("requiresExternalAcceptance") is not True):
        raise ValueError("Previous promotion receipt no longer matches reviewed evidence")
    if (promotion_module._promotion_git(repo_root, "status", "--porcelain")
            or promotion_module._promotion_git(repo_root, "symbolic-ref", "--quiet", "--short", "HEAD") != feature_branch
            or promotion_module._promotion_git(repo_root, "rev-parse", "HEAD") != expected_head
            or promotion_module._promotion_git(repo_root, "rev-parse", "--verify", f"refs/heads/{feature_branch}") != expected_head
            or promotion_module._promotion_git(repo_root, "rev-parse", f"{expected_head}^") != receipt["parentCommit"]):
        raise ValueError("Developer checkout must be clean at the exact promoted feature HEAD")
    provenance = {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "previousRunId": previous["runId"], "previousStage": previous_stage,
        "previousPromotionSha256": core_module.sha256_file(receipt_file),
        "previousStageCommit": expected_head, "featureBranch": feature_branch,
        "expectedStages": list(expected_stages),
        "requiresExternalAcceptance": True,
    }
    return initialize_stage_run(repo_root, runs_root, policy_path, policy, packet,
                                context, run_id, predecessor=provenance)
