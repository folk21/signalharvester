"""Subspec evidence subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import core as core_module
from . import deviations as deviations_module
from . import run_evidence as run_evidence_module

SUBSPEC_MAX_STAGES = 32


SUBSPEC_MAX_RUNS = 64


def summarize_subspec_evidence(
    run_dirs: list[Path], *, spec_path: str, expected_stages: list[str]
) -> dict[str, Any]:
    """Index selected stage runs without inferring stage promotion or sub-spec acceptance.

    The caller explicitly supplies the stage roster and run order. Historical
    source digests are snapshot references, not an immutable trust anchor.
    This helper only reads persisted run evidence; it never changes the run,
    invokes an external service, or consults the current Git checkout.
    """
    if (not isinstance(spec_path, str) or not spec_path.strip()
            or not isinstance(expected_stages, list)
            or not 1 <= len(expected_stages) <= SUBSPEC_MAX_STAGES
            or any(not isinstance(stage, str) or not stage.strip() or len(stage) > 128
                   for stage in expected_stages)
            or len(set(expected_stages)) != len(expected_stages)):
        raise ValueError("An explicit, bounded, unique stage roster and specPath are required")
    if (not isinstance(run_dirs, list) or not 1 <= len(run_dirs) <= SUBSPEC_MAX_RUNS
            or any(not isinstance(path, Path) for path in run_dirs)):
        raise ValueError("An explicit bounded list of run directories is required")

    per_stage: dict[str, list[dict[str, Any]]] = {stage: [] for stage in expected_stages}
    run_ids: set[str] = set()
    plan_path: str | None = None
    plan_initialized = False
    spec_versions: set[str] = set()
    plan_versions: set[str] = set()

    for index, run_dir in enumerate(run_dirs, start=1):
        if run_dir.is_symlink() or not run_dir.is_dir():
            raise ValueError("Sub-spec run evidence must be a regular directory")
        for candidate in run_dir.iterdir():
            if candidate.is_symlink():
                raise ValueError("Sub-spec evidence directory contains a symlink")
        manifest = run_evidence_module._require_json_object(run_dir / "manifest.json")
        packet_evidence = manifest.get("stagePacket")
        if not isinstance(packet_evidence, dict):
            raise ValueError("Sub-spec aggregation requires source-bound stage packets")
        summary = run_evidence_module.summarize_run_evidence(run_dir)
        stage = summary["stage"]
        run_id = summary["runId"]
        if (manifest.get("specPath") != spec_path or stage not in per_stage
                or packet_evidence.get("specPath") != spec_path):
            raise ValueError("Run is outside the selected sub-spec and stage roster")
        if (not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", run_id)
                or run_id in (".", "..")):
            raise ValueError("Invalid runId in sub-spec evidence")
        if run_id in run_ids:
            raise ValueError("Duplicate runId in sub-spec evidence selection")
        run_ids.add(run_id)
        if run_dir.name != run_id:
            raise ValueError("Run directory name does not match the persisted runId")

        plan = packet_evidence.get("planPath")
        if plan is not None and (not isinstance(plan, str) or not plan.strip()):
            raise ValueError("Invalid stage implementation-plan identity")
        if not plan_initialized:
            plan_path = plan
            plan_initialized = True
        elif plan != plan_path:
            raise ValueError("Selected runs refer to different implementation plans")
        context = packet_evidence.get("contextSha256")
        if not isinstance(context, dict):
            raise ValueError("Stage packet lacks source context digests")
        source_sha = deviations_module._require_sha256(context.get(spec_path), "stage specification digest")
        plan_sha = None if plan is None else deviations_module._require_sha256(
            context.get(plan), "stage implementation-plan digest"
        )
        spec_versions.add(source_sha)
        if plan_sha is not None:
            plan_versions.add(plan_sha)
        packet_path = run_dir / "stage-packet.json"
        packet_sha = deviations_module._require_sha256(packet_evidence.get("sha256"), "stage packet digest")
        if not packet_path.is_file() or core_module.sha256_file(packet_path) != packet_sha:
            raise ValueError("Stage packet does not match its recorded source digest")
        packet = run_evidence_module._require_json_object(packet_path)
        if (packet.get("schemaVersion") != core_module.SCHEMA_VERSION
                or type(packet.get("schemaVersion")) is not int
                or packet.get("stageId") != stage
                or packet.get("specPath") != spec_path
                or packet.get("implementationPlanPath") != plan):
            raise ValueError("Stage packet does not match the selected stage identity")
        for name in ("requirementIds", "featureIds"):
            expected = packet_evidence.get(name)
            if (not isinstance(expected, list) or not all(isinstance(x, str) and x.strip()
                                                          for x in expected)
                    or len(set(expected)) != len(expected) or packet.get(name) != expected):
                raise ValueError(f"Stage packet {name} evidence is inconsistent")

        worker_attempts = summary["workerAttempts"]
        if summary["state"] not in {
            core_module.STATE_PREPARED, core_module.STATE_WORKER_REQUIRED, core_module.STATE_VERIFYING,
            core_module.STATE_REVIEW_REQUIRED, core_module.STATE_BLOCKED, core_module.STATE_NEEDS_MAIN_REVIEW,
            core_module.STATE_READY_FOR_MAIN_REVIEW,
        }:
            raise ValueError("Unknown run state in sub-spec evidence")
        if summary["state"] == core_module.STATE_READY_FOR_MAIN_REVIEW:
            if (not worker_attempts or worker_attempts[-1].get("status") != "PASS"
                    or not worker_attempts[-1].get("changedFiles")):
                raise ValueError("Ready stage has no verified worker source state")
            deviations_module._require_sha256(worker_attempts[-1].get("changeSha256"), "ready worker changeSha256")
        review_attempt_path = run_dir / "review-attempt.json"
        review_attempt_sha = None
        if review_attempt_path.is_file():
            review_attempt = run_evidence_module._require_json_object(review_attempt_path)
            review_attempt_sha = core_module.sha256_file(review_attempt_path)
            if review_attempt.get("status") == "PASS":
                if (not summary["review"]["present"] or not worker_attempts
                        or review_attempt.get("workerSha256")
                        != worker_attempts[-1].get("changeSha256")):
                    raise ValueError("Reviewer attempt is not bound to validated review and worker evidence")
            if review_attempt.get("status") == "FAIL":
                if summary["review"]["present"] or summary["state"] == core_module.STATE_READY_FOR_MAIN_REVIEW:
                    raise ValueError("A failed provider review cannot support review presence or readiness")
            if review_attempt.get("status") not in ("PASS", "FAIL"):
                raise ValueError("Invalid reviewer attempt status")
        deviations = summary["deviations"]
        run_view = {
            "runId": run_id,
            "selectionOrder": index,
            "baseCommit": summary["baseCommit"],
            "taskSha256": summary["taskSha256"],
            "policySha256": summary["policySha256"],
            "stagePacketSha256": packet_sha,
            "specSha256": source_sha,
            "planSha256": plan_sha,
            "requirementIds": list(packet_evidence["requirementIds"]),
            "featureIds": list(packet_evidence["featureIds"]),
            "state": summary["state"],
            "workerAttemptCount": len(worker_attempts),
            "workerAttempts": [{
                "attempt": record["attempt"], "status": record["status"],
                "changeSha256": record["changeSha256"],
                "evidenceSha256": record.get("evidenceSha256"),
            } for record in worker_attempts],
            "checks": summary["checks"],
            "review": summary["review"],
            "deviations": [{
                "attempt": record["attempt"], "state": record["state"],
                "count": record.get("count"),
                "evidenceSha256": record.get("evidenceSha256"),
            } for record in deviations],
            "evidenceDigests": summary["evidenceDigests"],
            "reviewAttemptSha256": review_attempt_sha,
        }
        per_stage[stage].append(run_view)

    stages = [{
        "stageId": stage,
        "represented": bool(per_stage[stage]),
        "runCount": len(per_stage[stage]),
        "readyRunCount": sum(1 for run in per_stage[stage]
                             if run["state"] == core_module.STATE_READY_FOR_MAIN_REVIEW),
        "requirementIds": sorted({requirement for run in per_stage[stage]
                                  for requirement in run["requirementIds"]}),
        "featureIds": sorted({feature for run in per_stage[stage]
                              for feature in run["featureIds"]}),
        "runs": per_stage[stage],
    } for stage in expected_stages]
    return {
        "schemaVersion": core_module.SCHEMA_VERSION,
        "specPath": spec_path,
        "planPath": plan_path,
        "expectedStages": list(expected_stages),
        "missingStages": [entry["stageId"] for entry in stages if not entry["represented"]],
        "specSourceDigests": sorted(spec_versions),
        "planSourceDigests": sorted(plan_versions),
        "stages": stages,
        "selectedRunCount": len(run_ids),
        "requiresExternalAcceptance": True,
    }
