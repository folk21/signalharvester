#!/usr/bin/env python3
"""Independent black-box regression oracle for the A4 worker-attempt handoff pilot.

It loads the candidate controller from an explicit path. It does not modify
candidate source code and it does not replace canonical Git/worktree checks.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

CONTROLLER = None


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class HandoffContractOracle(unittest.TestCase):
    """Test externally observable handoff evidence across positive and adversarial cases."""

    def setUp(self) -> None:
        assert CONTROLLER is not None
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.run = self.root / "run"
        self.run.mkdir()
        self.manifest = {
            "runId": "black-box-oracle",
            "stage": "A4",
            "specPath": "docs/specs/active/subspecs/development-agent-pipeline.md",
            "repository": {"baseCommit": "b" * 40},
            "taskSha256": "a" * 64,
            "policy": {"sha256": "c" * 64},
        }
        self.status = {
            "state": "READY_FOR_MAIN_REVIEW",
            "checks": {"canonical": {"status": "PASS"}},
            "riskAssessment": {"level": "LOW", "changedFiles": []},
        }

    def payload(self, number: int, **updates: object) -> dict:
        record = {
            "schemaVersion": 1,
            "attempt": number,
            "status": "PASS",
            "failureReason": None,
            "exitCode": 0,
            "taskSha256": "a" * 64,
            "changeSha256": f"{number:064d}",
            "changedFiles": ["tools/agent-pipeline/agent_pipeline.py"],
            "model": "deepseek/deepseek-chat",
            "tool": "aider",
            "headCommit": "b" * 40,
            "unexpectedCommit": False,
            "logSha256": "d" * 64,
            "log": f"logs/worker-attempt-{number}.log",
            "command": ["aider", "--model", "deepseek/deepseek-chat"],
            "preflight": {"status": "PASS"},
            "scopeFailure": None,
            "changeEvidenceAvailable": True,
        }
        record.update(updates)
        return record

    def worker(self, record: dict) -> None:
        (self.run / "worker.json").write_text(json.dumps(record), encoding="utf-8")

    def snapshot(self, number: int, record: dict | None = None, *, name: str | None = None) -> Path:
        path = self.run / (name or f"worker-attempt-{number}.json")
        path.write_text(json.dumps(self.payload(number) if record is None else record), encoding="utf-8")
        return path

    def handoff(self) -> dict:
        with mock.patch.object(CONTROLLER, "update_status", return_value=self.status):
            return CONTROLLER.write_handoff(self.root, self.run, self.manifest, {})

    def assert_rejected(self) -> None:
        with self.assertRaises((ValueError, TypeError)):
            self.handoff()
        self.assertFalse((self.run / "handoff.json").exists(), "Invalid history must not be published")

    def test_hi01_empty_pre_worker(self) -> None:
        result = self.handoff()
        self.assertEqual([], result["workerAttempts"])
        for key in ("schemaVersion", "runId", "stage", "spec", "baseCommit",
                    "taskSha256", "policySha256", "worker", "checks", "review",
                    "state", "risk", "requiresExternalAcceptance", "evidenceFiles"):
            self.assertIn(key, result)
        self.assertIsNone(result["worker"])

    def test_hi02_legacy_single_without_snapshot(self) -> None:
        self.worker(self.payload(1))
        result = self.handoff()["workerAttempts"]
        self.assertEqual([1], [entry["attempt"] for entry in result])
        self.assertNotIn("evidenceFile", result[0])
        self.assertNotIn("evidenceSha256", result[0])

    def test_hi03_single_with_snapshot_is_digest_linked(self) -> None:
        record = self.payload(1)
        self.worker(record)
        path = self.snapshot(1, record)
        result = self.handoff()["workerAttempts"]
        self.assertEqual(1, len(result))
        self.assertEqual(path.name, result[0]["evidenceFile"])
        self.assertEqual(digest(path), result[0]["evidenceSha256"])

    def test_hi04_multiple_attempts_numeric_order_and_digests(self) -> None:
        self.snapshot(3)
        self.snapshot(1, self.payload(1, status="FAIL", failureReason="worker produced no source changes"))
        self.snapshot(2)
        self.worker(self.payload(3))
        entries = self.handoff()["workerAttempts"]
        self.assertEqual([1, 2, 3], [entry["attempt"] for entry in entries])
        for entry in entries:
            path = self.run / entry["evidenceFile"]
            self.assertEqual(f"worker-attempt-{entry['attempt']}.json", path.name)
            self.assertEqual(digest(path), entry["evidenceSha256"])
        self.assertEqual("FAIL", entries[0]["status"])

    def test_hi05_corrupted_json_and_array_fail_closed(self) -> None:
        latest = self.payload(1)
        self.worker(latest)
        path = self.snapshot(1, latest)
        for content in ("{bad-json", "[]"):
            with self.subTest(content=content):
                path.write_text(content, encoding="utf-8")
                self.assert_rejected()

    def test_hi06_noncanonical_filenames_fail_closed(self) -> None:
        latest = self.payload(1)
        self.worker(latest)
        self.snapshot(1, latest)
        for filename in ("worker-attempt-01.json", "worker-attempt-0.json"):
            path = self.snapshot(1, latest, name=filename)
            with self.subTest(filename=filename):
                self.assert_rejected()
            path.unlink()

    def test_hi07_snapshot_payload_requires_exact_int_identifier(self) -> None:
        latest = self.payload(1)
        self.worker(latest)
        path = self.snapshot(1, latest)
        for wrong in (True, "1", 1.0, 2, None, False):
            with self.subTest(value=repr(wrong)):
                path.write_text(json.dumps({**latest, "attempt": wrong}), encoding="utf-8")
                self.assert_rejected()

    def test_hi08_worker_requires_exact_positive_int_identifier(self) -> None:
        for wrong in (True, "1", 1.0, 0, None, False):
            with self.subTest(value=repr(wrong)):
                self.worker(self.payload(1, attempt=wrong))
                self.assert_rejected()

    def test_hi09_gap_in_snapshots_fails_closed(self) -> None:
        self.snapshot(1)
        self.snapshot(3)
        self.worker(self.payload(3))
        self.assert_rejected()

    def test_hi10_future_or_stale_snapshot_fails_closed(self) -> None:
        self.snapshot(1)
        self.snapshot(2)
        self.worker(self.payload(1))
        self.assert_rejected()

    def test_hi11_snapshots_require_authoritative_worker(self) -> None:
        self.snapshot(1)
        self.assert_rejected()

    def test_hi12_latest_full_record_conflict_fails_closed(self) -> None:
        original = self.payload(1)
        self.snapshot(1, original)
        mismatches = {
            "taskSha256": "e" * 64,
            "model": "other/model",
            "logSha256": "f" * 64,
            "command": ["aider", "--other-flag"],
            "preflight": {"status": "FAIL"},
        }
        for field, value in mismatches.items():
            with self.subTest(field=field):
                self.worker({**original, field: value})
                self.assert_rejected()

    def test_hi13_compact_list_does_not_leak_raw_fields(self) -> None:
        worker = self.payload(2, log="SECRET-LOG-TEXT",
                              command=["SECRET-COMMAND-TEXT"],
                              modelTranscript="SECRET-MODEL-TEXT")
        self.snapshot(1, self.payload(1, log="SECRET-OLD-LOG"))
        self.snapshot(2, worker)
        self.worker(worker)
        result = self.handoff()
        encoded = json.dumps(result["workerAttempts"])
        for forbidden in ("SECRET-LOG-TEXT", "SECRET-COMMAND-TEXT",
                          "SECRET-MODEL-TEXT", "SECRET-OLD-LOG"):
            self.assertNotIn(forbidden, encoded)
        for entry in result["workerAttempts"]:
            self.assertNotIn("command", entry)
            self.assertNotIn("log", entry)
            self.assertNotIn("modelTranscript", entry)

    def test_hi14_published_snapshot_hash_detects_later_bytes_change(self) -> None:
        worker = self.payload(1)
        path = self.snapshot(1, worker)
        self.worker(worker)
        entries = self.handoff()["workerAttempts"]
        pinned = entries[0]["evidenceSha256"]
        self.assertEqual(digest(path), pinned)
        path.write_text(json.dumps({**worker, "failureReason": "tampered"}), encoding="utf-8")
        self.assertNotEqual(digest(path), pinned)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, help="Exact worker-tree agent_pipeline.py path")
    parser.add_argument("--report", help="Optional structured JSON result path")
    args = parser.parse_args()
    candidate = Path(args.candidate).resolve(strict=True)
    if candidate.name != "agent_pipeline.py":
        parser.error("--candidate must name agent_pipeline.py")
    spec = importlib.util.spec_from_file_location("candidate_agent_pipeline", candidate)
    if spec is None or spec.loader is None:
        parser.error("could not import candidate")
    global CONTROLLER
    CONTROLLER = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = CONTROLLER
    spec.loader.exec_module(CONTROLLER)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(HandoffContractOracle)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    summary = {
        "oracle": "a4-handoff-integrity",
        "testedController": "tools/agent-pipeline/agent_pipeline.py",
        "testsRun": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "status": "PASS" if result.wasSuccessful() else "FAIL",
        "failingTests": sorted(set(test.id().split(".test_")[-1].split(" (")[0]
                                   for test, _ in result.failures + result.errors)),
    }
    if args.report:
        output = Path(args.report)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(summary, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
