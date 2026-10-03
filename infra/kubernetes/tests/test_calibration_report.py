import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"
if str(EVALUATION) not in sys.path:
    sys.path.insert(0, str(EVALUATION))

spec = importlib.util.spec_from_file_location("operational_calibration_report", EVALUATION / "calibration_report.py")
calibration = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = calibration
spec.loader.exec_module(calibration)


def marker(phase, changed_at):
    return {"phase": phase, "change": {"id": f"change-{phase}", "changedAt": changed_at}}


def snapshot(phase, snapshot_id, generated_at, status, policy_version, components=None):
    return {
        "phase": phase,
        "snapshot": {
            "id": snapshot_id,
            "generatedAt": generated_at,
            "overallStatus": status,
            "policyVersion": policy_version,
            "componentStatuses": components or {},
        },
    }


def scenario(*, scenario_id, fault, snapshots, affected=(), markers=(), label=None):
    return {
        "scenarioId": scenario_id,
        "scenarioRunId": f"run-{scenario_id}",
        "label": label or ("FAULT" if fault else "NORMAL_OPERATION"),
        "groundTruth": {"faultInjected": fault, "affectedSubsystems": list(affected)},
        "window": {
            "startedAt": "2026-10-01T10:00:00+00:00",
            "endedAt": "2026-10-01T10:01:00+00:00",
        },
        "outcome": "PASSED",
        "failure": None,
        "evidence": {
            "changeMarkers": list(markers),
            "healthSnapshots": list(snapshots),
            "measurements": [],
            "logs": [],
            "traces": [],
            "artifacts": [],
        },
        "limitations": [],
    }


def dataset(dataset_run_id, policy_version, *, suffix=""):
    return {
        "schemaVersion": 1,
        "artifactType": "signalharvester-operational-scenario-evidence",
        "datasetRunId": dataset_run_id,
        "generatedAt": "2026-10-01T10:02:00+00:00",
        "source": {"runner": "unit-test", "environment": {}},
        "scenarios": [
            scenario(
                scenario_id=f"fault{suffix}",
                fault=True,
                label="KAFKA_LAG",
                affected=("KAFKA",),
                markers=(
                    marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                    marker("FAULT_END", "2026-10-01T10:00:30+00:00"),
                ),
                snapshots=(
                    snapshot("BEFORE", f"before{suffix}", "2026-10-01T10:00:05+00:00", "HEALTHY", policy_version),
                    snapshot(
                        "FAULT",
                        f"fault{suffix}",
                        "2026-10-01T10:00:12+00:00",
                        "DEGRADED",
                        policy_version,
                        {"kafka": "DEGRADED"},
                    ),
                    snapshot("AFTER", f"after{suffix}", "2026-10-01T10:00:35+00:00", "HEALTHY", policy_version),
                ),
            ),
            scenario(
                scenario_id=f"normal{suffix}",
                fault=False,
                snapshots=(
                    snapshot("BEFORE", f"normal-before{suffix}", "2026-10-01T10:00:05+00:00", "HEALTHY", policy_version),
                    snapshot("AFTER", f"normal-after{suffix}", "2026-10-01T10:00:15+00:00", "HEALTHY", policy_version),
                ),
            ),
        ],
    }


def investigation_artifact(scenario_run_id, snapshot_id, *, max_tool_calls=8):
    return {
        "schemaVersion": 1,
        "artifactType": "signalharvester-assisted-investigation-evidence",
        "generatedAt": "2026-10-01T10:03:00+00:00",
        "source": {"runner": "unit-test", "provider": "fake", "model": "deterministic-v1"},
        "budgets": {
            "maxToolCalls": max_tool_calls,
            "maxRounds": 4,
            "maxInvestigationDurationMs": 45000,
        },
        "trials": [
            {
                "trialId": f"trial-{snapshot_id}",
                "scenarioRunId": scenario_run_id,
                "snapshotId": snapshot_id,
                "status": "COMPLETED",
                "execution": {"toolCallCount": 1, "roundCount": 1, "durationMs": 200},
                "allowedEvidenceReferences": [f"health-snapshot:{snapshot_id}"],
                "discoveredEvidenceReferences": [],
                "assessment": {
                    "snapshotId": snapshot_id,
                    "source": "PROVIDER",
                    "provider": "fake",
                    "model": "deterministic-v1",
                    "summary": "Kafka lag is visible.",
                    "suspectedSubsystems": ["KAFKA"],
                    "confidence": 0.9,
                    "observations": ["lag"],
                    "hypotheses": ["consumer backlog"],
                    "evidenceReferences": [f"health-snapshot:{snapshot_id}"],
                    "recommendedChecks": ["check consumer lag"],
                    "humanAttentionSuggested": True,
                },
                "claimAnnotations": [],
            }
        ],
    }


