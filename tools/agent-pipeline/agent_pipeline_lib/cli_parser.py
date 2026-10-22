"""Cli parser subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import argparse

def parse_command_tail(parts: list[str]) -> list[str]:
    """Normalize argparse remainder syntax for a command executed without a shell."""
    if parts and parts[0] == "--":
        parts = parts[1:]
    if not parts:
        raise ValueError("A command must be provided after --")
    return parts


def build_parser() -> argparse.ArgumentParser:
    """Build the command-line interface for the foundation workflow."""
    parser = argparse.ArgumentParser(
        description="Create and verify bounded development-agent runs with machine-readable evidence."
    )
    parser.add_argument("--repo-root", default=".", help="Repository root, default: current directory")
    parser.add_argument(
        "--policy",
        default="tools/agent-pipeline/pipeline-policy.json",
        help="Repository-relative or absolute pipeline policy path",
    )
    parser.add_argument(
        "--runs-root",
        default="build/reports/agent-pipeline",
        help="Repository-relative or absolute run evidence directory",
    )
    parser.add_argument(
        "--worktrees-root",
        default="build/agent-worktrees",
        help="Repository-relative or absolute isolated worker worktree directory",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    init_parser = subparsers.add_parser("init", help="Initialize one bounded worker run")
    init_parser.add_argument("--stage", required=True)
    init_parser.add_argument("--spec", required=True, help="Active spec path or 'none' for contributor-tooling work")
    init_parser.add_argument("--task-file", required=True)
    init_parser.add_argument("--worker")
    init_parser.add_argument("--model")
    init_parser.add_argument("--scope", action="append", default=[])
    init_parser.add_argument("--run-id")

    stage_parser = subparsers.add_parser("init-stage", help="Initialize a validated LOW-risk stage packet")
    stage_parser.add_argument("--packet", required=True, help="JSON Stage Task Packet approved by the developer")
    stage_parser.add_argument("--run-id")

    prepare_parser = subparsers.add_parser("prepare-worker", help="Create the isolated Git branch/worktree")
    prepare_parser.add_argument("--run-id", required=True)

    worker_parser = subparsers.add_parser("run-worker", help="Run the configured one-shot Aider worker")
    worker_parser.add_argument("--run-id", required=True)

    repair_parser = subparsers.add_parser("repair", help="Run one bounded source-repair attempt")
    repair_parser.add_argument("--run-id", required=True)

    cycle_parser = subparsers.add_parser(
        "run-cycle", help="Run a bounded automatic worker/verification/review/repair cycle"
    )
    cycle_parser.add_argument("--run-id", required=True)

    verify_parser = subparsers.add_parser("verify", help="Run all required checks in policy order")
    verify_parser.add_argument("--run-id", required=True)

    check_parser = subparsers.add_parser("run-check", help="Run one policy-defined required check")
    check_parser.add_argument("--run-id", required=True)
    check_parser.add_argument("--name", required=True)

    command_parser = subparsers.add_parser("run-command", help="Run and record one additional focused check")
    command_parser.add_argument("--run-id", required=True)
    command_parser.add_argument("--name", required=True)
    command_parser.add_argument("command_args", nargs=argparse.REMAINDER)

    review_parser = subparsers.add_parser("record-review", help="Record an independent structured review")
    review_parser.add_argument("--run-id", required=True)
    review_parser.add_argument("--input", required=True)

    subparsers.add_parser("probe-reviewer", help="Test reviewer JSON response contract using synthetic data")

    aggregate_parser = subparsers.add_parser(
        "summarize-subspec", help="Read-only evidence summary of selected stage runs"
    )
    aggregate_parser.add_argument("--spec", required=True, help="Exact active sub-spec path")
    aggregate_parser.add_argument("--stage", action="append", required=True, help="Expected stage ID (ordered)")
    aggregate_parser.add_argument("--run-id", action="append", required=True, help="Selected run ID (ordered)")

    export_parser = subparsers.add_parser(
        "export-subspec", help="Preserve a private, replayable sub-spec evidence bundle"
    )
    export_parser.add_argument("--spec", required=True)
    export_parser.add_argument("--stage", action="append", required=True)
    export_parser.add_argument("--run-id", action="append", required=True)
    export_parser.add_argument("--output-dir", required=True, help="New absolute directory outside the repository")

    verify_export_parser = subparsers.add_parser(
        "verify-subspec-export", help="Revalidate private evidence bundle after build cleanup"
    )
    verify_export_parser.add_argument("--bundle-dir", required=True)

    promotion_parser = subparsers.add_parser(
        "promote-stage", help="Explicitly authorized CAS promotion of one verified stage"
    )
    promotion_parser.add_argument("--run-id", required=True)
    promotion_parser.add_argument("--feature-branch", required=True)
    promotion_parser.add_argument("--expected-parent", required=True)
    promotion_parser.add_argument("--developer-approved", action="store_true")

    next_parser = subparsers.add_parser(
        "prepare-next-stage", help="Bind one next stage to a promoted feature branch HEAD"
    )
    next_parser.add_argument("--previous-run-dir", required=True,
                             help="Absolute prior run evidence directory (also supports private A4.4 custody)")
    next_parser.add_argument("--packet", required=True, help="Next approved Stage Task Packet JSON")
    next_parser.add_argument("--stage", action="append", required=True,
                             help="Complete expected stage roster in order")
    next_parser.add_argument("--feature-branch", required=True)
    next_parser.add_argument("--expected-head", required=True,
                             help="Full 40-character promoted stage commit SHA")
    next_parser.add_argument("--run-id", required=True)
    next_parser.add_argument("--developer-approved", action="store_true",
                             help="Confirm that the developer explicitly approved this next packet")

    sequence_parser = subparsers.add_parser(
        "drive-stage", help="A5.3 checkpointed single-stage sequence preflight/execution"
    )
    sequence_parser.add_argument("--run-id", required=True)
    sequence_parser.add_argument("--stage", action="append", required=True,
                                 help="Full bounded sequence roster in order")
    sequence_parser.add_argument("--feature-branch", required=True)
    sequence_parser.add_argument("--previous-run-dir",
                                 help="Absolute preserved predecessor evidence for later stages")
    sequence_parser.add_argument("--execute", action="store_true",
                                 help="Opt in to one worker/check/review cycle; never promote")
    sequence_parser.add_argument("--developer-approved", action="store_true")

    batch_parser = subparsers.add_parser(
        "plan-batch", help="A6.1 read-only bounded batch envelope; never executes or promotes stages"
    )
    batch_parser.add_argument("--run-id", required=True)
    batch_parser.add_argument("--stage", action="append", required=True,
                              help="Complete approved stage roster in order")
    batch_parser.add_argument("--feature-branch", required=True)
    batch_parser.add_argument("--previous-run-dir",
                              help="Absolute preserved predecessor evidence for later stages")
    batch_parser.add_argument("--max-stages", type=int, required=True)
    batch_parser.add_argument("--max-provider-requests", type=int, required=True)
    batch_parser.add_argument("--max-cost-cents", type=int, required=True)

    reviewer_parser = subparsers.add_parser("run-review", help="Invoke independent fresh-context reviewer")
    reviewer_parser.add_argument("--run-id", required=True)

    handoff_parser = subparsers.add_parser("handoff", help="Write compact external acceptance handoff")
    handoff_parser.add_argument("--run-id", required=True)

    status_parser = subparsers.add_parser("status", help="Recompute and print one run status")
    status_parser.add_argument("--run-id", required=True)
    return parser
