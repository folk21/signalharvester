"""Handoff subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from . import core as core_module
from . import status as status_module
from . import worker_attempts as worker_attempts_module

def write_handoff(repo_root: Path, run_dir: Path, manifest: dict[str, Any], policy: dict[str, Any],
                  *, _update_status=None) -> dict[str, Any]:
    """Publish a bounded evidence index for the external acceptance checkpoint."""
    status = (_update_status or status_module.update_status)(repo_root, run_dir, manifest, policy)
    worker_path = run_dir / "worker.json"
    worker = core_module.read_json(worker_path) if worker_path.exists() else None
    review_path = run_dir / "review.json"
    review = core_module.read_json(review_path) if review_path.exists() else None
    worker_attempts = worker_attempts_module.collect_worker_attempts(run_dir)
    handoff = {"schemaVersion": core_module.SCHEMA_VERSION, "runId": manifest["runId"],
        "stage": manifest["stage"], "spec": manifest["specPath"],
        "baseCommit": manifest["repository"].get("baseCommit"),
        "taskSha256": manifest["taskSha256"], "policySha256": manifest["policy"]["sha256"],
        "stagePacket": manifest.get("stagePacket"),
        "worker": None if worker is None else {"status": worker["status"],
            "model": worker["model"], "changeSha256": worker["changeSha256"],
            "changedFiles": worker["changedFiles"]},
        "workerAttempts": worker_attempts,
        "checks": status["checks"], "review": review, "state": status["state"],
        "risk": status["riskAssessment"],
        "requiresExternalAcceptance": True,
        "evidenceFiles": ["manifest.json", "worker.json", "checks.json", "review.json", "status.json"]}
    core_module.write_json(run_dir / "handoff.json", handoff)
    return handoff
