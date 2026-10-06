import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"

spec = importlib.util.spec_from_file_location(
    "operational_calibration_campaign",
    EVALUATION / "run_calibration_campaign.py",
)
campaign = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = campaign
spec.loader.exec_module(campaign)


def scenario_artifact(dataset_run_id, *, outcome="PASSED"):
    return {
        "schemaVersion": 1,
        "artifactType": "signalharvester-operational-scenario-evidence",
        "datasetRunId": dataset_run_id,
        "generatedAt": "2026-10-03T00:00:00+00:00",
        "source": {"runner": "unit-test", "environment": {}},
        "scenarios": [
            {
                "scenarioId": "normal",
                "scenarioRunId": f"scenario-{dataset_run_id}",
                "label": "NORMAL_OPERATION",
                "groundTruth": {"faultInjected": False, "affectedSubsystems": []},
                "window": {
                    "startedAt": "2026-10-03T00:00:00+00:00",
                    "endedAt": "2026-10-03T00:01:00+00:00",
                },
                "outcome": outcome,
                "failure": None,
                "evidence": {
                    "changeMarkers": [],
                    "healthSnapshots": [
                        {
                            "phase": "AFTER",
                            "snapshot": {
                                "id": f"snapshot-{dataset_run_id}",
                                "generatedAt": "2026-10-03T00:00:30+00:00",
                                "overallStatus": "HEALTHY",
                                "policyVersion": "health-v1",
                                "componentStatuses": {},
                            },
                        }
                    ],
                    "measurements": [],
                    "logs": [],
                    "traces": [],
                    "artifacts": [],
                },
                "limitations": [],
            }
        ],
    }


