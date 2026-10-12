import contextlib
import importlib.util
import io
import sys
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"

spec = importlib.util.spec_from_file_location(
    "operational_health_alert_calibration",
    EVALUATION / "run_health_alert_calibration.py",
)
calibration = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = calibration
spec.loader.exec_module(calibration)


class HealthAlertCalibrationTest(unittest.TestCase):
    def test_fixed_backlog_cases_preserve_calibration_contract(self):
        self.assertEqual(300, calibration.TRANSIENT_OUTBOX_ROWS)
        self.assertEqual(1200, calibration.CRITICAL_OUTBOX_ROWS)
        self.assertEqual("10m", calibration.OUTBOX_HOLD_POLL_INTERVAL)
        self.assertEqual("1s", calibration.OUTBOX_RECOVERY_POLL_INTERVAL)
        self.assertEqual("1s", calibration.OUTBOX_METRICS_INTERVAL)
        self.assertEqual(120.0, calibration.TRANSIENT_MAX_OLDEST_PENDING_AGE_SECONDS)

    def test_workload_partition_keeps_fixture_and_results_bounds(self):
        self.assertEqual([200, 100], calibration.partition_workload(300))
        self.assertEqual([200] * 6, calibration.partition_workload(1200))
        with self.assertRaises(ValueError):
            calibration.partition_workload(0)
        with self.assertRaises(ValueError):
            calibration.partition_workload(1, 501)

    def test_prometheus_scalar_returns_maximum_finite_vector_value(self):
        rows = [
            {"value": [1, "12.5"]},
            {"value": [1, "9"]},
            {"value": [1, "NaN"]},
        ]
        with mock.patch.object(calibration.resilience, "prometheus_query", return_value=rows):
            self.assertEqual(12.5, calibration.prometheus_scalar(19092, "query"))

    def test_outbox_scenario_records_explicit_detector_and_alert_expectations(self):
        dataset = calibration.scenario_evidence.ScenarioEvidenceDataset("unit-test", {})

        class Guard:
            def __init__(self):
                self.values = []

            def set(self, name, value):
                self.values.append((name, value))

        guard = Guard()

        def record_start(admin, scenario):
            scenario.add_health_snapshot(
                "BEFORE",
                {"id": "before", "overallStatus": "HEALTHY", "generatedAt": "2026-10-07T00:00:00+00:00"},
            )

        def record_end(admin, scenario):
            scenario.add_health_snapshot(
                "AFTER",
                {"id": "after", "overallStatus": "HEALTHY", "generatedAt": "2026-10-07T00:01:00+00:00"},
            )

        with (
            mock.patch.object(
                calibration,
                "prepare_outbox_backlog",
                return_value=([("profile", "source", 300)], {"pending": 300.0, "oldestAgeSeconds": 12.0}, 300),
            ),
            mock.patch.object(calibration, "wait_for_workload_results"),
            mock.patch.object(calibration, "wait_for_outbox_recovery"),
            mock.patch.object(calibration.resilience, "record_scenario_start", side_effect=record_start),
            mock.patch.object(calibration.resilience, "record_scenario_fault_boundary"),
            mock.patch.object(calibration.resilience, "capture_health_snapshot", return_value={
                "id": "fault",
                "overallStatus": "DEGRADED",
                "generatedAt": "2026-10-07T00:00:30+00:00",
            }),
            mock.patch.object(calibration.resilience, "record_scenario_end", side_effect=record_end),
        ):
            calibration.run_outbox_scenario(
                runner=object(),
                env_guard=guard,
                admin=object(),
                tracker=object(),
                evidence_dataset=dataset,
                prometheus_port=19092,
                scenario_id="transient",
                label="OUTBOX_BACKLOG_TRANSIENT",
                total_items=300,
                baseline_pending=0,
                health_expectation=calibration.scenario_evidence.HEALTH_EXPECTATION_DETECT,
                alert_expectation=calibration.scenario_evidence.ALERT_EXPECTATION_NO_ATTENTION,
                timeout=30.0,
                transient=True,
            )

        record = dataset.to_dict()["scenarios"][0]
        self.assertEqual(
            {"health": "DETECT", "alert": "NO_ATTENTION"}, record["evaluationExpectations"]
        )
        self.assertEqual(["ANALYSIS_OUTBOX"], record["groundTruth"]["affectedSubsystems"])
        self.assertEqual("DEGRADED", record["evidence"]["healthSnapshots"][1]["snapshot"]["overallStatus"])
        self.assertEqual(
            [("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", "1s")], guard.values
        )
        measurements = {item["name"]: item["value"] for item in record["evidence"]["measurements"]}
        self.assertEqual(300, measurements["targetPendingOutboxRows"])
        self.assertEqual(300.0, measurements["prometheusPendingAnalysisOutboxRows"])

    def test_transient_scenario_rejects_critical_age_contamination(self):
        dataset = calibration.scenario_evidence.ScenarioEvidenceDataset("unit-test", {})
        with (
            mock.patch.object(
                calibration,
                "prepare_outbox_backlog",
                return_value=([], {"pending": 300.0, "oldestAgeSeconds": 120.0}, 300),
            ),
            mock.patch.object(calibration.resilience, "record_scenario_start"),
        ):
            with self.assertRaises(calibration.AcceptanceError):
                calibration.run_outbox_scenario(
                    runner=object(),
                    env_guard=object(),
                    admin=object(),
                    tracker=object(),
                    evidence_dataset=dataset,
                    prometheus_port=19092,
                    scenario_id="transient",
                    label="OUTBOX_BACKLOG_TRANSIENT",
                    total_items=300,
                    baseline_pending=0,
                    health_expectation=calibration.scenario_evidence.HEALTH_EXPECTATION_DETECT,
                    alert_expectation=calibration.scenario_evidence.ALERT_EXPECTATION_NO_ATTENTION,
                    timeout=30.0,
                    transient=True,
                )
        self.assertEqual("FAILED", dataset.to_dict()["scenarios"][0]["outcome"])

    def test_parser_rejects_nonpositive_scenario_timeout(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                calibration.parse_args(["--scenario-timeout", "0"])


if __name__ == "__main__":
    unittest.main()
