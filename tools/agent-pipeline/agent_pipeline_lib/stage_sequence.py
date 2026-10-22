"""Stage sequence subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import core as core_module
from . import cycle as cycle_module
from . import git_ops as git_ops_module
from . import promotion as promotion_module
from . import run_evidence as run_evidence_module
from . import runs as runs_module
from . import stage_scope as stage_scope_module
from . import status as status_module
from . import workspaces as workspaces_module

SEQUENCE_MAX_STAGES = 8


def inspect_stage_sequence(repo_root: Path, run_dir: Path, manifest: dict[str, Any],
                           policy_path: Path, policy: dict[str, Any], *,
                           expected_stages: list[str], feature_branch: str,
                           previous_run_dir: Path | None = None) -> dict[str, Any]:
    """Read-only preflight for one stage in a bounded serialized sub-spec roster."""
    if (not isinstance(expected_stages, list)
            or not 1 <= len(expected_stages) <= SEQUENCE_MAX_STAGES
            or any(not isinstance(stage, str) or not stage.strip() or len(stage) > 128
                   for stage in expected_stages)
            or len(set(expected_stages)) != len(expected_stages)):
        raise ValueError("Sequence requires an explicit bounded, unique stage roster")
    if (not promotion_module.PROMOTION_BRANCH_PATTERN.fullmatch(feature_branch)
            or ".." in feature_branch or "//" in feature_branch
            or feature_branch.endswith("/") or feature_branch.endswith(".lock")):
        raise ValueError("Sequence requires an approved feature/* branch")
    if (run_dir.is_symlink() or not run_dir.is_dir()
            or run_dir.name != manifest.get("runId")
            or (run_dir / "stage-packet.json").is_symlink()
            or (run_dir / "task.md").is_symlink()
            or (run_dir / "checks.json").is_symlink()):
        raise ValueError("Sequence run evidence has unsafe paths or mismatched identity")
    stage = manifest.get("stage")
    if stage not in expected_stages:
        raise ValueError("Run stage is not in the approved sequence roster")
    ordinal = expected_stages.index(stage)
    if (manifest.get("state") != core_module.STATE_PREPARED or manifest.get("workspace") is not None
            or any((run_dir / name).exists() for name in (
                "sequence-intent.json", "sequence-result.json", "cycle.json", "worker.json",
                "review.json", "review-attempt.json", "promotion-intent.json", "promotion.json",
            ))):
        raise ValueError("Sequence stage is not fresh; never restart a partially attempted stage")
    checks = run_evidence_module._require_json_object(run_dir / "checks.json").get("checks")
    if checks != []:
        raise ValueError("Sequence stage has prior check evidence")
    runs_module.ensure_run_contract_matches(policy_path, run_dir, manifest)
    stage_scope_module.ensure_stage_contract(repo_root, run_dir, manifest)
    contract = manifest.get("stagePacket")
    packet = run_evidence_module._require_json_object(run_dir / "stage-packet.json")
    if (not isinstance(contract, dict) or contract.get("riskLevel") != "LOW"
            or packet.get("riskLevel") != "LOW" or packet.get("delegation") != "BOUNDED"
            or contract.get("sha256") != core_module.sha256_file(run_dir / "stage-packet.json")
            or manifest.get("specPath") != packet.get("specPath")
            or contract.get("specPath") != packet.get("specPath")
            or contract.get("planPath") != packet.get("implementationPlanPath")
            or packet.get("stageId") != stage):
        raise ValueError("Sequence requires a source-bound BOUNDED LOW-risk stage packet")
    # Re-validate the packet against current repository ownership and sources.
    stage_scope_module.validate_stage_packet(repo_root, packet)
    repository = manifest.get("repository")
    current = git_ops_module.discover_repository_state(repo_root)
    if (not isinstance(repository, dict) or repository.get("gitAvailable") is not True
            or repository.get("branch") != feature_branch
            or current.get("branch") != feature_branch
            or current.get("baseCommit") != repository.get("baseCommit")
            or current.get("clean") is not True
            or promotion_module._promotion_git(repo_root, "rev-parse", "--verify",
                              f"refs/heads/{feature_branch}") != repository.get("baseCommit")):
        raise ValueError("Sequence checkout must be clean at its exact approved feature HEAD")
    progression = manifest.get("stageProgression")
    if ordinal == 0:
        if progression is not None or previous_run_dir is not None:
            raise ValueError("First sequence stage cannot inherit a predecessor")
    else:
        if (not isinstance(progression, dict)
                or progression.get("schemaVersion") != core_module.SCHEMA_VERSION
                or progression.get("expectedStages") != expected_stages
                or progression.get("previousStage") != expected_stages[ordinal - 1]
                or progression.get("featureBranch") != feature_branch
                or progression.get("previousStageCommit") != repository.get("baseCommit")
                or previous_run_dir is None or not previous_run_dir.is_absolute()
                or previous_run_dir.is_symlink() or not previous_run_dir.is_dir()
                or any(child.is_symlink() for child in previous_run_dir.iterdir())):
            raise ValueError("Sequence predecessor identity or roster is missing")
        predecessor = run_evidence_module._require_json_object(previous_run_dir / "manifest.json")
        receipt_file = previous_run_dir / "promotion.json"
        intent_file = previous_run_dir / "promotion-intent.json"
        review_attempt_file = previous_run_dir / "review-attempt.json"
        for source in (receipt_file, intent_file, review_attempt_file):
            if source.is_symlink() or not source.is_file():
                raise ValueError("Sequence predecessor evidence is missing or unsafe")
        receipt = run_evidence_module._require_json_object(receipt_file)
        prior_worker = run_evidence_module._require_json_object(previous_run_dir / "worker.json")
        review_attempt = run_evidence_module._require_json_object(review_attempt_file)
        if (predecessor.get("runId") != progression.get("previousRunId")
                or predecessor.get("stage") != expected_stages[ordinal - 1]
                or predecessor.get("specPath") != packet["specPath"]
                or predecessor.get("repository", {}).get("branch") != feature_branch
                or core_module.sha256_file(receipt_file) != progression.get("previousPromotionSha256")
                or receipt != run_evidence_module._require_json_object(intent_file)
                or receipt.get("runId") != predecessor.get("runId")
                or receipt.get("stageCommit") != progression.get("previousStageCommit")
                or receipt.get("featureBranch") != feature_branch
                or receipt.get("parentCommit") != predecessor.get("repository", {}).get("baseCommit")
                or receipt.get("workerChangeSha256") != prior_worker.get("changeSha256")
                or receipt.get("reviewAttemptSha256") != core_module.sha256_file(review_attempt_file)
                or receipt.get("requiresExternalAcceptance") is not True
                or review_attempt.get("status") != "PASS"
                or review_attempt.get("workerSha256") != prior_worker.get("changeSha256")
                or promotion_module._promotion_git(repo_root, "rev-parse", f"{repository['baseCommit']}^")
                    != receipt.get("parentCommit")):
            raise ValueError("Sequence predecessor receipt or Git ancestry has drifted")
        runs_module.ensure_run_contract_matches(policy_path, previous_run_dir, predecessor)
        previous_summary = run_evidence_module.summarize_run_evidence(previous_run_dir)
        if (previous_summary["state"] != core_module.STATE_READY_FOR_MAIN_REVIEW
                or previous_summary["review"]["verdict"] != "PASS"):
            raise ValueError("Sequence predecessor no longer has review-ready evidence")
        promotion_module._promotion_safe_check_logs(previous_run_dir, policy, prior_worker)
    return {
        "schemaVersion": core_module.SCHEMA_VERSION, "runId": manifest["runId"],
        "stage": stage, "stageIndex": ordinal, "expectedStages": list(expected_stages),
        "featureBranch": feature_branch, "baseCommit": repository["baseCommit"],
        "action": "AWAIT_EXPLICIT_EXECUTION", "requiresExternalAcceptance": True,
    }


def plan_stage_batch(repo_root: Path, run_dir: Path, manifest: dict[str, Any],
                     policy_path: Path, policy: dict[str, Any], *,
                     expected_stages: list[str], feature_branch: str,
                     max_stages: int, max_provider_requests: int,
                     max_cost_cents: int,
                     previous_run_dir: Path | None = None) -> dict[str, Any]:
    """A6.1 read-only envelope; validate only the current prepared stage.

    Future stages cannot be source-bound before predecessor promotion. Budgets
    here are proposed ceilings for a future executor, not enforced allowances.
    """
    for field, value, maximum in (
        ("max_stages", max_stages, SEQUENCE_MAX_STAGES),
        ("max_provider_requests", max_provider_requests, 128),
        ("max_cost_cents", max_cost_cents, 1_000_000),
    ):
        if type(value) is not int or not 1 <= value <= maximum:
            raise ValueError(f"Batch {field} must be a positive bounded integer")

    current = inspect_stage_sequence(
        repo_root, run_dir, manifest, policy_path, policy,
        expected_stages=expected_stages, feature_branch=feature_branch,
        previous_run_dir=previous_run_dir,
    )
    # Stage existence is independent of whether a future stage packet exists.
    spec_path = manifest["specPath"]
    spec_text = (repo_root / spec_path).read_text(encoding="utf-8")
    for stage in expected_stages:
        if not re.search(r"(?<![A-Za-z0-9_.-])" + re.escape(stage) + r"(?![A-Za-z0-9_.-])", spec_text):
            raise ValueError(f"Batch roster contains a stage absent from the active specification: {stage}")

    remaining = expected_stages[current["stageIndex"]:]
    selected = remaining[:max_stages]
    if not selected:
        raise ValueError("Batch requires a current selected stage")
    stages = [
        {"stage": stage, "packetStatus": "CURRENT_SOURCE_BOUND" if offset == 0 else "NOT_PREPARED",
         "executionAuthorized": False}
        for offset, stage in enumerate(selected)
    ]
    return {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "action": "AWAIT_SEPARATE_STAGE_AUTHORIZATION",
        "runId": current["runId"], "featureBranch": current["featureBranch"],
        "baseCommit": current["baseCommit"],
        "stageIndex": current["stageIndex"],
        "expectedStages": list(expected_stages), "selectedStages": stages,
        "budget": {"maxStages": max_stages, "maxProviderRequests": max_provider_requests,
                   "maxCostCents": max_cost_cents, "enforced": False},
        "executionSupported": False,
        "requiresExternalAcceptance": True,
    }


def drive_stage_sequence(repo_root: Path, worktrees_root: Path, run_dir: Path,
                         manifest: dict[str, Any], policy_path: Path,
                         policy: dict[str, Any], *, expected_stages: list[str],
                         feature_branch: str, previous_run_dir: Path | None = None,
                         execute: bool = False, developer_approved: bool = False,
                         _prepare_workspace=None, _run_cycle=None, _update_status=None) -> tuple[dict[str, Any], int]:
    """Optionally run one authorized stage cycle; always stop before promotion."""
    plan = inspect_stage_sequence(
        repo_root, run_dir, manifest, policy_path, policy,
        expected_stages=expected_stages, feature_branch=feature_branch,
        previous_run_dir=previous_run_dir,
    )
    if not execute:
        return plan, 0
    if not developer_approved:
        raise ValueError("Sequence execution requires explicit developer approval")
    intent = {**plan, "action": "EXECUTION_STARTED", "startedAt": core_module.utc_now()}
    core_module.write_json(run_dir / "sequence-intent.json", intent)
    # Any exception or interruption retains the intent: this run cannot be replayed.
    (_prepare_workspace or workspaces_module.prepare_worker_workspace)(
        repo_root, worktrees_root, run_dir, manifest, policy
    )
    exit_code = (_run_cycle or cycle_module.run_automated_cycle)(repo_root, run_dir, manifest, policy)
    state = (_update_status or status_module.update_status)(repo_root, run_dir, manifest, policy)["state"]
    action = ("AWAIT_DEVELOPER_PROMOTION"
              if exit_code == 0 and state == core_module.STATE_READY_FOR_MAIN_REVIEW
              else "ESCALATE_TO_DEVELOPER")
    result = {**plan, "action": action, "completedAt": core_module.utc_now(),
              "cycleExitCode": exit_code, "state": state}
    core_module.write_json(run_dir / "sequence-result.json", result)
    return result, 0 if action == "AWAIT_DEVELOPER_PROMOTION" else 2
