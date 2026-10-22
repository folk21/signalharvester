"""Cycle subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from . import checks as checks_module
from . import core as core_module
from . import git_ops as git_ops_module
from . import handoff as handoff_module
from . import repair as repair_module
from . import reviewer_transport as reviewer_transport_module
from . import status as status_module
from . import worker_exec as worker_exec_module
from . import workspaces as workspaces_module

def run_automated_cycle(
    repo_root: Path, run_dir: Path, manifest: dict[str, Any], policy: dict[str, Any],
) -> int:
    """Run one bounded initial worker/check/review/repair cycle without accepting changes."""
    cycle_path = run_dir / "cycle.json"
    if cycle_path.exists():
        raise ValueError("Automatic cycle already attempted; inspect cycle.json before starting another run")
    if (run_dir / "worker.json").exists() or (run_dir / "review.json").exists() or (
        run_dir / "review-attempt.json"
    ).exists() or core_module.read_json(run_dir / "checks.json").get("checks"):
        raise ValueError("Automatic cycle requires a fresh prepared run without execution evidence")
    worktree = workspaces_module.execution_root(repo_root, manifest)
    if manifest.get("workspace", {}).get("kind") != "git-worktree":
        raise ValueError("Prepare an isolated worker worktree before the automatic cycle")
    if git_ops_module.run_git(worktree, "status", "--porcelain") != "":
        raise ValueError("Automatic cycle requires a clean initial worker worktree")

    cycle = {"schemaVersion": core_module.SCHEMA_VERSION, "runId": manifest["runId"],
             "status": "RUNNING", "startedAt": core_module.utc_now(), "finishedAt": None,
             "reasonCode": None, "events": []}

    def event(step: str, attempt: int, outcome: str, reason: str | None = None) -> None:
        cycle["events"].append({"at": core_module.utc_now(), "step": step,
                                "attempt": attempt, "outcome": outcome, "reasonCode": reason})
        core_module.write_json(cycle_path, cycle)

    def stop(reason: str, *, complete: bool = False) -> int:
        cycle["status"] = "READY_FOR_MAIN_REVIEW" if complete else "ESCALATED"
        cycle["reasonCode"] = reason
        cycle["finishedAt"] = core_module.utc_now()
        core_module.write_json(cycle_path, cycle)
        return 0 if complete else 1

    core_module.write_json(cycle_path, cycle)
    attempt = 1
    feedback: str | None = None
    feedback_details: str | None = None
    try:
        while True:
            if attempt == 1:
                worker_exit = worker_exec_module.execute_worker(repo_root, run_dir, manifest, policy)
            else:
                next_attempt, repair_task = repair_module.prepare_repair(
                    repo_root, run_dir, manifest, policy,
                    automated_feedback=feedback, automated_details=feedback_details,
                )
                if next_attempt != attempt:
                    raise ValueError("Repair attempt number is inconsistent")
                event("repair-authorized", attempt, "PASS")
                worker_exit = worker_exec_module.execute_worker(
                    repo_root, run_dir, manifest, policy,
                    attempt=next_attempt, task_override=repair_task,
                )
            worker = core_module.read_json(run_dir / "worker.json")
            event("worker", attempt, worker["status"], worker.get("failureReason"))
            if worker_exit != 0:
                if worker.get("failureReason") != "worker produced no source changes" or worker.get("exitCode") != 0:
                    return stop("WORKER_EXECUTION_FAILURE")
                feedback = None
                feedback_details = None
            else:
                failed_definition: dict[str, Any] | None = None
                for definition in policy["requiredChecks"]:
                    result = checks_module.run_required_check(repo_root, run_dir, manifest, definition, policy)
                    check = repair_module._latest_checks(core_module.read_json(run_dir / "checks.json"))[definition["name"]]
                    event("check:" + definition["name"], attempt, check["status"])
                    if result != 0:
                        failed_definition = check
                        break
                if failed_definition is not None:
                    feedback, feedback_details = repair_module.classify_automatic_check_failure(
                        run_dir, failed_definition, policy)
                    if feedback not in ("TEST_FAILURE", "COMPILATION_FAILURE"):
                        return stop(feedback)
                else:
                    review = reviewer_transport_module.run_independent_review(repo_root, run_dir, manifest, policy)
                    event("review", attempt, review["verdict"])
                    status = status_module.update_status(repo_root, run_dir, manifest, policy)
                    if status["state"] == core_module.STATE_READY_FOR_MAIN_REVIEW:
                        handoff_module.write_handoff(repo_root, run_dir, manifest, policy)
                        event("handoff", attempt, "PASS")
                        return stop("REVIEW_AND_CHECKS_PASSED", complete=True)
                    if review["verdict"] != "CHANGES_REQUIRED":
                        return stop("REVIEW_ESCALATION_OR_BLOCKED")
                    feedback = None
                    feedback_details = None
            if attempt > policy.get("repairExecution", {}).get("maxRepairAttempts", 3):
                return stop("REPAIR_BUDGET_EXHAUSTED")
            # prepare_repair validates LOW risk, source identity, security, and finding categories.
            # It runs only at the beginning of the next iteration.
            attempt += 1
    except (OSError, ValueError, KeyError, TypeError) as exc:
        event("controller", attempt, "FAIL", type(exc).__name__)
        stop("CONTROLLER_OR_POLICY_FAILURE")
        raise
