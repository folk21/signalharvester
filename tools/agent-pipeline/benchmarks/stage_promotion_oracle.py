#!/usr/bin/env python3
"""Independent A5.1 oracle: guarded feature-branch promotion from source-bound runs."""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


class StagePromotionOracle(unittest.TestCase):
    """Verify exact-base CAS promotion without depending on the controller test suite."""

    def setUp(self) -> None:
        self.sandbox = tempfile.TemporaryDirectory()
        self.addCleanup(self.sandbox.cleanup)
        self.root = Path(self.sandbox.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        for rel, data in {
            ".gitignore": "build/\n", "README.md": "# Test\n", "AGENTS.md": "# Rules\n",
            "docs/specs/README.md": "# Index\n",
            "docs/specs/active/subspecs/oracle.md": "# Oracle\nA5.1\nAP12\n",
            "src/sample.txt": "old\n",
        }.items():
            dest = self.repo / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(data)
        self.git(self.repo, "init", "-q")
        self.git(self.repo, "config", "user.name", "Oracle")
        self.git(self.repo, "config", "user.email", "oracle@example.invalid")
        self.git(self.repo, "add", ".")
        self.git(self.repo, "commit", "-qm", "initial")
        self.parent = self.git(self.repo, "rev-parse", "HEAD")
        self.branch = "feature/a51-oracle"
        self.git(self.repo, "branch", self.branch)
        self.work = self.root / "work"
        self.git(self.repo, "worktree", "add", "-q", "-b", "agent/a51-oracle", str(self.work), self.parent)
        (self.work / "src/sample.txt").write_text("reviewed\n")
        self.evidence = self.root / "evidence" / "oracle-run"
        self.evidence.mkdir(parents=True)
        (self.evidence / "task.md").write_text("Stage task\n")
        self.policy = {"schemaVersion": 1, "requiredChecks": [
            {"name": "canonical", "command": ["true"]}],
            "workerExecution": {"required": True},
            "independentReview": {"required": True, "blockingSeverities": ["HIGH", "CRITICAL"]},
            "riskRules": [{"level": "LOW", "patterns": ["^src/"]}]}
        # The policy lives outside the Git checkout so no fixture dirt appears.
        self.policy_path = self.root / "policy.json"
        self.controller.write_json(self.policy_path, self.policy)
        stage_packet = {"schemaVersion": 1, "stageId": "A5.1",
            "specPath": "docs/specs/active/subspecs/oracle.md",
            "implementationPlanPath": None, "requirementIds": ["AP12"], "featureIds": []}
        self.controller.write_json(self.evidence / "stage-packet.json", stage_packet)
        source = "docs/specs/active/subspecs/oracle.md"
        self.manifest = {"schemaVersion": 1, "runId": "oracle-run", "stage": "A5.1",
            "specPath": source, "policy": {"sha256": self.controller.sha256_file(self.policy_path)},
            "taskSha256": self.controller.sha256_file(self.evidence / "task.md"),
            "requiredChecks": ["canonical"],
            "repository": {"gitAvailable": True, "baseCommit": self.parent, "branch": self.branch},
            "workspace": {"kind": "git-worktree", "path": str(self.work), "baseCommit": self.parent},
            "stagePacket": {"sha256": self.controller.sha256_file(self.evidence / "stage-packet.json"),
                "specPath": source, "planPath": None, "riskLevel": "LOW",
                "editablePaths": ["src/sample.txt"],
                "contextSha256": {source: self.controller.sha256_file(self.repo / source)},
                "requirementIds": ["AP12"], "featureIds": []}}
        self.controller.write_json(self.evidence / "manifest.json", self.manifest)
        self.worker = {"attempt": 1, "status": "PASS", "model": "fake",
            "taskSha256": self.manifest["taskSha256"], "headCommit": self.parent,
            "changedFiles": ["src/sample.txt"],
            "changeSha256": self.controller.workspace_change_digest(self.work, ["src/sample.txt"])}
        worker_log = self.evidence / "logs/worker-attempt-1.log"
        worker_log.parent.mkdir(exist_ok=True)
        worker_log.write_text("worker PASS\n")
        self.worker["log"] = "logs/worker-attempt-1.log"
        self.worker["logSha256"] = self.controller.sha256_file(worker_log)
        self.controller.write_json(self.evidence / "worker.json", self.worker)
        self.controller.write_json(self.evidence / "worker-attempt-1.json", self.worker)
        log = self.evidence / "logs/canonical-attempt-1.log"
        log.parent.mkdir(exist_ok=True)
        log.write_text("PASS\n")
        self.controller.write_json(self.evidence / "checks.json", {"checks": [{
            "name": "canonical", "required": True, "status": "PASS", "attempt": 1,
            "exitCode": 0, "log": "logs/canonical-attempt-1.log",
            "logSha256": self.controller.sha256_file(log)}]})
        self.controller.write_json(self.evidence / "review.json", {"schemaVersion": 1,
            "reviewer": "independent", "verdict": "PASS", "findings": []})
        self.controller.write_json(self.evidence / "review-attempt.json", {"status": "PASS",
            "workerSha256": self.worker["changeSha256"]})
        self.controller.write_json(self.evidence / "status.json", {
            "runId": "oracle-run", "stage": "A5.1", "state": "READY_FOR_MAIN_REVIEW",
            "checks": {"canonical": {"status": "PASS", "attempt": 1, "required": True}},
            "review": {"present": True, "verdict": "PASS"}})

    @staticmethod
    def git(root: Path, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=root, check=True,
            text=True, capture_output=True).stdout.strip()

    def promote(self, approved: bool = True) -> dict:
        return self.controller.promote_verified_stage(self.repo, self.evidence,
            self.manifest, self.policy_path, self.policy, feature_branch=self.branch,
            expected_parent=self.parent, developer_approved=approved)

    def test_p01_exact_parent_and_content(self) -> None:
        receipt = self.promote()
        self.assertEqual(self.parent, receipt["parentCommit"])
        self.assertEqual(receipt["stageCommit"], self.git(self.repo, "rev-parse", self.branch))
        self.assertEqual("reviewed", self.git(self.repo, "show", f"{receipt['stageCommit']}:src/sample.txt"))
        self.assertEqual(self.parent, self.git(self.repo, "rev-parse", "HEAD"))
        self.assertTrue(receipt["requiresExternalAcceptance"])

    def test_p02_no_automatic_approval(self) -> None:
        with self.assertRaises(ValueError):
            self.promote(False)
        self.assertEqual(self.parent, self.git(self.repo, "rev-parse", self.branch))

    def test_p03_checked_out_ref_denied(self) -> None:
        self.git(self.repo, "checkout", "-q", self.branch)
        with self.assertRaises(ValueError):
            self.promote()

    def test_p04_source_drift_denied(self) -> None:
        (self.work / "src/sample.txt").write_text("changed since review\n")
        with self.assertRaises(ValueError):
            self.promote()
        self.assertEqual(self.parent, self.git(self.repo, "rev-parse", self.branch))

    def test_p05_failed_provider_evidence_denied(self) -> None:
        self.controller.write_json(self.evidence / "review-attempt.json", {
            "status": "FAIL", "workerSha256": self.worker["changeSha256"]})
        with self.assertRaises(ValueError):
            self.promote()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("stage_promotion_candidate", args.candidate.resolve())
    if spec is None or spec.loader is None:
        raise ValueError("Invalid candidate Python source")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    StagePromotionOracle.controller = module
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(StagePromotionOracle)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(json.dumps({"schemaVersion": 1, "oracle": "a5.1-stage-promotion",
        "status": "PASS" if result.wasSuccessful() else "FAIL", "testsRun": result.testsRun,
        "failures": len(result.failures), "errors": len(result.errors)}, sort_keys=True))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
