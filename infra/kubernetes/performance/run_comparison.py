#!/usr/bin/env python3
"""Run the same bounded capacity workload across explicit backend replica counts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
BASELINE_RUNNER = ROOT / "infra" / "kubernetes" / "performance" / "run_baseline.py"
DEFAULT_OUTPUT_DIR = ROOT / "build" / "reports" / "performance" / "replica-comparison"
COMPARISON_METRICS = (
    "collectionSeconds",
    "pipelineCompletionSeconds",
    "postCollectionDrainSeconds",
    "analysisCompletionSeconds",
    "resultsCompletionSeconds",
    "analysisLagDrainSeconds",
    "resultsLagDrainSeconds",
    "eventObservationLagDrainSeconds",
    "outboxDrainSeconds",
    "collectionPublishRateItemsPerSecond",
    "endToEndRateItemsPerSecond",
)


def parse_replica_counts(value: str) -> list[int]:
    """Parses ordered, unique positive replica counts from a comma-separated CLI value."""

    counts: list[int] = []
    for raw in value.split(","):
        token = raw.strip()
        if not token:
            continue
        try:
            count = int(token)
        except ValueError as failure:
            raise argparse.ArgumentTypeError(f"invalid replica count: {token!r}") from failure
        if count < 1:
            raise argparse.ArgumentTypeError("replica counts must be at least one")
        if count in counts:
            raise argparse.ArgumentTypeError(f"duplicate replica count: {count}")
        counts.append(count)
    if len(counts) < 2:
        raise argparse.ArgumentTypeError("at least two replica counts are required for comparison")
    return counts


def report_filename(replicas: int) -> str:
    suffix = "replica" if replicas == 1 else "replicas"
    return f"capacity-{replicas}-{suffix}.json"


def load_baseline_report(path: Path, expected_replicas: int, sources: int, items_per_source: int) -> dict[str, Any]:
    report = json.loads(path.read_text(encoding="utf-8"))
    if report.get("scenario") != "pipeline-capacity-baseline":
        raise ValueError(f"unexpected baseline scenario in {path}: {report.get('scenario')!r}")

    environment = report.get("environment")
    workload = report.get("workload")
    summary = report.get("summary")
    if not isinstance(environment, dict) or not isinstance(workload, dict) or not isinstance(summary, dict):
        raise ValueError(f"baseline report is missing environment/workload/summary objects: {path}")
    if environment.get("backendReplicas") != expected_replicas:
        raise ValueError(
            f"baseline report {path} recorded backendReplicas={environment.get('backendReplicas')!r}, "
            f"expected {expected_replicas}"
        )
    if workload.get("sources") != sources or workload.get("itemsPerSource") != items_per_source:
        raise ValueError(
            f"baseline report {path} workload does not match comparison workload: "
            f"sources={workload.get('sources')!r}, itemsPerSource={workload.get('itemsPerSource')!r}"
        )
    return report


def _numeric(summary: dict[str, Any], metric: str) -> float | int | None:
    value = summary.get(metric)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"summary metric {metric!r} is not numeric: {value!r}")
    return value


def build_comparison(reports: list[tuple[int, Path, dict[str, Any]]]) -> dict[str, Any]:
    """Builds neutral deltas/ratios without declaring a performance winner or threshold."""

    if len(reports) < 2:
        raise ValueError("at least two baseline reports are required")

    reference_replicas, _, reference_report = reports[0]
    reference_summary = reference_report["summary"]
    metrics: dict[str, Any] = {}
    for metric in COMPARISON_METRICS:
        reference_value = _numeric(reference_summary, metric)
        observations: list[dict[str, Any]] = []
        for replicas, _, report in reports:
            value = _numeric(report["summary"], metric)
            difference = None
            ratio = None
            if value is not None and reference_value is not None:
                difference = round(float(value) - float(reference_value), 3)
                if float(reference_value) != 0.0:
                    ratio = round(float(value) / float(reference_value), 4)
            observations.append(
                {
                    "backendReplicas": replicas,
                    "value": value,
                    "differenceFromReference": difference,
                    "ratioToReference": ratio,
                }
            )
        metrics[metric] = {
            "referenceBackendReplicas": reference_replicas,
            "observations": observations,
        }

    workload = reference_report["workload"]
    return {
        "schemaVersion": 1,
        "scenario": "pipeline-capacity-replica-comparison",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "interpretation": {
            "referenceBackendReplicas": reference_replicas,
            "performanceBudgetApplied": False,
            "winnerDeclared": False,
            "note": "Differences and ratios are observations for this ordered local run, not capacity limits or SLO evidence.",
        },
        "workload": workload,
        "runs": [
            {
                "backendReplicas": replicas,
                "report": str(path),
                "startedAt": report.get("startedAt"),
                "backendImage": report["environment"].get("backendImage"),
                "consumerMembersBefore": report["environment"].get("consumerMembersBefore"),
            }
            for replicas, path, report in reports
        ],
        "metrics": metrics,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--backend-port", type=int, default=18084)
    parser.add_argument("--sources", type=int, default=6)
    parser.add_argument("--items-per-source", type=int, default=200)
    parser.add_argument("--replicas", type=parse_replica_counts, default=parse_replica_counts("1,3"))
    parser.add_argument("--sample-interval", type=float, default=1.0)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=240.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args(argv)
    if args.sources < 1 or args.sources > 20:
        parser.error("--sources must be between 1 and 20")
    if args.items_per_source < 1 or args.items_per_source > 500:
        parser.error("--items-per-source must be between 1 and 500")
    if args.sample_interval <= 0:
        parser.error("--sample-interval must be positive")
    if args.scenario_timeout <= 0:
        parser.error("--scenario-timeout must be positive")
    return args


def run_baseline(args: argparse.Namespace, replicas: int, output: Path, skip_preflight: bool) -> None:
    command = [
        sys.executable,
        str(BASELINE_RUNNER),
        "--namespace",
        args.namespace,
        "--backend-port",
        str(args.backend_port),
        "--sources",
        str(args.sources),
        "--items-per-source",
        str(args.items_per_source),
        "--replicas",
        str(replicas),
        "--sample-interval",
        str(args.sample_interval),
        "--http-timeout",
        str(args.http_timeout),
        "--scenario-timeout",
        str(args.scenario_timeout),
        "--rollout-timeout",
        args.rollout_timeout,
        "--output",
        str(output),
    ]
    if skip_preflight:
        command.append("--skip-preflight")
    subprocess.run(command, cwd=ROOT, check=True)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    output_dir = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    reports: list[tuple[int, Path, dict[str, Any]]] = []
    try:
        for index, replicas in enumerate(args.replicas):
            output = output_dir / report_filename(replicas)
            print(f"==> Capacity baseline with {replicas} backend replica{'s' if replicas != 1 else ''}")
            run_baseline(args, replicas, output, skip_preflight=index > 0)
            reports.append(
                (
                    replicas,
                    output,
                    load_baseline_report(output, replicas, args.sources, args.items_per_source),
                )
            )

        comparison = build_comparison(reports)
        comparison_path = output_dir / "capacity-comparison.json"
        comparison_path.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"==> Replica comparison report: {comparison_path}")
        print(json.dumps({metric: value["observations"] for metric, value in comparison["metrics"].items()}, indent=2))
        return 0
    except (OSError, ValueError, json.JSONDecodeError, subprocess.CalledProcessError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
