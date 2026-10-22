"""Promotion subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from . import checks as checks_module
from . import core as core_module
from . import reviewer_contract as reviewer_contract_module
from . import run_evidence as run_evidence_module
from . import runs as runs_module
from . import status as status_module
from . import workspaces as workspaces_module

PROMOTION_BRANCH_PATTERN = re.compile(r"feature/[A-Za-z0-9][A-Za-z0-9._/-]{0,100}")


def _promotion_git(root: Path, *args: str, index: Path | None = None) -> str:
    """Run one Git plumbing operation with an isolated environment and bounded failure."""
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    if index is not None:
        env["GIT_INDEX_FILE"] = str(index)
    result = subprocess.run(["git", *args], cwd=root, env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise ValueError(f"Git promotion operation {args[0]} failed (exit {result.returncode})")
    return result.stdout.strip()


def _promotion_safe_check_logs(run_dir: Path, policy: dict[str, Any], worker: dict[str, Any]) -> None:
    """Recheck each latest PASS and its original check log without re-running checks."""
    records = run_evidence_module._require_json_object(run_dir / "checks.json").get("checks")
    if not isinstance(records, list):
        raise ValueError("Stage promotion requires check records")
    latest = {record.get("name"): record for record in records if isinstance(record, dict)}
    for definition in policy["requiredChecks"]:
        record = latest.get(definition["name"])
        if not isinstance(record, dict) or record.get("status") != "PASS" or record.get("required") is not True:
            raise ValueError("Stage promotion requires every policy check PASS")
        if definition.get("kind") == "gitleaks":
            if record.get("scanner", {}).get("sourceSha256") != worker.get("changeSha256"):
                raise ValueError("Security check no longer matches worker bytes")
        else:
            name = record.get("log")
            if (not isinstance(name, str) or not re.fullmatch(r"logs/[A-Za-z0-9._-]+\.log", name)):
                raise ValueError("Check log evidence path is invalid")
            log = run_dir / name
            if log.is_symlink() or not log.is_file() or core_module.sha256_file(log) != record.get("logSha256"):
                raise ValueError("Check log evidence is missing or changed")
            if record.get("exitCode") != 0:
                raise ValueError("Successful check must have exit code zero")


def _promotion_branch_is_checked_out(repo_root: Path, branch: str) -> bool:
    """Detect checked-out refs in every registered worktree before a refs-only CAS."""
    output = _promotion_git(repo_root, "worktree", "list", "--porcelain")
    return f"branch refs/heads/{branch}" in output.splitlines()


def promote_verified_stage(repo_root: Path, run_dir: Path, manifest: dict[str, Any],
                           policy_path: Path, policy: dict[str, Any], *,
                           feature_branch: str, expected_parent: str,
                           developer_approved: bool) -> dict[str, Any]:
    """Promote one LOW-risk verified worker tree with Git ref CAS, never accepting a spec.

    The feature branch must be un-checked-out, so ref movement cannot leave an
    attached developer worktree inconsistent. The next stage is always manual.
    """
    if not developer_approved:
        raise ValueError("Explicit developer stage-promotion approval is required")
    if (not PROMOTION_BRANCH_PATTERN.fullmatch(feature_branch)
            or ".." in feature_branch or "//" in feature_branch
            or feature_branch.endswith("/") or feature_branch.endswith(".lock")):
        raise ValueError("Stage promotion requires an approved feature/* branch")
    if not re.fullmatch(r"[0-9a-f]{40}", expected_parent):
        raise ValueError("Expected stage parent must be an exact full commit SHA")
    if any((run_dir / filename).exists() for filename in ("promotion-intent.json", "promotion.json")):
        raise ValueError("Stage already has a promotion intent or receipt; resolve it manually")
    if manifest.get("stagePacket") is None or manifest["stagePacket"].get("riskLevel") != "LOW":
        raise ValueError("Only source-bound LOW-risk stages can be promoted")
    repo = manifest.get("repository", {})
    if (repo.get("branch") != feature_branch or repo.get("baseCommit") != expected_parent
            or repo.get("gitAvailable") is not True):
        raise ValueError("Run branch/base is not the approved expected parent")
    if _promotion_git(repo_root, "status", "--porcelain"):
        raise ValueError("Developer worktree must be clean for stage promotion")
    if _promotion_git(repo_root, "rev-parse", "HEAD") != expected_parent:
        raise ValueError("Developer checkout must point at the immutable run base")
    ref = f"refs/heads/{feature_branch}"
    if _promotion_git(repo_root, "rev-parse", "--verify", ref) != expected_parent:
        raise ValueError("Feature branch moved after run initialization")
    if _promotion_branch_is_checked_out(repo_root, feature_branch):
        raise ValueError("Feature branch is checked out; switch all worktrees away before promotion")
    runs_module.ensure_run_contract_matches(policy_path, run_dir, manifest)
    worker = workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
    if not worker.get("changedFiles") or not isinstance(worker.get("changedFiles"), list):
        raise ValueError("Cannot promote an empty or malformed worker change")
    worker_log_name = worker.get("log")
    if (not isinstance(worker_log_name, str) or not re.fullmatch(
            r"logs/worker-attempt-[1-9][0-9]*\.log", worker_log_name)):
        raise ValueError("Worker log provenance is required for stage promotion")
    worker_log = run_dir / worker_log_name
    if (worker_log.is_symlink() or not worker_log.is_file()
            or core_module.sha256_file(worker_log) != worker.get("logSha256")):
        raise ValueError("Worker log evidence changed before stage promotion")
    checks = run_evidence_module._require_json_object(run_dir / "checks.json")
    review = reviewer_contract_module.validate_review(run_evidence_module._require_json_object(run_dir / "review.json"))
    checks_module.validate_security_check_evidence(policy, checks, worker)
    _promotion_safe_check_logs(run_dir, policy, worker)
    if status_module.derive_state(policy, checks, review, worker)[0] != core_module.STATE_READY_FOR_MAIN_REVIEW:
        raise ValueError("Stage no longer satisfies checks and independent review")
    report = run_evidence_module.summarize_run_evidence(run_dir)
    if report["state"] != core_module.STATE_READY_FOR_MAIN_REVIEW or report["review"]["verdict"] != "PASS":
        raise ValueError("Persisted run is not READY_FOR_MAIN_REVIEW")
    reviewer = run_evidence_module._require_json_object(run_dir / "review-attempt.json")
    if reviewer.get("status") != "PASS" or reviewer.get("workerSha256") != worker["changeSha256"]:
        raise ValueError("Independent provider review must bind the exact worker bytes")
    worktree = workspaces_module.execution_root(repo_root, manifest)
    actual_changed = worker["changedFiles"]
    if (not all(isinstance(path, str) for path in actual_changed)
            or any((worktree / path).is_symlink() for path in actual_changed)):
        raise ValueError("Promoted files must be ordinary source files or deletions")
    risk = status_module.classify_risk(actual_changed, policy, True)
    if risk["level"] != "LOW":
        raise ValueError("Non-LOW stage promotion requires external checkpoint")
    if _promotion_git(worktree, "rev-parse", "HEAD") != expected_parent:
        raise ValueError("Worker source base changed")

    with tempfile.TemporaryDirectory(prefix="signalharvester-promotion-index-") as folder:
        index = Path(folder) / "index"
        _promotion_git(worktree, "read-tree", expected_parent, index=index)
        _promotion_git(worktree, "add", "-A", "--", *actual_changed, index=index)
        tree = _promotion_git(worktree, "write-tree", index=index)
        # diff-tree compares commits/trees; --root is unnecessary for a nonempty base.
        actual_tree_paths = _promotion_git(worktree, "diff", "--name-only", expected_parent,
                                           "--cached", "--", index=index).splitlines()
        if sorted(actual_tree_paths) != sorted(actual_changed):
            raise ValueError("Staged promotion tree differs from verified changed files")
        if tree == _promotion_git(worktree, "rev-parse", f"{expected_parent}^{{tree}}"):
            raise ValueError("Stage promotion cannot record an empty commit")
        # Git attributes, EOL normalization and clean filters must not silently
        # change the reviewed source bytes when the alternate index is built.
        env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        env["GIT_INDEX_FILE"] = str(index)
        for name in actual_changed:
            source_file = worktree / name
            base_entry = _promotion_git(worktree, "ls-tree", expected_parent, "--", name)
            if source_file.is_file():
                staged = subprocess.run(["git", "show", f":{name}"], cwd=worktree,
                                        env=env, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, check=False)
                if staged.returncode or staged.stdout != source_file.read_bytes():
                    raise ValueError("Git staging transformed verified worker source bytes")
                staged_entry = _promotion_git(worktree, "ls-files", "--stage", "--", name, index=index)
                staged_mode = staged_entry.split(" ", 1)[0]
                base_mode = base_entry.split(" ", 1)[0] if base_entry else "100644"
                if (staged_mode != base_mode or staged_mode not in ("100644", "100755")
                        or bool(source_file.stat().st_mode & 0o111) != (staged_mode == "100755")):
                    raise ValueError("Unverified Git file mode change is not allowed")
            elif not base_entry:
                raise ValueError("Stage changed path is neither a file nor a tracked deletion")
        _promotion_git(worktree, "diff", "--cached", "--check", expected_parent, index=index)
        # Final source recheck immediately before creating the detached commit object.
        workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
        message = f"Agent stage {manifest['stage']} ({manifest['runId']})\n\nSource-bound stage promotion; not sub-spec acceptance.\n"
        commit = subprocess.run(["git", "commit-tree", tree, "-p", expected_parent], cwd=worktree,
                                input=message, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, check=False,
                                env={k: v for k, v in os.environ.items() if not k.startswith("GIT_")})
        if commit.returncode or not re.fullmatch(r"[0-9a-f]{40}", commit.stdout.strip()):
            raise ValueError("Git stage commit creation failed; configure repository author identity")
        commit_sha = commit.stdout.strip()

    if (_promotion_git(repo_root, "rev-parse", "--verify", ref) != expected_parent
            or _promotion_branch_is_checked_out(repo_root, feature_branch)):
        raise ValueError("Feature branch changed while preparing stage commit")
    intent = {
        "schemaVersion": core_module.SCHEMA_VERSION, "runId": manifest["runId"],
        "stage": manifest["stage"], "featureBranch": feature_branch,
        "parentCommit": expected_parent, "stageCommit": commit_sha,
        "workerChangeSha256": worker["changeSha256"],
        "reviewAttemptSha256": core_module.sha256_file(run_dir / "review-attempt.json"),
        "requiresExternalAcceptance": True,
    }
    core_module.write_json(run_dir / "promotion-intent.json", intent)
    # Compare-and-swap is the only branch mutation: no checkout, reset, merge or rebase.
    _promotion_git(repo_root, "update-ref", ref, commit_sha, expected_parent)
    core_module.write_json(run_dir / "promotion.json", intent)
    return intent
