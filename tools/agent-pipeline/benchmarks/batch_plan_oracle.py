#!/usr/bin/env python3
"""Independent AP20/BP oracle for read-only batch boundaries on real Git evidence."""

from __future__ import annotations

import argparse
import importlib.util
import json
import unittest
from pathlib import Path
from unittest import mock

from stage_sequence_oracle import StageSequenceOracle


class BatchPlanOracle(StageSequenceOracle):
    """Apply independent acceptance cases without worker-authored fixture assumptions."""

    def plan(self, directory: Path, manifest: dict, **changes: object) -> dict:
        options = {"expected_stages": ["A5.1", "A5.2"],
                   "feature_branch": self.branch, "previous_run_dir": self.evidence,
                   "max_stages": 1, "max_provider_requests": 2, "max_cost_cents": 100}
        options.update(changes)
        return self.controller.plan_stage_batch(
            self.repo, directory, manifest, self.policy_path, self.policy, **options)

    def test_bp01_source_binding_no_writes_or_git_transition(self) -> None:
        directory, manifest = self.planned()
        before = {item.name: item.read_bytes() for item in directory.iterdir() if item.is_file()}
        branch_head = self.git(self.repo, "rev-parse", self.branch)
        with (mock.patch.object(self.controller, "execute_worker", side_effect=AssertionError("paid call")),
              mock.patch.object(self.controller, "promote_verified_stage", side_effect=AssertionError("Git promotion"))):
            report = self.plan(directory, manifest)
        self.assertEqual("AWAIT_SEPARATE_STAGE_AUTHORIZATION", report["action"])
        self.assertFalse(report["executionSupported"])
        self.assertFalse(report["budget"]["enforced"])
        self.assertTrue(report["requiresExternalAcceptance"])
        self.assertEqual(["A5.2"], [item["stage"] for item in report["selectedStages"]])
        self.assertTrue(all(not item["executionAuthorized"] for item in report["selectedStages"]))
        self.assertEqual(before, {item.name: item.read_bytes() for item in directory.iterdir() if item.is_file()})
        self.assertEqual(branch_head, self.git(self.repo, "rev-parse", self.branch))
        self.assertFalse((directory / "sequence-intent.json").exists())

    def test_bp02_reject_unmetered_invalid_budgets(self) -> None:
        directory, manifest = self.planned()
        for kwargs in ({"max_cost_cents": False}, {"max_provider_requests": 0},
                       {"max_stages": 100}, {"max_cost_cents": 10**9}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.plan(directory, manifest, **kwargs)
        self.assertFalse((directory / "sequence-intent.json").exists())

    def test_bp03_predecessor_and_dirty_ref_fail_closed(self) -> None:
        directory, manifest = self.planned()
        with self.assertRaises(ValueError):
            self.plan(directory, manifest, previous_run_dir=None)
        (self.repo / "dirty.txt").write_text("mutation", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.plan(directory, manifest)
        self.assertFalse((directory / "worker.json").exists())
        self.assertFalse((directory / "sequence-intent.json").exists())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("batch_candidate", args.candidate.resolve())
    if spec is None or spec.loader is None:
        raise ValueError("Invalid controller candidate")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    BatchPlanOracle.controller = module
    names = ("test_bp01_source_binding_no_writes_or_git_transition",
             "test_bp02_reject_unmetered_invalid_budgets",
             "test_bp03_predecessor_and_dirty_ref_fail_closed")
    suite = unittest.TestSuite(BatchPlanOracle(name) for name in names)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(json.dumps({"schemaVersion": 1, "oracle": "a6.1-batch-plan",
                      "status": "PASS" if result.wasSuccessful() else "FAIL",
                      "testsRun": result.testsRun, "failures": len(result.failures),
                      "errors": len(result.errors)}, sort_keys=True))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
