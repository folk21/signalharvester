import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVALUATION = ROOT / "infra" / "kubernetes" / "evaluation"

spec = importlib.util.spec_from_file_location(
    "assisted_investigation_evaluator", EVALUATION / "assisted_investigation_evaluator.py"
)
evaluator = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = evaluator
spec.loader.exec_module(evaluator)


def snapshot(snapshot_id):
    return {
        "phase": "FAULT",
        "snapshot": {
            "id": snapshot_id,
            "generatedAt": "2026-10-01T10:00:10+00:00",
            "overallStatus": "DEGRADED",
            "componentStatuses": {},
        },
    }


def scenario_dataset(*, scenario_run_id="scenario-run-1", fault=True, affected=("KAFKA",), outcome="PASSED"):
    return {
        "schemaVersion": 1,
        "artifactType": "signalharvester-operational-scenario-evidence",
        "datasetRunId": "dataset-1",
        "generatedAt": "2026-10-01T10:02:00+00:00",
        "source": {"runner": "unit-test", "environment": {}},
        "scenarios": [
            {
                "scenarioId": "kafka-lag" if fault else "normal",
                "scenarioRunId": scenario_run_id,
                "label": "KAFKA_LAG" if fault else "NORMAL_OPERATION",
                "groundTruth": {"faultInjected": fault, "affectedSubsystems": list(affected)},
                "window": {
                    "startedAt": "2026-10-01T10:00:00+00:00",
                    "endedAt": "2026-10-01T10:01:00+00:00",
                },
                "outcome": outcome,
                "failure": None,
                "evidence": {
                    "changeMarkers": [],
                    "healthSnapshots": [snapshot("snapshot-1")],
                    "measurements": [],
                    "logs": [],
                    "traces": [],
                    "artifacts": [],
                },
                "limitations": [],
            }
        ],
    }


def assessment(*, snapshot_id="snapshot-1", suspected=("kafka",), citations=("health-snapshot:snapshot-1",), attention=True):
    return {
        "id": "assessment-1",
        "snapshotId": snapshot_id,
        "createdAt": "2026-10-01T10:00:20+00:00",
        "source": "PROVIDER",
        "provider": "fake",
        "model": "deterministic-v1",
        "summary": "Kafka lag is elevated.",
        "suspectedSubsystems": list(suspected),
        "confidence": 0.8,
        "observations": ["Lag increased."],
        "hypotheses": ["Analysis consumption is behind."],
        "evidenceReferences": list(citations),
        "recommendedChecks": ["Inspect consumer progress."],
        "humanAttentionSuggested": attention,
    }


def investigation_dataset(*, trials, max_tools=8, max_rounds=4, max_duration=45000):
    return {
        "schemaVersion": 1,
        "artifactType": "signalharvester-assisted-investigation-evidence",
        "generatedAt": "2026-10-01T10:03:00+00:00",
        "source": {"runner": "unit-test", "provider": "fake", "model": "deterministic-v1"},
        "budgets": {
            "maxToolCalls": max_tools,
            "maxRounds": max_rounds,
            "maxInvestigationDurationMs": max_duration,
        },
        "trials": list(trials),
    }


def trial(
    *,
    trial_id="trial-1",
    scenario_run_id="scenario-run-1",
    status="COMPLETED",
    value=None,
    allowed=("health-snapshot:snapshot-1", "prometheus:kafka-lag"),
    discovered=(),
    annotations=(),
    tool_calls=3,
    rounds=2,
    duration_ms=1200,
):
    result = {
        "trialId": trial_id,
        "scenarioRunId": scenario_run_id,
        "snapshotId": "snapshot-1",
        "status": status,
        "execution": {
            "toolCallCount": tool_calls,
            "roundCount": rounds,
            "durationMs": duration_ms,
        },
        "allowedEvidenceReferences": list(allowed),
        "discoveredEvidenceReferences": list(discovered),
        "claimAnnotations": list(annotations),
    }
    if status == "COMPLETED":
        result["assessment"] = value or assessment()
    return result


