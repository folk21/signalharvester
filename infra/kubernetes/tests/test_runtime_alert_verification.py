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
    "operational_runtime_alert_verification",
    EVALUATION / "run_runtime_alert_verification.py",
)
verification = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = verification
spec.loader.exec_module(verification)


class FakeAdmin:
    def __init__(self, alerts):
        self.alerts = alerts

    def request(self, method, path):
        if method != "GET" or path != verification.ALERTS_PATH:
            raise AssertionError((method, path))
        return 200, self.alerts


class Guard:
    def __init__(self):
        self.values = []

    def set(self, name, value):
        self.values.append((name, value))


class RuntimeAlertVerificationTest(unittest.TestCase):
    def test_runtime_environment_pins_current_alert_v1_and_disables_periodic_sampling(self):
        self.assertEqual(2, verification.ARTIFACT_SCHEMA_VERSION)
        self.assertEqual("deterministic-statistical-v1", verification.HEALTH_POLICY_VERSION)
        self.assertEqual("human-attention-v1", verification.ALERT_POLICY_VERSION)
        self.assertEqual("false", verification.ALERT_RUNTIME_ENV["SIGNALHARVESTER_OPERATIONS_HEALTH_SAMPLING_ENABLED"])
        self.assertEqual("true", verification.ALERT_RUNTIME_ENV["SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_ENABLED"])
        self.assertEqual(
            "3",
            verification.ALERT_RUNTIME_ENV[
                "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_DEGRADED_MIN_CONSECUTIVE_SNAPSHOTS"
            ],
        )
        self.assertEqual(
            "1",
            verification.ALERT_RUNTIME_ENV[
                "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_UNHEALTHY_MIN_CONSECUTIVE_SNAPSHOTS"
            ],
        )
        self.assertEqual(
            "2",
            verification.ALERT_RUNTIME_ENV[
                "SIGNALHARVESTER_OPERATIONS_ALERT_POLICY_HEALTHY_MIN_CONSECUTIVE_SNAPSHOTS_TO_RESOLVE"
            ],
        )

    def test_require_snapshot_rejects_wrong_health_status_or_policy(self):
        snapshot = {
            "id": "snapshot-1",
            "overallStatus": "DEGRADED",
            "policyVersion": verification.HEALTH_POLICY_VERSION,
        }
        self.assertIs(snapshot, verification.require_snapshot(snapshot, "DEGRADED", "FAULT"))
        with self.assertRaises(verification.AcceptanceError):
            verification.require_snapshot(snapshot, "UNHEALTHY", "FAULT")
        with self.assertRaises(verification.AcceptanceError):
            verification.require_snapshot(
                {**snapshot, "policyVersion": "deterministic-statistical-v2"}, "DEGRADED", "FAULT"
            )

    def test_baseline_stabilization_records_quality_findings_without_hiding_recovery(self):
        evidence = {}
        snapshots = [
            {
                "id": "unhealthy",
                "overallStatus": "UNHEALTHY",
                "policyVersion": verification.HEALTH_POLICY_VERSION,
            },
            {
                "id": "healthy-1",
                "overallStatus": "HEALTHY",
                "policyVersion": verification.HEALTH_POLICY_VERSION,
            },
            {
                "id": "healthy-2",
                "overallStatus": "HEALTHY",
                "policyVersion": verification.HEALTH_POLICY_VERSION,
            },
        ]
        open_alert = {"id": "baseline-alert", "state": "OPEN"}
        resolved_alert = {"id": "baseline-alert", "state": "RESOLVED"}

        def poll_until(_description, _timeout, _interval, predicate):
            for _ in range(3):
                if predicate():
                    return
            raise AssertionError("baseline should have stabilized")

        with (
            mock.patch.object(verification.resilience, "wait_until", side_effect=poll_until),
            mock.patch.object(
                verification.resilience,
                "capture_health_snapshot",
                side_effect=snapshots,
            ),
            mock.patch.object(
                verification,
                "fetch_alerts",
                side_effect=[[open_alert], [open_alert], [resolved_alert]],
            ),
        ):
            snapshot, alerts, findings = verification.stabilize_baseline(
                object(),
                set(),
                timeout=60.0,
                interval=5.0,
                evidence=evidence,
            )

        self.assertEqual("healthy-2", snapshot["id"])
        self.assertEqual([resolved_alert], alerts)
        self.assertTrue(evidence["stabilized"])
        self.assertFalse(evidence["clean"])
        self.assertEqual(["unhealthy"], evidence["unexpectedHealthSnapshotIds"])
        self.assertEqual(["baseline-alert"], evidence["createdAlertIds"])
        self.assertTrue(evidence["unexpectedAttention"])
        self.assertEqual(3, len(evidence["attempts"]))
        self.assertEqual(
            ["BASELINE_HEALTH_DEGRADATION", "BASELINE_HUMAN_ATTENTION"],
            [finding["type"] for finding in findings],
        )

    def test_clean_baseline_requires_two_consecutive_healthy_snapshots(self):
        evidence = {}
        snapshots = [
            {
                "id": "healthy-1",
                "overallStatus": "HEALTHY",
                "policyVersion": verification.HEALTH_POLICY_VERSION,
            },
            {
                "id": "healthy-2",
                "overallStatus": "HEALTHY",
                "policyVersion": verification.HEALTH_POLICY_VERSION,
            },
        ]

        def poll_until(_description, _timeout, _interval, predicate):
            self.assertFalse(predicate())
            self.assertTrue(predicate())

        with (
            mock.patch.object(verification.resilience, "wait_until", side_effect=poll_until),
            mock.patch.object(
                verification.resilience,
                "capture_health_snapshot",
                side_effect=snapshots,
            ),
            mock.patch.object(verification, "fetch_alerts", side_effect=[[], []]),
        ):
            snapshot, alerts, findings = verification.stabilize_baseline(
                object(),
                set(),
                timeout=60.0,
                interval=5.0,
                evidence=evidence,
            )

        self.assertEqual("healthy-2", snapshot["id"])
        self.assertEqual([], alerts)
        self.assertEqual([], findings)
        self.assertTrue(evidence["stabilized"])
        self.assertTrue(evidence["clean"])
        self.assertEqual(2, len(evidence["attempts"]))

    def test_no_attention_phase_rejects_any_new_persisted_alert(self):
        baseline_ids = {"existing"}
        verification.require_no_new_alerts(
            FakeAdmin([{"id": "existing", "state": "RESOLVED"}]), baseline_ids, "TRANSIENT_FAULT"
        )
        with self.assertRaises(verification.AcceptanceError):
            verification.require_no_new_alerts(
                FakeAdmin([
                    {"id": "new", "state": "OPEN"},
                    {"id": "existing", "state": "RESOLVED"},
                ]),
                baseline_ids,
                "TRANSIENT_FAULT",
            )

    def test_critical_alert_lifecycle_keeps_one_identity_through_resolution(self):
        baseline_ids = {"old"}
        opened = {
            "id": "alert-1",
            "state": "OPEN",
            "severity": "CRITICAL",
            "reason": "UNHEALTHY",
            "policyVersion": verification.ALERT_POLICY_VERSION,
            "latestSnapshotId": "critical-snapshot",
            "latestHealthStatus": "UNHEALTHY",
            "resolvedAt": None,
        }
        result = verification.require_lifecycle_alert(
            FakeAdmin([opened, {"id": "old", "state": "RESOLVED"}]),
            baseline_ids,
            expected_id=None,
            expected_state="OPEN",
            expected_snapshot_id="critical-snapshot",
            expected_health_status="UNHEALTHY",
            phase="CRITICAL_FAULT",
        )
        self.assertEqual("alert-1", result["id"])

        still_open = {
            **opened,
            "latestSnapshotId": "healthy-1",
            "latestHealthStatus": "HEALTHY",
        }
        verification.require_lifecycle_alert(
            FakeAdmin([still_open, {"id": "old", "state": "RESOLVED"}]),
            baseline_ids,
            expected_id="alert-1",
            expected_state="OPEN",
            expected_snapshot_id="healthy-1",
            expected_health_status="HEALTHY",
            phase="CRITICAL_RECOVERY_1",
        )

        resolved = {
            **still_open,
            "state": "RESOLVED",
            "latestSnapshotId": "healthy-2",
            "resolvedAt": "2026-10-12T00:00:00Z",
        }
        verification.require_lifecycle_alert(
            FakeAdmin([resolved, {"id": "old", "state": "RESOLVED"}]),
            baseline_ids,
            expected_id="alert-1",
            expected_state="RESOLVED",
            expected_snapshot_id="healthy-2",
            expected_health_status="HEALTHY",
            phase="CRITICAL_RECOVERY_2",
        )

    def test_lifecycle_validation_rejects_duplicate_new_alerts(self):
        alert = {
            "state": "OPEN",
            "severity": "CRITICAL",
            "reason": "UNHEALTHY",
            "policyVersion": verification.ALERT_POLICY_VERSION,
            "latestSnapshotId": "snapshot",
            "latestHealthStatus": "UNHEALTHY",
            "resolvedAt": None,
        }
        with self.assertRaises(verification.AcceptanceError):
            verification.require_lifecycle_alert(
                FakeAdmin([{**alert, "id": "one"}, {**alert, "id": "two"}]),
                set(),
                expected_id=None,
                expected_state="OPEN",
                expected_snapshot_id="snapshot",
                expected_health_status="UNHEALTHY",
                phase="CRITICAL_FAULT",
            )

    def test_backlog_capture_and_recovery_use_hold_then_recovery_poll_intervals(self):
        guard = Guard()
        with (
            mock.patch.object(
                verification.calibration,
                "prepare_outbox_backlog",
                return_value=([("profile", "source", 300)], {"pending": 300.0, "oldestAgeSeconds": 10.0}, 300),
            ),
            mock.patch.object(
                verification.resilience,
                "capture_health_snapshot",
                side_effect=[
                    {
                        "id": "fault",
                        "overallStatus": "DEGRADED",
                        "policyVersion": verification.HEALTH_POLICY_VERSION,
                    },
                    {
                        "id": "recovery",
                        "overallStatus": "HEALTHY",
                        "policyVersion": verification.HEALTH_POLICY_VERSION,
                    },
                ],
            ),
            mock.patch.object(verification.calibration, "wait_for_workload_results"),
            mock.patch.object(verification.calibration, "wait_for_outbox_recovery"),
        ):
            snapshot, _, _, workloads = verification.build_backlog_and_capture(
                runner=object(),
                env_guard=guard,
                admin=object(),
                tracker=object(),
                prometheus_port=19092,
                total_items=300,
                baseline_pending=0,
                timeout=30.0,
                label="TRANSIENT",
                expected_status="DEGRADED",
            )
            recovery = verification.recover_backlog(
                runner=object(),
                env_guard=guard,
                admin=object(),
                workloads=workloads,
                prometheus_port=19092,
                baseline_pending=0,
                timeout=30.0,
                phase="RECOVERY",
            )

        self.assertEqual("fault", snapshot["id"])
        self.assertEqual("recovery", recovery["id"])
        self.assertEqual(
            [
                ("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", "10m"),
                ("SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL", "1s"),
            ],
            guard.values,
        )

    def test_successful_scenarios_still_fail_acceptance_when_baseline_has_quality_findings(self):
        report = {
            "scenarioOutcome": "RUNNING",
            "outcome": "RUNNING",
            "failure": None,
            "qualityFindings": [{"type": "BASELINE_HUMAN_ATTENTION"}],
        }

        result = verification.finalize_successful_scenarios(report)

        self.assertEqual(1, result)
        self.assertEqual("PASSED", report["scenarioOutcome"])
        self.assertEqual("FAILED", report["outcome"])
        self.assertEqual("BaselineQualityFinding", report["failure"]["type"])
        self.assertIn("BASELINE_HUMAN_ATTENTION", report["failure"]["message"])

    def test_parser_rejects_nonpositive_timeouts_and_intervals(self):
        invalid_arguments = [
            ["--scenario-timeout", "0"],
            ["--baseline-stabilization-timeout", "0"],
            ["--baseline-stabilization-interval", "0"],
        ]
        for arguments in invalid_arguments:
            with self.subTest(arguments=arguments):
                with contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        verification.parse_args(arguments)


if __name__ == "__main__":
    unittest.main()
