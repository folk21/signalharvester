#!/usr/bin/env python3
"""Independent A4.2b read-only worker deviation evidence acceptance oracle.

This oracle lives outside the Stage Task Packet editable scope. It makes no
provider/network requests and does not modify candidate source files.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from typing import Any


def write_json(path: Path, record: dict[str, Any]) -> None:
    path.write_text(json.dumps(record, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class DeviationContractOracle(unittest.TestCase):
    """Protect DV-01..DV-20 against source-inconsistent or invented claims."""

    controller: Any = None

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.run = Path(temp.name) / "run"
        self.run.mkdir()
        self.task_digest = "1" * 64
        self.changed = "tools/agent-pipeline/agent_pipeline.py"
        self.manifest = {
            "schemaVersion": 1,
            "runId": "run-DV-001",
            "stage": "A4",
            "taskSha256": self.task_digest,
            "policy": {"sha256": "2" * 64},
            "repository": {"baseCommit": "3" * 40},
            "requiredChecks": ["gitleaks", "canonical"],
            "stagePacket": {"requirementIds": ["AP2", "AP9", "AP10", "AP13", "AP14"]},
        }
        self.checks = [
            {"name": "gitleaks", "attempt": 1, "status": "PASS", "required": True},
            {"name": "canonical", "attempt": 1, "status": "PASS", "required": True},
        ]
        self.status = {
            "schemaVersion": 1,
            "runId": "run-DV-001",
            "stage": "A4",
            "state": "BLOCKED",
            "checks": {item["name"]: {k: item[k] for k in ("attempt", "status", "required")}
                       for item in self.checks},
            "worker": {"present": True, "required": True, "status": "PASS"},
            "review": {"present": False, "verdict": None, "openFindingCount": 0},
        }
        self.persist_baseline()

    def worker(self, attempt: int) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "attempt": attempt,
            "status": "PASS",
            "failureReason": None,
            "taskSha256": self.task_digest,
            "model": "deepseek/deepseek-chat",
            "changeSha256": f"{attempt:064x}",
            "changedFiles": [self.changed],
            "logSha256": "4" * 64,
            "command": ["secret-raw-command"],
            "preflight": {"status": "PASS"},
        }

    def persist_baseline(self) -> None:
        write_json(self.run / "manifest.json", self.manifest)
        write_json(self.run / "worker.json", self.worker(1))
        write_json(self.run / "worker-attempt-1.json", self.worker(1))
        write_json(self.run / "checks.json", {"schemaVersion": 1, "checks": self.checks})
        write_json(self.run / "status.json", self.status)

    def record(self, attempt: int = 1, deviations: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "runId": self.manifest["runId"],
            "stage": self.manifest["stage"],
            "attempt": attempt,
            "taskSha256": self.task_digest,
            "changeSha256": self.worker(attempt)["changeSha256"],
            "reportedBy": "WORKER_MODEL_CLAIM",
            "deviations": [] if deviations is None else deviations,
        }

    def entry(self, number: int = 1) -> dict[str, Any]:
        return {
            "id": f"DEV-{number:03d}",
            "kind": "DESIGN_ALTERNATIVE",
            "requirementIds": ["AP14"],
            "summary": "Used existing parsing helper",
            "rationale": "Avoid duplicate parsing logic",
            "affectedPaths": [self.changed],
            "testImpact": "Added a malformed-file regression",
            "riskImpact": "NONE_IDENTIFIED",
        }

    def put(self, attempt: int = 1, payload: dict[str, Any] | None = None,
            filename: str | None = None) -> Path:
        path = self.run / (filename or f"worker-deviations-attempt-{attempt}.json")
        write_json(path, payload if payload is not None else self.record(attempt))
        return path

    def collect(self) -> list[dict[str, Any]]:
        return self.controller.collect_deviation_summaries(self.run)

    def project(self) -> dict[str, Any]:
        return self.controller.summarize_run_evidence(self.run)

    def rejected(self) -> None:
        with self.assertRaises((ValueError, TypeError)):
            self.collect()

    def test_dv01_unreported_has_no_fabricated_digest(self) -> None:
        result = self.collect()
        self.assertEqual([{"attempt": 1, "state": "UNREPORTED"}], result)
        self.assertEqual(result, self.project()["deviations"])

    def test_dv02_explicit_empty_is_worker_claim_with_digest(self) -> None:
        path = self.put()
        item = self.collect()[0]
        self.assertEqual("REPORTED_NONE", item["state"])
        self.assertEqual("WORKER_MODEL_CLAIM", item["source"])
        self.assertEqual(0, item["count"])
        self.assertEqual([], item["entries"])
        self.assertEqual(path.name, item["evidenceFile"])
        self.assertEqual(sha256_file(path), item["evidenceSha256"])

    def test_dv03_valid_bounded_deviation_projects_safe_metadata(self) -> None:
        self.put(payload=self.record(deviations=[self.entry()]))
        result = self.collect()[0]
        self.assertEqual("REPORTED", result["state"])
        self.assertEqual(1, result["count"])
        self.assertEqual([{"id": "DEV-001", "kind": "DESIGN_ALTERNATIVE",
                           "riskImpact": "NONE_IDENTIFIED"}], result["entries"])
        agg = self.project()
        self.assertEqual(self.collect(), agg["deviations"])
        for key in ("state", "workerAttempts", "checks", "review", "evidenceDigests",
                    "requiresExternalAcceptance"):
            self.assertIn(key, agg)
        self.assertTrue(agg["requiresExternalAcceptance"])

    def test_dv04_multiple_attempts_sorted_with_missing_middle_claim(self) -> None:
        for attempt in (1, 2, 3):
            write_json(self.run / f"worker-attempt-{attempt}.json", self.worker(attempt))
        write_json(self.run / "worker.json", self.worker(3))
        self.put(1, self.record(1, [self.entry()]))
        self.put(3, self.record(3))
        result = self.collect()
        self.assertEqual([1, 2, 3], [item["attempt"] for item in result])
        self.assertEqual(["REPORTED", "UNREPORTED", "REPORTED_NONE"],
                         [item["state"] for item in result])

    def test_dv05_noncanonical_snapshot_name_rejected(self) -> None:
        for filename in ("worker-deviations-attempt-01.json", "worker-deviations-attempt-0.json"):
            with self.subTest(filename=filename):
                path = self.put(filename=filename)
                self.rejected()
                path.unlink()

    def test_dv06_attempt_payload_strict_integer_and_filename_match(self) -> None:
        for value in (2, True, False, 1.0, "1", None):
            with self.subTest(value=value):
                payload = self.record()
                payload["attempt"] = value
                self.put(payload=payload)
                self.rejected()

    def test_dv07_orphan_deviation_record_rejected(self) -> None:
        self.put(2, self.record(2))
        self.rejected()

    def test_dv08_invalid_json_or_array_rejected(self) -> None:
        path = self.put()
        for raw in ("[]\n", "{broken"):
            with self.subTest(raw=raw):
                path.write_text(raw, encoding="utf-8")
                self.rejected()

    def test_dv09_manifest_run_stage_task_mismatch_rejected(self) -> None:
        for key, value in (("runId", "other-run"), ("stage", "A5"),
                           ("taskSha256", "f" * 64)):
            with self.subTest(key=key):
                record = self.record()
                record[key] = value
                self.put(payload=record)
                self.rejected()

    def test_dv10_change_sha_must_match_worker_evidence(self) -> None:
        payload = self.record()
        payload["changeSha256"] = "a" * 64
        self.put(payload=payload)
        self.rejected()

    def test_dv11_duplicate_or_disordered_ids_rejected(self) -> None:
        self.put(payload=self.record(deviations=[self.entry(1), self.entry(1)]))
        self.rejected()
        second_path = "tools/agent-pipeline/tests/test_agent_pipeline.py"
        worker = self.worker(1)
        worker["changedFiles"].append(second_path)
        write_json(self.run / "worker.json", worker)
        write_json(self.run / "worker-attempt-1.json", worker)
        second = self.entry(1)
        second["affectedPaths"] = [second_path]
        self.put(payload=self.record(deviations=[self.entry(2), second]))
        self.rejected()

    def test_dv12_unknown_enum_or_schema_keys_rejected(self) -> None:
        mutations = (
            ("kind", "UNAUTHORIZED"),
            ("riskImpact", "ACCEPTED"),
            ("nestedKey", "unrecognized"),
        )
        for field, value in mutations:
            with self.subTest(field=field):
                entry = self.entry()
                entry[field] = value
                self.put(payload=self.record(deviations=[entry]))
                self.rejected()
        payload = self.record()
        payload["rawCommand"] = "must reject"
        self.put(payload=payload)
        self.rejected()

    def test_dv13_affected_paths_must_be_changed_normalized_unique(self) -> None:
        paths = (["/etc/passwd"], ["../agent_pipeline.py"],
                 ["tools/agent-pipeline/../agent-pipeline/agent_pipeline.py"],
                 [self.changed, self.changed], ["docs/specs/README.md"],
                 ["./" + self.changed], ["C:\\secrets.txt"])
        for value in paths:
            with self.subTest(paths=value):
                entry = self.entry()
                entry["affectedPaths"] = value
                self.put(payload=self.record(deviations=[entry]))
                self.rejected()

    def test_dv14_requirement_ids_must_be_stage_authorized(self) -> None:
        for ids in (["AP999"], ["AP14", "AP14"]):
            with self.subTest(ids=ids):
                entry = self.entry()
                entry["requirementIds"] = ids
                self.put(payload=self.record(deviations=[entry]))
                self.rejected()

    def test_dv15_excess_entries_and_overlong_text_rejected(self) -> None:
        self.put(payload=self.record(deviations=[self.entry(n) for n in range(1, 22)]))
        self.rejected()
        for key in ("summary", "rationale", "testImpact"):
            with self.subTest(field=key):
                entry = self.entry()
                entry[key] = "x" * 501
                self.put(payload=self.record(deviations=[entry]))
                self.rejected()

    def test_dv16_no_sensitive_raw_narrative_in_projection(self) -> None:
        entry = self.entry()
        entry["rationale"] = "SENSITIVE_RAW_TRANSCRIPT_SENTINEL"
        entry["summary"] = "PRIVATE_INPUT_SENTINEL"
        entry["testImpact"] = "UNSAFE_COMMAND_SENTINEL"
        self.put(payload=self.record(deviations=[entry]))
        report = json.dumps(self.project())
        for needle in ("SENSITIVE_RAW_TRANSCRIPT_SENTINEL", "PRIVATE_INPUT_SENTINEL",
                       "UNSAFE_COMMAND_SENTINEL", "secret-raw-command"):
            self.assertNotIn(needle, report)

    def test_dv17_digest_binds_current_snapshot_bytes(self) -> None:
        path = self.put()
        old = self.collect()[0]["evidenceSha256"]
        with path.open("ab") as output:
            output.write(b"\n")
        self.assertNotEqual(old, sha256_file(path))

    def test_dv18_missing_worker_or_invalid_worker_history_rejected(self) -> None:
        self.put()
        (self.run / "worker.json").unlink()
        self.rejected()
        write_json(self.run / "worker.json", self.worker(3))
        write_json(self.run / "worker-attempt-3.json", self.worker(3))
        self.rejected()

    def test_dv19_legacy_packetless_run_empty_requirement_mapping(self) -> None:
        self.manifest.pop("stagePacket")
        write_json(self.run / "manifest.json", self.manifest)
        entry = self.entry()
        entry["requirementIds"] = []
        self.put(payload=self.record(deviations=[entry]))
        self.assertEqual("REPORTED", self.collect()[0]["state"])
        entry["requirementIds"] = ["AP14"]
        self.put(payload=self.record(deviations=[entry]))
        self.rejected()

    def test_dv20_requiring_decision_never_becomes_approval(self) -> None:
        entry = self.entry()
        entry["riskImpact"] = "REQUIRES_DECISION"
        self.put(payload=self.record(deviations=[entry]))
        report = self.project()
        self.assertEqual("REQUIRES_DECISION", report["deviations"][0]["entries"][0]["riskImpact"])
        self.assertEqual("BLOCKED", report["state"])
        self.assertTrue(report["requiresExternalAcceptance"])
        for word in ("accepted", "approved", "promoted"):
            self.assertNotIn(word, json.dumps(report).lower())

    def test_extra_symlink_evidence_is_rejected(self) -> None:
        target = self.run / "untrusted.json"
        write_json(target, self.record())
        (self.run / "worker-deviations-attempt-1.json").symlink_to(target)
        self.rejected()

    def test_extra_collection_does_not_mutate_evidence_files(self) -> None:
        self.put(payload=self.record(deviations=[self.entry()]))
        before = {path.name: sha256_file(path) for path in self.run.glob("*.json")}
        self.assertEqual(self.collect(), self.collect())
        self.assertEqual(before, {path.name: sha256_file(path) for path in self.run.glob("*.json")})


    def test_extra_distinct_claims_may_share_one_changed_path(self) -> None:
        self.put(payload=self.record(deviations=[self.entry(1), self.entry(2)]))
        self.assertEqual(2, self.collect()[0]["count"])

    def test_extra_malformed_matching_digest_fails_closed(self) -> None:
        for field, malformed in (("taskSha256", "Z" * 64),
                                 ("changeSha256", "A" * 64)):
            with self.subTest(field=field):
                original_manifest = copy.deepcopy(self.manifest)
                worker = self.worker(1)
                record = self.record()
                record[field] = malformed
                if field == "taskSha256":
                    self.manifest[field] = malformed
                    write_json(self.run / "manifest.json", self.manifest)
                    worker[field] = malformed
                else:
                    worker[field] = malformed
                write_json(self.run / "worker.json", worker)
                write_json(self.run / "worker-attempt-1.json", worker)
                self.put(payload=record)
                self.rejected()
                self.manifest = original_manifest
                self.persist_baseline()
        worker = self.worker(1)
        worker["taskSha256"] = "f" * 64
        write_json(self.run / "worker.json", worker)
        write_json(self.run / "worker-attempt-1.json", worker)
        self.put(payload=self.record())
        self.rejected()

    def test_extra_noncanonical_suffix_is_rejected(self) -> None:
        self.put()
        (self.run / "worker-deviations-attempt-1.json.bak").write_text("{}\n", encoding="utf-8")
        self.rejected()

    def test_extra_nonstring_identity_and_enums_fail_closed(self) -> None:
        for name in ("runId", "stage"):
            with self.subTest(field=name):
                original = self.manifest[name]
                self.manifest[name] = " "
                write_json(self.run / "manifest.json", self.manifest)
                payload = self.record()
                self.put(payload=payload)
                self.rejected()
                self.manifest[name] = original
                write_json(self.run / "manifest.json", self.manifest)
        for name in ("kind", "riskImpact"):
            with self.subTest(field=name):
                entry = self.entry()
                entry[name] = ["INVALID"]
                self.put(payload=self.record(deviations=[entry]))
                self.rejected()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--aggregate-output", type=Path)
    args = parser.parse_args()
    module_spec = importlib.util.spec_from_file_location("a42b_candidate", args.candidate.resolve())
    if module_spec is None or module_spec.loader is None:
        raise ValueError("Cannot load candidate source")
    controller = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(controller)
    DeviationContractOracle.controller = controller
    results = unittest.TextTestRunner(verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(DeviationContractOracle)
    )
    summary = {
        "schemaVersion": 1,
        "oracle": "a4.2b-worker-deviations",
        "status": "PASS" if results.wasSuccessful() else "FAIL",
        "testsRun": results.testsRun,
        "failures": len(results.failures),
        "errors": len(results.errors),
        "skipped": len(results.skipped),
        "failingTests": [str(case) for case, _ in results.failures + results.errors],
    }
    if args.evidence_dir is not None or args.aggregate_output is not None:
        if args.evidence_dir is None or args.aggregate_output is None:
            parser.error("--evidence-dir and --aggregate-output must be specified together")
        if results.wasSuccessful():
            try:
                aggregate = controller.summarize_run_evidence(args.evidence_dir)
                args.aggregate_output.parent.mkdir(parents=True, exist_ok=True)
                write_json(args.aggregate_output, aggregate)
            except Exception as exc:
                summary["status"] = "FAIL"
                summary["aggregateErrorType"] = type(exc).__name__
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        write_json(args.report, summary)
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
