import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"

spec = importlib.util.spec_from_file_location("operational_offline_evaluator", EVALUATION / "offline_evaluator.py")
evaluator = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = evaluator
spec.loader.exec_module(evaluator)


def marker(phase, changed_at):
    return {"phase": phase, "change": {"id": f"change-{phase}", "changedAt": changed_at}}


def snapshot(phase, snapshot_id, generated_at, status, components=None):
    return {
        "phase": phase,
        "snapshot": {
            "id": snapshot_id,
            "generatedAt": generated_at,
            "overallStatus": status,
            "componentStatuses": components or {},
        },
    }


def scenario(
    *,
    fault,
    snapshots,
    markers=None,
    affected=(),
    outcome="PASSED",
    scenario_id="scenario",
    health_expectation="DESCRIPTIVE_ONLY",
    alert_expectation="DESCRIPTIVE_ONLY",
    include_expectations=True,
):
    value = {
        "scenarioId": scenario_id,
        "scenarioRunId": f"run-{scenario_id}",
        "label": "FAULT" if fault else "NORMAL_OPERATION",
        "groundTruth": {"faultInjected": fault, "affectedSubsystems": list(affected)},
        "window": {
            "startedAt": "2026-10-01T10:00:00+00:00",
            "endedAt": "2026-10-01T10:01:00+00:00",
        },
        "outcome": outcome,
        "failure": None,
        "evidence": {
            "changeMarkers": list(markers or []),
            "healthSnapshots": list(snapshots),
            "measurements": [],
            "logs": [],
            "traces": [],
            "artifacts": [],
        },
        "limitations": [],
    }
    if include_expectations:
        value["evaluationExpectations"] = {
            "health": health_expectation,
            "alert": alert_expectation,
        }
    return value


