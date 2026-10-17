#!/usr/bin/env python3
"""Independent A5.3 oracle for bounded sequential stage coordination."""

from __future__ import annotations

import argparse
import importlib.util
import json
import unittest
from pathlib import Path
from unittest import mock

from stage_progression_oracle import StageProgressionOracle


class StageSequenceOracle(unittest.TestCase):
    """Validate real Git ancestry and fail-closed coordination without source test fixtures."""

    setUp = StageProgressionOracle.setUp
    git = staticmethod(StageProgressionOracle.git)
    promote = StageProgressionOracle.promote
    next_stage = StageProgressionOracle.next_stage

    def planned(self) -> tuple[Path, dict]:
        run_dir = self.next_stage()
        return run_dir, self.controller.read_json(run_dir / "manifest.json")

    def drive(self, run_dir: Path, manifest: dict, **kwargs: object) -> tuple[dict, int]:
        args = {"expected_stages": ["A5.1", "A5.2"],
                "feature_branch": self.branch, "previous_run_dir": self.evidence}
        args.update(kwargs)
        return self.controller.drive_stage_sequence(
            self.repo, self.repo / "build/agent-worktrees", run_dir, manifest,
            self.policy_path, self.policy, **args)

    def test_s01_preflight_does_not_execute_or_promote(self) -> None:
        directory, manifest = self.planned()
        report, code = self.drive(directory, manifest)
        self.assertEqual(0, code)
        self.assertEqual("AWAIT_EXPLICIT_EXECUTION", report["action"])
        self.assertFalse((directory / "sequence-intent.json").exists())
        self.assertEqual(self.head, self.git(self.repo, "rev-parse", "HEAD"))

    def test_s02_rejects_skipped_stage(self) -> None:
        directory, manifest = self.planned()
        with self.assertRaises(ValueError):
            self.drive(directory, manifest, expected_stages=["A5.2", "A5.1"])
        self.assertFalse((directory / "sequence-intent.json").exists())

    def test_s03_requires_explicit_execution_approval(self) -> None:
        directory, manifest = self.planned()
        with self.assertRaises(ValueError):
            self.drive(directory, manifest, execute=True)
        self.assertFalse((directory / "sequence-intent.json").exists())

    def test_s04_missing_predecessor_evidence_blocks(self) -> None:
        directory, manifest = self.planned()
        with self.assertRaises(ValueError):
            self.drive(directory, manifest, previous_run_dir=None)

    def test_s05_only_one_cycle_and_never_git_promotion(self) -> None:
        directory, manifest = self.planned()
        with (mock.patch.object(self.controller, "prepare_worker_workspace"),
              mock.patch.object(self.controller, "run_automated_cycle", return_value=0) as cycle,
              mock.patch.object(self.controller, "update_status", return_value={
                  "state": "READY_FOR_MAIN_REVIEW"})):
            outcome, code = self.drive(directory, manifest, execute=True, developer_approved=True)
        self.assertEqual(0, code)
        self.assertEqual("AWAIT_DEVELOPER_PROMOTION", outcome["action"])
        self.assertEqual(1, cycle.call_count)
        self.assertEqual(self.head, self.git(self.repo, "rev-parse", self.branch))
        self.assertFalse((directory / "promotion.json").exists())
        with self.assertRaises(ValueError):
            self.drive(directory, manifest, execute=True, developer_approved=True)

    def test_s06_interruption_keeps_nonreplayable_intent(self) -> None:
        directory, manifest = self.planned()
        with mock.patch.object(self.controller, "prepare_worker_workspace", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.drive(directory, manifest, execute=True, developer_approved=True)
        self.assertTrue((directory / "sequence-intent.json").exists())
        self.assertFalse((directory / "sequence-result.json").exists())
        with self.assertRaises(ValueError):
            self.drive(directory, manifest)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("sequence_candidate", args.candidate.resolve())
    if spec is None or spec.loader is None:
        raise ValueError("Invalid candidate controller")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    StageSequenceOracle.controller = module
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(StageSequenceOracle)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(json.dumps({"schemaVersion": 1, "oracle": "a5.3-stage-sequence",
                      "status": "PASS" if result.wasSuccessful() else "FAIL",
                      "testsRun": result.testsRun, "failures": len(result.failures),
                      "errors": len(result.errors)}, sort_keys=True))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