class AssistedInvestigationEvaluatorTest(unittest.TestCase):
    def write_inputs(self, directory, scenarios, investigations):
        scenario_path = Path(directory) / "scenarios.json"
        investigation_path = Path(directory) / "investigations.json"
        scenario_path.write_text(json.dumps(scenarios), encoding="utf-8")
        investigation_path.write_text(json.dumps(investigations), encoding="utf-8")
        return scenario_path, investigation_path

    def test_evaluates_subsystem_citations_claims_and_execution_budgets(self):
        annotations = (
            {
                "claim": "Kafka lag is caused by delayed Analysis consumption.",
                "classification": "SUPPORTED",
                "evidenceReferences": ["health-snapshot:snapshot-1"],
            },
            {
                "claim": "PostgreSQL is unavailable.",
                "classification": "UNSUPPORTED",
                "evidenceReferences": [],
            },
        )
        investigations = investigation_dataset(trials=(trial(annotations=annotations),))

        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory, scenario_dataset(), investigations
            )
            report = evaluator.build_evaluation([scenario_path], [investigation_path])

        result = report["trials"][0]
        self.assertTrue(result["evaluable"])
        self.assertEqual(1.0, result["subsystemIdentification"]["affectedSubsystemCoverage"])
        self.assertEqual(1.0, result["subsystemIdentification"]["suspectedSubsystemPrecision"])
        self.assertEqual(1.0, result["evidenceCitations"]["citationValidityRate"])
        self.assertEqual(0.5, result["claimAssessment"]["unsupportedCausalClaimRate"])
        self.assertEqual(1.0, result["claimAssessment"]["supportedClaimCitationCoverage"])
        self.assertTrue(result["execution"]["budgetCompliant"])
        self.assertEqual(3.0, report["summary"]["execution"]["meanToolCallCount"])
        self.assertEqual(1200.0, report["summary"]["execution"]["meanInvestigationDurationMs"])

    def test_reports_normal_operation_subsystem_claim_as_false_positive_signal(self):
        investigations = investigation_dataset(
            trials=(trial(value=assessment(suspected=("kafka",), attention=True)),)
        )
        scenarios = scenario_dataset(fault=False, affected=())

        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(directory, scenarios, investigations)
            report = evaluator.build_evaluation([scenario_path], [investigation_path])

        summary = report["summary"]
        self.assertEqual(1, summary["subsystemIdentification"]["normalTrialsWithUnexpectedSubsystemClaim"])
        self.assertEqual(1.0, summary["subsystemIdentification"]["normalUnexpectedSubsystemClaimRate"])
        self.assertEqual(1, summary["advisoryAttention"]["normalTrialsSuggestingAttention"])

    def test_invalid_reference_is_measured_without_rejecting_the_trial(self):
        investigations = investigation_dataset(
            trials=(trial(value=assessment(citations=("made-up-reference",))),)
        )

        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory, scenario_dataset(), investigations
            )
            report = evaluator.build_evaluation([scenario_path], [investigation_path])

        result = report["trials"][0]
        self.assertTrue(result["evaluable"])
        self.assertEqual(["made-up-reference"], result["evidenceCitations"]["invalidEvidenceReferences"])
        self.assertEqual(0.0, result["evidenceCitations"]["citationValidityRate"])

    def test_missing_claim_annotations_do_not_imply_zero_hallucinations(self):
        investigations = investigation_dataset(trials=(trial(),))

        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory, scenario_dataset(), investigations
            )
            report = evaluator.build_evaluation([scenario_path], [investigation_path])

        claims = report["summary"]["causalClaims"]
        self.assertEqual(0, claims["trialsWithClaimAnnotations"])
        self.assertEqual(0, claims["causalClaimsEvaluated"])
        self.assertIsNone(claims["unsupportedCausalClaimRate"])

    def test_failed_and_budget_exceeded_trials_are_excluded_but_execution_status_is_preserved(self):
        investigations = investigation_dataset(
            trials=(
                trial(trial_id="failed", status="FAILED", tool_calls=1, rounds=1, duration_ms=400),
                trial(
                    trial_id="budget",
                    status="BUDGET_EXCEEDED",
                    tool_calls=8,
                    rounds=4,
                    duration_ms=45001,
                ),
            )
        )

        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory, scenario_dataset(), investigations
            )
            report = evaluator.build_evaluation([scenario_path], [investigation_path])

        self.assertEqual(0, report["summary"]["trials"]["evaluable"])
        self.assertEqual(1, report["summary"]["trials"]["statusCounts"]["FAILED"])
        self.assertEqual(1, report["summary"]["trials"]["statusCounts"]["BUDGET_EXCEEDED"])

    def test_groups_descriptive_metrics_by_provider_and_model_without_ranking(self):
        first = investigation_dataset(trials=(trial(trial_id="one"),))
        second = investigation_dataset(trials=(trial(trial_id="two"),))
        second["source"]["provider"] = "other"
        second["source"]["model"] = "other-model"
        second["trials"][0]["assessment"]["provider"] = "other"
        second["trials"][0]["assessment"]["model"] = "other-model"

        with tempfile.TemporaryDirectory() as directory:
            scenario_path = Path(directory) / "scenarios.json"
            first_path = Path(directory) / "first.json"
            second_path = Path(directory) / "second.json"
            scenario_path.write_text(json.dumps(scenario_dataset()), encoding="utf-8")
            first_path.write_text(json.dumps(first), encoding="utf-8")
            second_path.write_text(json.dumps(second), encoding="utf-8")
            report = evaluator.build_evaluation([scenario_path], [first_path, second_path])

        self.assertEqual(
            [("fake", "deterministic-v1"), ("other", "other-model")],
            [(item["provider"], item["model"]) for item in report["models"]],
        )
        self.assertNotIn("winner", report)

    def test_rejects_unknown_scenario_or_snapshot_linkage(self):
        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory,
                scenario_dataset(),
                investigation_dataset(trials=(trial(scenario_run_id="unknown"),)),
            )
            with self.assertRaises(evaluator.AssistedEvaluationError):
                evaluator.build_evaluation([scenario_path], [investigation_path])

        bad_snapshot = trial()
        bad_snapshot["snapshotId"] = "missing-snapshot"
        bad_snapshot["assessment"]["snapshotId"] = "missing-snapshot"
        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory,
                scenario_dataset(),
                investigation_dataset(trials=(bad_snapshot,)),
            )
            with self.assertRaises(evaluator.AssistedEvaluationError):
                evaluator.build_evaluation([scenario_path], [investigation_path])


    def test_accepts_zero_tool_call_budget_for_single_shot_provider_mode(self):
        investigations = investigation_dataset(
            trials=(trial(tool_calls=0, rounds=1, duration_ms=300),),
            max_tools=0,
        )

        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory, scenario_dataset(), investigations
            )
            report = evaluator.build_evaluation([scenario_path], [investigation_path])

        execution = report["trials"][0]["execution"]
        self.assertTrue(execution["toolCallsWithinBudget"])
        self.assertTrue(execution["budgetCompliant"])

    def test_rejects_unsupported_investigation_schema(self):
        investigations = investigation_dataset(trials=(trial(),))
        investigations["schemaVersion"] = 99
        with tempfile.TemporaryDirectory() as directory:
            scenario_path, investigation_path = self.write_inputs(
                directory, scenario_dataset(), investigations
            )
            with self.assertRaises(evaluator.AssistedEvaluationError):
                evaluator.build_evaluation([scenario_path], [investigation_path])


if __name__ == "__main__":
    unittest.main()