class OfflineOperationalEvaluatorTest(unittest.TestCase):
    def test_fault_detection_uses_explicit_fault_window_and_reports_subsystem_coverage(self):
        value = scenario(
            fault=True,
            affected=("KAFKA", "ANALYSIS"),
            health_expectation="DETECT",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:30+00:00"),
            ),
            snapshots=(
                snapshot("BEFORE", "before", "2026-10-01T10:00:05+00:00", "HEALTHY"),
                snapshot(
                    "FAULT",
                    "fault",
                    "2026-10-01T10:00:14+00:00",
                    "DEGRADED",
                    {"eventing": "DEGRADED", "analysis": "HEALTHY"},
                ),
                snapshot("AFTER", "after", "2026-10-01T10:00:35+00:00", "HEALTHY"),
            ),
        )

        result = evaluator.evaluate_health_scenario(value)

        self.assertTrue(result["evaluable"])
        self.assertTrue(result["detectionSuccess"])
        self.assertEqual("FAULT_MARKERS", result["timingSource"])
        self.assertEqual(4000, result["timeToDetectionMs"])
        self.assertEqual(["KAFKA"], result["matchedAffectedSubsystems"])
        self.assertEqual(["ANALYSIS"], result["missedAffectedSubsystems"])
        self.assertEqual(0.5, result["affectedSubsystemCoverage"])
        self.assertTrue(result["recoveredHealthy"])

    def test_fault_with_preexisting_degradation_is_excluded_from_detection_rate(self):
        value = scenario(
            fault=True,
            affected=("KAFKA",),
            health_expectation="DETECT",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:30+00:00"),
            ),
            snapshots=(
                snapshot(
                    "BEFORE", "already-degraded", "2026-10-01T10:00:05+00:00", "DEGRADED",
                    {"eventing": "DEGRADED"},
                ),
                snapshot(
                    "FAULT", "fault", "2026-10-01T10:00:14+00:00", "DEGRADED",
                    {"eventing": "DEGRADED"},
                ),
            ),
        )

        result = evaluator.evaluate_health_scenario(value)

        self.assertFalse(result["evaluable"])
        self.assertFalse(result["scored"])
        self.assertIsNone(result["detectionSuccess"])
        self.assertEqual(["already-degraded"], result["preFaultDetectingSnapshotIds"])

    def test_fault_without_snapshot_inside_fault_window_is_not_counted_as_miss(self):
        value = scenario(
            fault=True,
            affected=("POSTGRESQL",),
            health_expectation="DETECT",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:30+00:00"),
            ),
            snapshots=(
                snapshot("BEFORE", "before", "2026-10-01T10:00:05+00:00", "HEALTHY"),
                snapshot("AFTER", "after", "2026-10-01T10:00:35+00:00", "HEALTHY"),
            ),
        )

        result = evaluator.evaluate_health_scenario(value)

        self.assertFalse(result["evaluable"])
        self.assertIsNone(result["detectionSuccess"])
        self.assertEqual(0, result["faultSnapshotCount"])

    def test_normal_operation_reports_detector_and_alert_false_positives(self):
        value = scenario(
            fault=False,
            health_expectation="REMAIN_HEALTHY",
            alert_expectation="NO_ATTENTION",
            snapshots=(
                snapshot("BEFORE", "one", "2026-10-01T10:00:05+00:00", "HEALTHY"),
                snapshot("AFTER", "two", "2026-10-01T10:00:15+00:00", "DEGRADED"),
                snapshot("AFTER", "three", "2026-10-01T10:00:16+00:00", "DEGRADED"),
                snapshot("AFTER", "four", "2026-10-01T10:00:17+00:00", "DEGRADED"),
            ),
        )

        health = evaluator.evaluate_health_scenario(value)
        alert = evaluator.evaluate_alert_projection_scenario(value, evaluator.AlertProjectionPolicy())

        self.assertTrue(health["falsePositive"])
        self.assertTrue(alert["attentionTriggered"])
        self.assertTrue(alert["falsePositive"])
        self.assertEqual("WARNING", alert["maxSeverity"])

    def test_alert_projection_applies_persistence_escalation_and_recovery_hysteresis(self):
        value = scenario(
            fault=True,
            affected=("KAFKA",),
            alert_expectation="ATTENTION_REQUIRED",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:25+00:00"),
            ),
            snapshots=(
                snapshot("BEFORE", "s0", "2026-10-01T10:00:05+00:00", "HEALTHY"),
                snapshot("FAULT", "s1", "2026-10-01T10:00:11+00:00", "DEGRADED"),
                snapshot("FAULT", "s2", "2026-10-01T10:00:12+00:00", "DEGRADED"),
                snapshot("FAULT", "s3", "2026-10-01T10:00:16+00:00", "DEGRADED"),
                snapshot("FAULT", "s4", "2026-10-01T10:00:18+00:00", "UNHEALTHY"),
                snapshot("AFTER", "s5", "2026-10-01T10:00:30+00:00", "HEALTHY"),
                snapshot("AFTER", "s6", "2026-10-01T10:00:31+00:00", "HEALTHY"),
            ),
        )

        result = evaluator.evaluate_alert_projection_scenario(value, evaluator.AlertProjectionPolicy())

        self.assertTrue(result["evaluable"])
        self.assertTrue(result["attentionTriggeredDuringFault"])
        self.assertEqual(6000, result["timeToAttentionMs"])
        self.assertEqual("CRITICAL", result["maxSeverity"])
        self.assertTrue(result["resolvedByFinalSnapshot"])
        self.assertEqual(["OPEN", "ESCALATE", "RESOLVE"], [event["action"] for event in result["events"]])

    def test_unhealthy_reopens_during_cooldown_while_degraded_does_not(self):
        policy = evaluator.AlertProjectionPolicy(
            degraded_min_consecutive=1,
            unhealthy_min_consecutive=1,
            healthy_min_consecutive_to_resolve=1,
            reopen_cooldown_seconds=900,
        )
        value = scenario(
            fault=True,
            alert_expectation="ATTENTION_REQUIRED",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:00+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:20+00:00"),
            ),
            snapshots=(
                snapshot("FAULT", "s1", "2026-10-01T10:00:01+00:00", "DEGRADED"),
                snapshot("FAULT", "s2", "2026-10-01T10:00:02+00:00", "HEALTHY"),
                snapshot("FAULT", "s3", "2026-10-01T10:00:03+00:00", "DEGRADED"),
                snapshot("FAULT", "s4", "2026-10-01T10:00:04+00:00", "UNHEALTHY"),
            ),
        )

        result = evaluator.evaluate_alert_projection_scenario(value, policy)

        actions = [(event["action"], event.get("severity")) for event in result["events"]]
        self.assertEqual([("OPEN", "WARNING"), ("RESOLVE", None), ("OPEN", "CRITICAL")], actions)

    def test_build_evaluation_aggregates_only_passed_evaluable_scenarios_and_hashes_inputs(self):
        good = scenario(
            fault=True,
            scenario_id="good",
            affected=("KAFKA",),
            health_expectation="DETECT",
            alert_expectation="ATTENTION_REQUIRED",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:20+00:00"),
            ),
            snapshots=(snapshot("FAULT", "fault", "2026-10-01T10:00:11+00:00", "UNHEALTHY", {"eventing": "UNHEALTHY"}),),
        )
        failed = scenario(fault=True, scenario_id="failed", snapshots=(), outcome="FAILED")
        dataset = {
            "schemaVersion": 1,
            "artifactType": "signalharvester-operational-scenario-evidence",
            "datasetRunId": "dataset-1",
            "generatedAt": "2026-10-01T10:02:00+00:00",
            "source": {"runner": "unit-test", "environment": {}},
            "scenarios": [good, failed],
        }

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(dataset), encoding="utf-8")
            report = evaluator.build_evaluation([path], evaluator.AlertProjectionPolicy())

        self.assertEqual(2, report["schemaVersion"])
        self.assertEqual("signalharvester-operational-intelligence-evaluation", report["artifactType"])
        self.assertEqual(64, len(report["inputs"][0]["sha256"]))
        self.assertEqual(2, report["summary"]["scenarios"]["total"])
        self.assertEqual(1, report["summary"]["scenarios"]["excludedNonPassed"])
        self.assertEqual(1.0, report["summary"]["health"]["detectionRate"])
        self.assertEqual(1.0, report["summary"]["alertProjection"]["attentionRate"])

    def test_legacy_evidence_without_expectations_is_descriptive_only(self):
        legacy = scenario(
            fault=True,
            snapshots=(snapshot("FAULT", "fault", "2026-10-01T10:00:11+00:00", "UNHEALTHY"),),
            include_expectations=False,
        )
        dataset = {
            "schemaVersion": 1,
            "artifactType": "signalharvester-operational-scenario-evidence",
            "datasetRunId": "dataset-legacy",
            "generatedAt": "2026-10-01T10:02:00+00:00",
            "source": {"runner": "unit-test", "environment": {}},
            "scenarios": [legacy],
        }

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.json"
            path.write_text(json.dumps(dataset), encoding="utf-8")
            report = evaluator.build_evaluation([path], evaluator.AlertProjectionPolicy())

        self.assertEqual(1, report["summary"]["scenarios"]["legacyExpectationDefaults"])
        self.assertEqual(1, report["summary"]["health"]["descriptiveOnlyScenarios"])
        self.assertIsNone(report["summary"]["health"]["detectionRate"])
        self.assertIsNone(report["summary"]["alertProjection"]["attentionRate"])
        self.assertFalse(report["scenarios"][0]["health"]["scored"])

    def test_subsystem_coverage_maps_ground_truth_to_health_component_vocabulary(self):
        value = scenario(
            fault=True,
            affected=("KAFKA", "RESULTS"),
            health_expectation="DETECT",
            markers=(
                marker("FAULT_START", "2026-10-01T10:00:10+00:00"),
                marker("FAULT_END", "2026-10-01T10:00:20+00:00"),
            ),
            snapshots=(
                snapshot(
                    "FAULT", "fault", "2026-10-01T10:00:11+00:00", "DEGRADED",
                    {"eventing": "DEGRADED"},
                ),
            ),
        )

        result = evaluator.evaluate_health_scenario(value)

        self.assertEqual(["KAFKA"], result["scorableGroundTruthAffectedSubsystems"])
        self.assertEqual(["RESULTS"], result["unmappedGroundTruthAffectedSubsystems"])
        self.assertEqual(["KAFKA"], result["matchedAffectedSubsystems"])
        self.assertEqual(1.0, result["affectedSubsystemCoverage"])

    def test_rejects_unsupported_evidence_schema(self):
        dataset = {
            "schemaVersion": 99,
            "artifactType": "signalharvester-operational-scenario-evidence",
            "datasetRunId": "dataset-1",
            "source": {"runner": "unit-test", "environment": {}},
            "scenarios": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(dataset), encoding="utf-8")
            with self.assertRaises(evaluator.EvaluationError):
                evaluator.build_evaluation([path], evaluator.AlertProjectionPolicy())


if __name__ == "__main__":
    unittest.main()
