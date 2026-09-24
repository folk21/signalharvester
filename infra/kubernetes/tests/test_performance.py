import importlib.util
import inspect
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PERFORMANCE = ROOT / "infra" / "kubernetes" / "performance"

spec = importlib.util.spec_from_file_location("performance_baseline", PERFORMANCE / "run_baseline.py")
baseline = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = baseline
spec.loader.exec_module(baseline)

comparison_spec = importlib.util.spec_from_file_location("performance_comparison", PERFORMANCE / "run_comparison.py")
comparison = importlib.util.module_from_spec(comparison_spec)
assert comparison_spec.loader is not None
sys.modules[comparison_spec.name] = comparison
comparison_spec.loader.exec_module(comparison)


class KubernetesPerformanceAssetsTest(unittest.TestCase):
    def test_consumer_group_parser_sums_non_negative_lag_and_members(self):
        sample = """
{
  "group": "signalharvester-analysis-v1",
  "members": 2,
  "partitions": [
    {"partition": 0, "lag": 7, "member_id": "member-a"},
    {"partition": 1, "lag": -1, "member_id": "member-b"},
    {"partition": 2, "lag": 3, "member_id": "member-a"}
  ]
}
"""
        status = baseline.parse_consumer_group_status(sample)
        self.assertEqual(2, status.members)
        self.assertEqual(10, status.lag)
        self.assertIsNone(status.state)
        self.assertEqual(3, status.partition_rows)

    def test_consumer_group_parser_accepts_rpk_aggregate_during_rebalance(self):
        sample = """
[{
  "group_name": "signalharvester-analysis-v1",
  "state": "PreparingRebalance",
  "members": 1,
  "total_lag": 0,
  "partitions": [],
  "members_details": [{
    "member_id": "signalharvester-raw-item-kafka-listener-1",
    "client_id": "signalharvester-raw-item-kafka-listener",
    "topic_partitions": []
  }]
}]
"""
        status = baseline.parse_consumer_group_status(sample)
        self.assertEqual(1, status.members)
        self.assertEqual(0, status.lag)
        self.assertEqual("PreparingRebalance", status.state)
        self.assertEqual(0, status.partition_rows)
        self.assertFalse(baseline.consumer_group_ready_for_baseline(status))

    def test_consumer_group_ready_requires_stable_when_rpk_reports_state(self):
        stable = baseline.ConsumerGroupStatus(members=1, lag=0, state="Stable", partition_rows=3)
        rebalancing = baseline.ConsumerGroupStatus(
            members=1, lag=0, state="CompletingRebalance", partition_rows=0
        )
        legacy = baseline.ConsumerGroupStatus(members=1, lag=0, state=None, partition_rows=3)
        self.assertTrue(baseline.consumer_group_ready_for_baseline(stable))
        self.assertFalse(baseline.consumer_group_ready_for_baseline(rebalancing))
        self.assertTrue(baseline.consumer_group_ready_for_baseline(legacy))

    def test_summary_reports_observed_rates_without_thresholds(self):
        samples = [
            baseline.PipelineSample(1.0, 6, 5, 4, 4, 2, 2),
            baseline.PipelineSample(2.0, 0, 2, 1, 10, 8, 1),
            baseline.PipelineSample(2.5, 0, 0, 0, 10, 10, 0),
        ]
        summary = baseline.build_summary(10, 0.5, samples)
        self.assertEqual(2.5, summary["pipelineCompletionSeconds"])
        self.assertEqual(2.0, summary["analysisCompletionSeconds"])
        self.assertEqual(2.5, summary["resultsCompletionSeconds"])
        self.assertEqual(2.0, summary["analysisLagDrainSeconds"])
        self.assertEqual(2.5, summary["resultsLagDrainSeconds"])
        self.assertEqual(2.5, summary["eventObservationLagDrainSeconds"])
        self.assertEqual(2.5, summary["outboxDrainSeconds"])
        self.assertEqual(6, summary["maxAnalysisLag"])
        self.assertEqual(5, summary["maxResultsLag"])
        self.assertEqual(4, summary["maxEventObservationLag"])
        self.assertEqual(2, summary["maxPendingOutboxRows"])
        self.assertEqual(20.0, summary["collectionPublishRateItemsPerSecond"])
        self.assertEqual(4.0, summary["endToEndRateItemsPerSecond"])

    def test_runner_requires_clean_baseline_and_does_not_define_performance_budget(self):
        source = inspect.getsource(baseline.main)
        self.assertIn("lagged_groups", source)
        self.assertIn("baseline_outbox != 0", source)
        self.assertIn("Analysis DLQ advanced during healthy capacity baseline", source)
        self.assertNotIn("p95", source)
        self.assertNotIn("requests_per_second_target", source)

    def test_runner_reuses_bounded_fixture_and_writes_under_build_by_default(self):
        source = (PERFORMANCE / "run_baseline.py").read_text()
        self.assertIn("/scale/capacity-", source)
        self.assertIn("items_per_source > 500", source)
        self.assertIn("sources > 20", source)
        self.assertIn('"build" / "reports" / "performance" / "capacity-baseline.json"', source)

    def test_comparison_replica_parser_requires_ordered_unique_positive_counts(self):
        self.assertEqual([1, 3], comparison.parse_replica_counts("1,3"))
        with self.assertRaises(Exception):
            comparison.parse_replica_counts("1")
        with self.assertRaises(Exception):
            comparison.parse_replica_counts("1,1")
        with self.assertRaises(Exception):
            comparison.parse_replica_counts("0,3")

    def test_comparison_report_keeps_neutral_observations_without_budget_or_winner(self):
        base_report = {
            "scenario": "pipeline-capacity-baseline",
            "startedAt": "2026-09-24T10:00:00+00:00",
            "environment": {
                "backendReplicas": 1,
                "backendImage": "signalharvester-backend:local",
                "consumerMembersBefore": {"signalharvester-analysis-v1": 1},
            },
            "workload": {"sources": 6, "itemsPerSource": 200, "expectedItems": 1200},
            "summary": {
                "collectionSeconds": 12.0,
                "pipelineCompletionSeconds": 60.0,
                "postCollectionDrainSeconds": 48.0,
                "analysisCompletionSeconds": 40.0,
                "resultsCompletionSeconds": 60.0,
                "analysisLagDrainSeconds": 40.0,
                "resultsLagDrainSeconds": 30.0,
                "eventObservationLagDrainSeconds": 45.0,
                "outboxDrainSeconds": 60.0,
                "maxAnalysisLag": 100,
                "maxResultsLag": 80,
                "maxEventObservationLag": 70,
                "maxPendingOutboxRows": 50,
                "collectionPublishRateItemsPerSecond": 100.0,
                "endToEndRateItemsPerSecond": 20.0,
            },
        }
        scaled_report = json.loads(json.dumps(base_report))
        scaled_report["environment"]["backendReplicas"] = 3
        scaled_report["environment"]["consumerMembersBefore"] = {"signalharvester-analysis-v1": 3}
        scaled_report["summary"]["pipelineCompletionSeconds"] = 30.0
        scaled_report["summary"]["endToEndRateItemsPerSecond"] = 40.0

        report = comparison.build_comparison([
            (1, Path("one.json"), base_report),
            (3, Path("three.json"), scaled_report),
        ])

        self.assertEqual("pipeline-capacity-replica-comparison", report["scenario"])
        self.assertFalse(report["interpretation"]["performanceBudgetApplied"])
        self.assertFalse(report["interpretation"]["winnerDeclared"])
        pipeline = report["metrics"]["pipelineCompletionSeconds"]["observations"]
        self.assertEqual(0.5, pipeline[1]["ratioToReference"])
        self.assertEqual(-30.0, pipeline[1]["differenceFromReference"])
        rate = report["metrics"]["endToEndRateItemsPerSecond"]["observations"]
        self.assertEqual(2.0, rate[1]["ratioToReference"])

    def test_comparison_runner_uses_same_workload_and_distinct_report_files(self):
        source = (PERFORMANCE / "run_comparison.py").read_text()
        self.assertIn('default=parse_replica_counts("1,3")', source)
        self.assertIn('"--sources"', source)
        self.assertIn('"--items-per-source"', source)
        self.assertIn('"--replicas"', source)
        self.assertIn('capacity-comparison.json', source)
        self.assertIn('skip_preflight=index > 0', source)

    def test_capacity_documentation_keeps_stress_and_ml_as_follow_up_work(self):
        text = (PERFORMANCE / "README.md").read_text()
        self.assertIn("not a benchmark claim or a production SLO", text)
        self.assertIn("ramp, spike, or soak scenarios", text)
        self.assertIn("ML-based anomaly detection", text)

    def test_capacity_telemetry_is_current_focus_with_measurement_carryover(self):
        umbrella = (ROOT / "docs" / "specs" / "active" / "spec-signal-harvester-platform.md").read_text()
        index = (ROOT / "docs" / "specs" / "README.md").read_text()
        telemetry_spec = (
            ROOT
            / "docs"
            / "specs"
            / "active"
            / "subspecs"
            / "backend-capacity-telemetry-expansion.md"
        ).read_text()
        self.assertIn("current_focus: subspecs/backend-capacity-telemetry-expansion.md", umbrella)
        self.assertIn("spec_status: verification-pending", telemetry_spec)
        self.assertIn("Verification-pending carryover", index)
        self.assertIn("backend-capacity-observability-baseline.md", index)
        self.assertIn("backend-analysis-all-relevant-default.md", index)


if __name__ == "__main__":
    unittest.main()
