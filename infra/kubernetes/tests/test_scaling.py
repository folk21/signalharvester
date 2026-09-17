import importlib.util
import inspect
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
K8S = ROOT / "infra" / "kubernetes"
SCALING = K8S / "scaling"

spec = importlib.util.spec_from_file_location("scaling_acceptance", SCALING / "run_acceptance.py")
acceptance = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = acceptance
spec.loader.exec_module(acceptance)


class KubernetesScalingAssetsTest(unittest.TestCase):
    def test_group_snapshot_parser_tracks_members_assignments_and_total_lag(self):
        sample = """
{
  "group": "signalharvester-analysis-v1",
  "members": 3,
  "partitions": [
    {"topic": "signalharvester.collection.raw-item-discovered.v1", "partition": 0, "lag": 7, "member_id": "member-a", "client_id": "shared-client"},
    {"topic": "signalharvester.collection.raw-item-discovered.v1", "partition": 1, "lag": 5, "member_id": "member-b", "client_id": "shared-client"},
    {"topic": "signalharvester.collection.raw-item-discovered.v1", "partition": 2, "lag": 3, "member_id": "member-c", "client_id": "shared-client"}
  ]
}
"""
        snapshot = acceptance.parse_consumer_group_snapshot(sample)
        self.assertEqual(3, snapshot.member_count)
        self.assertEqual(15, snapshot.lag)
        self.assertEqual({"member-a", "member-b", "member-c"}, set(snapshot.member_ids))
        self.assertEqual({"shared-client"}, set(snapshot.client_ids))
        self.assertEqual(
            {("signalharvester.collection.raw-item-discovered.v1", 1)},
            set(snapshot.assignments["member-b"]),
        )

    def test_group_snapshot_parser_does_not_require_unique_client_ids(self):
        sample = """
{
  "group": "signalharvester-analysis-v1",
  "members": 3,
  "partitions": [
    {"topic": "raw", "partition": 0, "lag": 1, "member_id": "member-a", "client_id": "consumer-signalharvester-analysis-v1-1"},
    {"topic": "raw", "partition": 1, "lag": 1, "member_id": "member-b", "client_id": "consumer-signalharvester-analysis-v1-1"},
    {"topic": "raw", "partition": 2, "lag": 1, "member_id": "member-c", "client_id": "consumer-signalharvester-analysis-v1-1"}
  ]
}
"""
        snapshot = acceptance.parse_consumer_group_snapshot(sample)
        self.assertEqual(3, len(snapshot.member_ids))
        self.assertEqual(1, len(snapshot.client_ids))
        self.assertEqual(3, len(snapshot.assignments))

    def test_group_snapshot_parser_rejects_missing_partition_rows(self):
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.parse_consumer_group_snapshot('{"group":"empty","members":0}')

    def test_scaling_fixture_supports_bounded_generated_workload(self):
        fixture = (K8S / "resilience" / "fixture.yaml").read_text()
        self.assertIn('path.startswith("/scale/")', fixture)
        self.assertIn('count > 500', fixture)
        self.assertIn('scaling_entries(path, count)', fixture)

    def test_scaling_runner_reopens_api_tunnels_around_backend_rollouts(self):
        main_source = inspect.getsource(acceptance.main)
        verify_source = inspect.getsource(acceptance.verify_scaling)

        baseline = main_source.index('print("==> Establish one-replica scaling baseline")')
        scale_one = main_source.index("replica_guard.set(1)", baseline)
        disable_analysis = main_source.index(
            'env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", "false")', baseline
        )
        first_api_session = main_source.index("with authenticated_admin_session", disable_analysis)

        self.assertLess(scale_one, first_api_session)
        self.assertLess(disable_analysis, first_api_session)
        self.assertGreaterEqual(main_source.count("with authenticated_admin_session"), 3)
        self.assertNotIn("port_forward", verify_source)
        self.assertNotIn("admin.request", verify_source)
        self.assertIn('env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", None)', verify_source)
        self.assertIn("replica_guard.set(target_replicas)", verify_source)

    def test_scaling_runner_uses_scenario_timeout_for_synchronous_backlog_generation(self):
        main_source = inspect.getsource(acceptance.main)
        self.assertIn(
            "resilience.run_collection(admin, profile_id, timeout=args.scenario_timeout)",
            main_source,
        )

    def test_scaling_runner_uses_partition_bounded_three_replica_contract(self):
        runner = (SCALING / "run_acceptance.py").read_text()
        for marker in (
            "Establish one-replica scaling baseline",
            "Restore one Analysis worker while backlog remains",
            "Scale backend from one to {target_replicas} replicas",
            "Analysis lag to drain after scale-up",
            "all scaling Results materialization",
        ):
            self.assertIn(marker, runner)
        self.assertIn("target_replicas != 3", runner)
        self.assertIn("raw_assignments != {0, 1, 2}", runner)
        self.assertIn("ANALYSIS_DLQ_TOPIC", runner)

    def test_scaling_spec_reuses_stable_feature_id(self):
        text = (
            ROOT
            / "docs"
            / "specs"
            / "archive"
            / "subspecs"
            / "backend-kafka-consumer-horizontal-scaling.md"
        ).read_text()
        self.assertIn("`SCALABILITY.KAFKA_CONSUMERS`", text)
        self.assertIn("umbrella R26", text)
        self.assertIn("scenario S9", text)


if __name__ == "__main__":
    unittest.main()
