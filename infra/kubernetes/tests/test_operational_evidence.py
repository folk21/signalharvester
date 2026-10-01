import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"

spec = importlib.util.spec_from_file_location("operational_scenario_evidence", EVALUATION / "scenario_evidence.py")
evidence = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = evidence
spec.loader.exec_module(evidence)


class OperationalScenarioEvidenceTest(unittest.TestCase):
    def test_dataset_keeps_ground_truth_separate_from_observed_health(self):
        dataset = evidence.ScenarioEvidenceDataset(
            "unit-test-runner",
            {"namespace": "signalharvester-test"},
        )

        with dataset.scenario(
            "postgres-outage",
            "POSTGRESQL_OUTAGE",
            fault_injected=True,
            affected_subsystems=("POSTGRESQL", "ANALYSIS"),
        ) as scenario:
            scenario.add_change_marker("START", {"id": "change-1", "category": "TEST_SCENARIO"})
            scenario.add_health_snapshot(
                "RECOVERY",
                {"id": "snapshot-1", "overallStatus": "HEALTHY", "healthScore": 100},
            )
            scenario.add_measurement(
                "analysisDlqRecords",
                1,
                phase="FAULT",
                unit="records",
                source="rpk",
            )

        record = dataset.to_dict()["scenarios"][0]
        self.assertEqual("POSTGRESQL_OUTAGE", record["label"])
        self.assertTrue(record["groundTruth"]["faultInjected"])
        self.assertEqual(["POSTGRESQL", "ANALYSIS"], record["groundTruth"]["affectedSubsystems"])
        self.assertEqual("HEALTHY", record["evidence"]["healthSnapshots"][0]["snapshot"]["overallStatus"])
        self.assertNotIn("expectedStatus", record["groundTruth"])
        self.assertEqual("PASSED", record["outcome"])

    def test_failed_scenario_retains_partial_evidence(self):
        dataset = evidence.ScenarioEvidenceDataset("unit-test-runner", {})

        with self.assertRaises(RuntimeError):
            with dataset.scenario(
                "kafka-lag",
                "KAFKA_LAG",
                fault_injected=True,
                affected_subsystems=("KAFKA", "ANALYSIS"),
            ) as scenario:
                scenario.add_measurement("analysisLag", 9, phase="FAULT", unit="records")
                raise RuntimeError("controlled failure")

        record = dataset.to_dict()["scenarios"][0]
        self.assertEqual("FAILED", record["outcome"])
        self.assertEqual("RuntimeError", record["failure"]["type"])
        self.assertEqual(9, record["evidence"]["measurements"][0]["value"])
        self.assertIsNotNone(record["window"]["endedAt"])

    def test_writer_emits_versioned_artifact_and_sha256_helper_is_stable(self):
        dataset = evidence.ScenarioEvidenceDataset("unit-test-runner", {"namespace": "test"})
        with dataset.scenario("normal", "NORMAL_OPERATION", fault_injected=False):
            pass

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "evidence.json"
            dataset.write(output)
            payload = json.loads(output.read_text(encoding="utf-8"))
            digest = evidence.sha256_file(output)

        self.assertEqual(1, payload["schemaVersion"])
        self.assertEqual("signalharvester-operational-scenario-evidence", payload["artifactType"])
        self.assertEqual("unit-test-runner", payload["source"]["runner"])
        self.assertEqual(64, len(digest))

    def test_evidence_kind_limits_reject_unbounded_growth(self):
        scenario = evidence.ScenarioEvidence(
            "normal",
            "NORMAL_OPERATION",
            False,
            (),
        )
        for index in range(evidence.MAX_ITEMS_PER_EVIDENCE_KIND):
            scenario.add_measurement(f"metric-{index}", index, phase="OBSERVED")
        with self.assertRaises(evidence.ScenarioEvidenceError):
            scenario.add_measurement("overflow", 1, phase="OBSERVED")


if __name__ == "__main__":
    unittest.main()
