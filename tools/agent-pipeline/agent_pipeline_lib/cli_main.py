"""Cli main subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from . import checks as checks_module
from . import cli_parser as cli_parser_module
from . import core as core_module
from . import cycle as cycle_module
from . import handoff as handoff_module
from . import policy as policy_module
from . import promotion as promotion_module
from . import repair as repair_module
from . import reviewer_contract as reviewer_contract_module
from . import reviewer_transport as reviewer_transport_module
from . import runs as runs_module
from . import stage_init as stage_init_module
from . import stage_scope as stage_scope_module
from . import stage_sequence as stage_sequence_module
from . import status as status_module
from . import subspec_evidence as subspec_evidence_module
from . import subspec_export as subspec_export_module
from . import worker_exec as worker_exec_module
from . import workspaces as workspaces_module

def resolve_under_repo(repo_root: Path, value: str) -> Path:
    """Resolve a path against the repository while preserving explicit absolute paths."""
    path = Path(value)
    return path if path.is_absolute() else repo_root / path


def main(argv: list[str] | None = None) -> int:
    """Execute one agent-pipeline foundation command."""
    parser = cli_parser_module.build_parser()
    args = parser.parse_args(argv)
    repo_root = Path(args.repo_root).resolve()
    policy_path = resolve_under_repo(repo_root, args.policy).resolve()
    runs_root = resolve_under_repo(repo_root, args.runs_root).resolve()
    worktrees_root = resolve_under_repo(repo_root, args.worktrees_root).resolve()
    try:
        policy = core_module.read_json(policy_path)
        policy_module.validate_policy(policy)
        policy_module.validate_repository(repo_root, policy)

        if args.command == "probe-reviewer":
            result = reviewer_transport_module.probe_independent_reviewer(policy)
            print(json.dumps(result, indent=2, sort_keys=True))
            return 0 if result["status"] == "PASS" else 2

        if args.command == "verify-subspec-export":
            print(json.dumps(subspec_export_module._verified_subspec_export(Path(args.bundle_dir)), indent=2, sort_keys=True))
            return 0

        if args.command == "export-subspec":
            if len(args.run_id) > subspec_evidence_module.SUBSPEC_MAX_RUNS or any(
                not subspec_export_module.SUBSPEC_EXPORT_FILENAME.fullmatch(run_id) or run_id in (".", "..")
                for run_id in args.run_id
            ):
                raise ValueError("Invalid bounded export run selection")
            run_dirs = [runs_module.load_run(runs_root, run_id)[0] for run_id in args.run_id]
            result = subspec_export_module.export_subspec_handoff(
                run_dirs, spec_path=args.spec, expected_stages=args.stage,
                destination=Path(args.output_dir), repo_root=repo_root,
            )
            print(json.dumps(result, indent=2, sort_keys=True))
            return 0

        if args.command == "summarize-subspec":
            if len(args.run_id) > subspec_evidence_module.SUBSPEC_MAX_RUNS:
                raise ValueError("Too many selected runs")
            if any(not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", run_id)
                   or run_id in (".", "..") for run_id in args.run_id):
                raise ValueError("Invalid selected run identifier")
            run_dirs = [runs_module.load_run(runs_root, run_id)[0] for run_id in args.run_id]
            result = subspec_evidence_module.summarize_subspec_evidence(
                run_dirs, spec_path=args.spec, expected_stages=args.stage
            )
            print(json.dumps(result, indent=2, sort_keys=True))
            return 0

        if args.command == "init":
            task_path = resolve_under_repo(repo_root, args.task_file)
            task_text = task_path.read_text(encoding="utf-8")
            run_dir = runs_module.create_run(
                repo_root,
                runs_root,
                policy_path,
                policy,
                args.stage,
                args.spec,
                task_text,
                args.worker or policy.get("workerExecution", {}).get("tool", "aider"),
                args.model or policy.get("workerExecution", {}).get("defaultModel", "unspecified"),
                args.scope,
                args.run_id,
            )
            print(run_dir)
            return 0

        if args.command == "init-stage":
            packet_path = resolve_under_repo(repo_root, args.packet)
            packet, context = stage_scope_module.validate_stage_packet(repo_root, core_module.read_json(packet_path))
            run_dir = stage_init_module.initialize_stage_run(repo_root, runs_root, policy_path, policy,
                                           packet, context, args.run_id)
            print(run_dir)
            return 0

        if args.command == "prepare-next-stage":
            run_dir = stage_init_module.prepare_next_stage(
                repo_root, runs_root, policy_path, policy,
                previous_run_dir=Path(args.previous_run_dir),
                next_packet_path=resolve_under_repo(repo_root, args.packet),
                expected_stages=args.stage, feature_branch=args.feature_branch,
                expected_head=args.expected_head, run_id=args.run_id,
                developer_approved=args.developer_approved,
            )
            print(run_dir)
            return 0

        run_dir, manifest = runs_module.load_run(runs_root, args.run_id)
        runs_module.ensure_run_contract_matches(policy_path, run_dir, manifest)
        stage_scope_module.ensure_stage_contract(repo_root, run_dir, manifest)

        if args.command == "drive-stage":
            report, code = stage_sequence_module.drive_stage_sequence(
                repo_root, worktrees_root, run_dir, manifest, policy_path, policy,
                expected_stages=args.stage, feature_branch=args.feature_branch,
                previous_run_dir=(None if args.previous_run_dir is None
                                  else Path(args.previous_run_dir)),
                execute=args.execute, developer_approved=args.developer_approved,
            )
            print(json.dumps(report, indent=2, sort_keys=True))
            return code

        if args.command == "plan-batch":
            report = stage_sequence_module.plan_stage_batch(
                repo_root, run_dir, manifest, policy_path, policy,
                expected_stages=args.stage, feature_branch=args.feature_branch,
                previous_run_dir=(None if args.previous_run_dir is None
                                  else Path(args.previous_run_dir)),
                max_stages=args.max_stages,
                max_provider_requests=args.max_provider_requests,
                max_cost_cents=args.max_cost_cents,
            )
            print(json.dumps(report, indent=2, sort_keys=True))
            return 0

        if args.command == "promote-stage":
            receipt = promotion_module.promote_verified_stage(
                repo_root, run_dir, manifest, policy_path, policy,
                feature_branch=args.feature_branch, expected_parent=args.expected_parent,
                developer_approved=args.developer_approved,
            )
            print(json.dumps(receipt, indent=2, sort_keys=True))
            return 0

        if args.command == "prepare-worker":
            workspace = workspaces_module.prepare_worker_workspace(repo_root, worktrees_root, run_dir, manifest, policy)
            print(json.dumps(workspace, indent=2, sort_keys=True))
            return 0

        if args.command == "run-worker":
            result = worker_exec_module.execute_worker(repo_root, run_dir, manifest, policy)
            status_module.update_status(repo_root, run_dir, manifest, policy)
            return result

        if args.command == "repair":
            attempt, task = repair_module.prepare_repair(repo_root, run_dir, manifest, policy)
            result = worker_exec_module.execute_worker(repo_root, run_dir, manifest, policy,
                                    attempt=attempt, task_override=task)
            status_module.update_status(repo_root, run_dir, manifest, policy)
            return result

        if args.command == "run-cycle":
            result = cycle_module.run_automated_cycle(repo_root, run_dir, manifest, policy)
            status_module.update_status(repo_root, run_dir, manifest, policy)
            print(json.dumps(core_module.read_json(run_dir / "cycle.json"), indent=2, sort_keys=True))
            return result

        if args.command == "run-check":
            definitions = policy_module.required_check_map(policy)
            if args.name not in definitions:
                raise ValueError(f"Unknown required check: {args.name}")
            result = checks_module.run_required_check(repo_root, run_dir, manifest, definitions[args.name], policy)
            status_module.update_status(repo_root, run_dir, manifest, policy)
            return result

        if args.command == "verify":
            for definition in policy["requiredChecks"]:
                result = checks_module.run_required_check(repo_root, run_dir, manifest, definition, policy)
                status_module.update_status(repo_root, run_dir, manifest, policy)
                if result != 0:
                    return result
            return 0

        if args.command == "run-command":
            if (run_dir / "review.json").exists() or (run_dir / "review-attempt.json").exists():
                raise ValueError("Verification after review requires a new repair round")
            if args.name in policy_module.required_check_map(policy):
                raise ValueError(f"Use run-check for policy-defined check: {args.name}")
            command = cli_parser_module.parse_command_tail(args.command_args)
            if policy.get("workerExecution", {}).get("required", False):
                workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
            result = checks_module.execute_check(workspaces_module.execution_root(repo_root, manifest), run_dir, args.name, command, False)
            status_module.update_status(repo_root, run_dir, manifest, policy)
            return result

        if args.command == "run-review":
            review = reviewer_transport_module.run_independent_review(repo_root, run_dir, manifest, policy)
            print(json.dumps(status_module.update_status(repo_root, run_dir, manifest, policy), indent=2))
            return 0

        if args.command == "handoff":
            print(json.dumps(handoff_module.write_handoff(repo_root, run_dir, manifest, policy), indent=2))
            return 0

        if args.command == "record-review":
            if (run_dir / "review.json").exists() or (run_dir / "review-attempt.json").exists():
                raise ValueError("Independent review already recorded for this source attempt")
            if policy.get("workerExecution", {}).get("required", False):
                worker = workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
                checks = core_module.read_json(run_dir / "checks.json")
                checks_module.validate_security_check_evidence(policy, checks, worker)
                if status_module.derive_state(policy, checks, None, worker)[0] != core_module.STATE_REVIEW_REQUIRED:
                    raise ValueError("All required checks must pass before review")
            review_input = resolve_under_repo(repo_root, args.input)
            review = reviewer_contract_module.validate_review(core_module.read_json(review_input))
            reviews_path = run_dir / "reviews.json"
            reviews = core_module.read_json(reviews_path)
            reviews.setdefault("reviews", []).append(review)
            core_module.write_json(reviews_path, reviews)
            core_module.write_json(run_dir / "review.json", review)
            status = status_module.update_status(repo_root, run_dir, manifest, policy)
            print(json.dumps(status, indent=2, sort_keys=True))
            return 0

        if args.command == "status":
            status = status_module.update_status(repo_root, run_dir, manifest, policy)
            print(json.dumps(status, indent=2, sort_keys=True))
            return 0

        raise ValueError(f"Unsupported command: {args.command}")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