class OperationalCalibrationReportTest(unittest.TestCase):
    def test_groups_repeated_health_runs_and_sweeps_alert_policy_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            one = root / "one.json"
            two = root / "two.json"
            one.write_text(json.dumps(dataset("dataset-1", "health-v1", suffix="-1")), encoding="utf-8")
            two.write_text(json.dumps(dataset("dataset-2", "health-v1", suffix="-2")), encoding="utf-8")
            policies = calibration._alert_policies("alert-v1", (1, 2), (1,), (1,), (0,))

            report = calibration.build_calibration([one, two], policies)

        self.assertEqual("signalharvester-operational-intelligence-calibration", report["artifactType"])
        self.assertEqual("MANUAL_EVIDENCE_REVIEW", report["calibration"]["selectionMode"])
        self.assertEqual(1, len(report["healthObserved"]))
        self.assertEqual(2, report["healthObserved"][0]["datasetRuns"])
        self.assertEqual(2, report["healthObserved"][0]["passedScenarioLabels"]["KAFKA_LAG"])
        self.assertEqual(1.0, report["healthObserved"][0]["summary"]["detectionRate"])
        self.assertEqual(2, len(report["alertPolicyCandidates"]))
        self.assertEqual(
            {1, 2},
            {
                candidate["alertPolicy"]["degradedMinConsecutiveSnapshots"]
                for candidate in report["alertPolicyCandidates"]
            },
        )

    def test_keeps_alert_sweeps_separate_for_different_health_policy_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            one = root / "one.json"
            two = root / "two.json"
            one.write_text(json.dumps(dataset("dataset-1", "health-v1", suffix="-1")), encoding="utf-8")
            two.write_text(json.dumps(dataset("dataset-2", "health-v2", suffix="-2")), encoding="utf-8")
            policy = calibration._alert_policies("alert-v1", (1,), (1,), (1,), (0,))

            report = calibration.build_calibration([one, two], policy)

        self.assertEqual({"health-v1", "health-v2"}, {item["healthPolicyVersion"] for item in report["healthObserved"]})
        self.assertEqual(2, len(report["alertPolicyCandidates"]))

    def test_rejects_mixed_health_policy_versions_inside_one_dataset_run(self):
        mixed = dataset("dataset-1", "health-v1")
        mixed["scenarios"][1]["evidence"]["healthSnapshots"][0]["snapshot"]["policyVersion"] = "health-v2"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mixed.json"
            path.write_text(json.dumps(mixed), encoding="utf-8")
            policies = calibration._alert_policies("alert-v1", (1,), (1,), (1,), (0,))
            with self.assertRaises(calibration.CalibrationError):
                calibration.build_calibration([path], policies)

    def test_groups_assisted_trials_by_provider_model_and_runtime_budgets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scenario_path = root / "scenario.json"
            investigation_path = root / "investigation.json"
            scenario_value = dataset("dataset-1", "health-v1")
            fault = scenario_value["scenarios"][0]
            fault_snapshot_id = fault["evidence"]["healthSnapshots"][1]["snapshot"]["id"]
            scenario_path.write_text(json.dumps(scenario_value), encoding="utf-8")
            investigation_path.write_text(
                json.dumps(investigation_artifact(fault["scenarioRunId"], fault_snapshot_id)),
                encoding="utf-8",
            )
            policies = calibration._alert_policies("alert-v1", (1,), (1,), (1,), (0,))

            report = calibration.build_calibration([scenario_path], policies, [investigation_path])

        self.assertEqual(1, len(report["assistedInvestigationCandidates"]))
        candidate = report["assistedInvestigationCandidates"][0]
        self.assertEqual("fake", candidate["provider"])
        self.assertEqual(8, candidate["budgets"]["maxToolCalls"])
        self.assertEqual(1, candidate["trialCount"])
        self.assertEqual(1.0, candidate["summary"]["subsystemIdentification"]["affectedSubsystemCoverage"])


    def test_rejects_duplicate_dataset_identity_across_repeated_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            one = root / "one.json"
            two = root / "two.json"
            one.write_text(json.dumps(dataset("dataset-1", "health-v1", suffix="-1")), encoding="utf-8")
            two.write_text(json.dumps(dataset("dataset-1", "health-v1", suffix="-2")), encoding="utf-8")
            policies = calibration._alert_policies("alert-v1", (1,), (1,), (1,), (0,))
            with self.assertRaises(calibration.CalibrationError):
                calibration.build_calibration([one, two], policies)

    def test_rejects_alert_policy_grid_larger_than_bound(self):
        with self.assertRaises(calibration.CalibrationError):
            calibration._alert_policies(
                "alert-v1",
                (1, 2, 3, 4),
                (1, 2, 3, 4),
                (1, 2, 3, 4),
                (0, 60),
            )

    def test_parses_unique_integer_candidate_values(self):
        self.assertEqual((3, 2), calibration._parse_int_values("3,2,3", "values", minimum=1))
        with self.assertRaises(calibration.CalibrationError):
            calibration._parse_int_values("1,-1", "values", minimum=0)


if __name__ == "__main__":
    unittest.main()