class CalibrationCampaignTest(unittest.TestCase):
    def test_runs_repeated_resilience_evidence_before_calibration(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "campaign"
            args = campaign.parse_args(["--repeats", "3", "--output-dir", str(output_dir)])
            commands = []
            dataset_index = 0

            def execute(command):
                nonlocal dataset_index
                commands.append(list(command))
                program = Path(command[1]).name
                if program == "run_acceptance.py":
                    dataset_index += 1
                    evidence = Path(command[command.index("--evidence-output") + 1])
                    if not evidence.is_absolute():
                        evidence = campaign.ROOT / evidence
                    evidence.parent.mkdir(parents=True, exist_ok=True)
                    evidence.write_text(json.dumps(scenario_artifact(f"dataset-{dataset_index}")), encoding="utf-8")
                    return 0
                if program == "calibration_report.py":
                    report = Path(command[command.index("--output") + 1])
                    if not report.is_absolute():
                        report = campaign.ROOT / report
                    report.write_text(
                        json.dumps(
                            {
                                "artifactType": "signalharvester-operational-intelligence-calibration",
                                "calibration": {"selectionMode": "MANUAL_EVIDENCE_REVIEW"},
                            }
                        ),
                        encoding="utf-8",
                    )
                    return 0
                raise AssertionError(command)

            with contextlib.redirect_stdout(io.StringIO()):
                manifest_path = campaign.run_campaign(args, campaign_id="campaign-test", execute=execute)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        self.assertEqual("PASSED", manifest["status"])
        self.assertEqual(3, len(manifest["scenarioEvidence"]))
        self.assertEqual(3, len(manifest["runs"]))
        self.assertEqual(4, len(commands))
        self.assertTrue(all(Path(command[1]).name == "run_acceptance.py" for command in commands[:3]))
        self.assertEqual("calibration_report.py", Path(commands[-1][1]).name)
        self.assertEqual("MANUAL_EVIDENCE_REVIEW", manifest["calibrationReport"]["selectionMode"])

    def test_can_include_one_capacity_run_per_repeat(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "campaign"
            args = campaign.parse_args(
                ["--repeats", "2", "--include-capacity", "--output-dir", str(output_dir)]
            )
            commands = []
            dataset_index = 0

            def execute(command):
                nonlocal dataset_index
                commands.append(list(command))
                program = Path(command[1]).name
                if program in {"run_acceptance.py", "run_baseline.py"}:
                    dataset_index += 1
                    evidence = Path(command[command.index("--evidence-output") + 1])
                    if not evidence.is_absolute():
                        evidence = campaign.ROOT / evidence
                    evidence.parent.mkdir(parents=True, exist_ok=True)
                    evidence.write_text(json.dumps(scenario_artifact(f"dataset-{dataset_index}")), encoding="utf-8")
                    if program == "run_baseline.py":
                        report = Path(command[command.index("--output") + 1])
                        if not report.is_absolute():
                            report = campaign.ROOT / report
                        report.write_text("{}", encoding="utf-8")
                    return 0
                if program == "calibration_report.py":
                    report = Path(command[command.index("--output") + 1])
                    if not report.is_absolute():
                        report = campaign.ROOT / report
                    report.write_text(
                        json.dumps(
                            {
                                "artifactType": "signalharvester-operational-intelligence-calibration",
                                "calibration": {"selectionMode": "MANUAL_EVIDENCE_REVIEW"},
                            }
                        ),
                        encoding="utf-8",
                    )
                    return 0
                raise AssertionError(command)

            with contextlib.redirect_stdout(io.StringIO()):
                manifest_path = campaign.run_campaign(args, campaign_id="campaign-capacity", execute=execute)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        self.assertEqual(["RESILIENCE", "CAPACITY", "RESILIENCE", "CAPACITY"], [item["kind"] for item in manifest["runs"]])
        self.assertEqual(4, len(manifest["scenarioEvidence"]))
        self.assertEqual(5, len(commands))

    def test_failed_live_run_keeps_failed_manifest_and_does_not_calibrate(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "campaign"
            args = campaign.parse_args(["--repeats", "2", "--output-dir", str(output_dir)])
            commands = []

            def execute(command):
                commands.append(list(command))
                if "--repair-baseline-only" in command:
                    return 0
                return 7

            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(campaign.CampaignError):
                    campaign.run_campaign(args, campaign_id="campaign-failed", execute=execute)
            manifest = json.loads((output_dir / "campaign-manifest.json").read_text(encoding="utf-8"))

        self.assertEqual("FAILED", manifest["status"])
        self.assertIn("exit code 7", manifest["failure"])
        self.assertEqual(2, len(commands))
        self.assertIn("--repair-baseline-only", commands[1])
        self.assertEqual("PASSED", manifest["runs"][0]["baselineRecovery"]["status"])
        self.assertIsNone(manifest["calibrationReport"])

    def test_failed_resilience_baseline_recovery_is_reported_in_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "campaign"
            args = campaign.parse_args(["--repeats", "2", "--output-dir", str(output_dir)])

            def execute(command):
                return 9 if "--repair-baseline-only" in command else 7

            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(campaign.CampaignError):
                    campaign.run_campaign(args, campaign_id="campaign-recovery-failed", execute=execute)
            manifest = json.loads((output_dir / "campaign-manifest.json").read_text(encoding="utf-8"))

        self.assertEqual("FAILED", manifest["status"])
        self.assertIn("baseline recovery also failed with exit code 9", manifest["failure"])
        self.assertEqual("FAILED", manifest["runs"][0]["baselineRecovery"]["status"])
        self.assertEqual(9, manifest["runs"][0]["baselineRecovery"]["exitCode"])

    def test_rejects_unbounded_repeat_counts_and_invalid_capacity_replicas(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                campaign.parse_args(["--repeats", "1"])
            with self.assertRaises(SystemExit):
                campaign.parse_args(["--repeats", str(campaign.MAX_REPEATS + 1)])
            with self.assertRaises(SystemExit):
                campaign.parse_args(["--capacity-replicas", "0"])


    def test_rejects_successful_runner_exit_when_evidence_contains_failed_scenario(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "campaign"
            args = campaign.parse_args(["--repeats", "2", "--output-dir", str(output_dir)])
            commands = []

            def execute(command):
                commands.append(list(command))
                if "--repair-baseline-only" in command:
                    return 0
                evidence = Path(command[command.index("--evidence-output") + 1])
                if not evidence.is_absolute():
                    evidence = campaign.ROOT / evidence
                evidence.parent.mkdir(parents=True, exist_ok=True)
                evidence.write_text(
                    json.dumps(scenario_artifact("dataset-failed", outcome="FAILED")),
                    encoding="utf-8",
                )
                return 0

            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(campaign.CampaignError):
                    campaign.run_campaign(args, campaign_id="campaign-inconsistent", execute=execute)
            manifest = json.loads((output_dir / "campaign-manifest.json").read_text(encoding="utf-8"))

        self.assertEqual("FAILED", manifest["status"])
        self.assertIn("non-passed evidence", manifest["failure"])
        self.assertEqual(2, len(commands))
        self.assertEqual("PASSED", manifest["runs"][0]["baselineRecovery"]["status"])

    def test_refuses_to_overwrite_nonempty_campaign_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "campaign"
            output_dir.mkdir()
            (output_dir / "existing.txt").write_text("keep", encoding="utf-8")
            args = campaign.parse_args(["--repeats", "2", "--output-dir", str(output_dir)])

            with self.assertRaises(campaign.CampaignError):
                campaign.run_campaign(args, campaign_id="campaign-existing", execute=lambda _: 0)


if __name__ == "__main__":
    unittest.main()
