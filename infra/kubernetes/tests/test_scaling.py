import importlib.util
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
    def test_group_snapshot_parser_tracks_clients_assignments_and_total_lag(self):
        sample = """
{
  "group": "signalharvester-analysis-v1",
  "members": 3,
  "partitions": [
    {"topic": "signalharvester.collection.raw-item-discovered.v1", "partition": 0, "lag": 7, "client_id": "worker-a"},
    {"topic": "signalharvester.collection.raw-item-discovered.v1", "partition": 1, "lag": 5, "client_id": "worker-b"},
    {"topic": "signalharvester.collection.raw-item-discovered.v1", "partition": 2, "lag": 3, "client_id": "worker-c"}
  ]
}
"""
        snapshot = acceptance.parse_consumer_group_snapshot(sample)
        self.assertEqual(3, snapshot.member_count)
        self.assertEqual(15, snapshot.lag)
        self.assertEqual({"worker-a", "worker-b", "worker-c"}, set(snapshot.clients))
        self.assertEqual(
            {("signalharvester.collection.raw-item-discovered.v1", 1)},
            set(snapshot.assignments["worker-b"]),
        )

    def test_group_snapshot_parser_rejects_missing_partition_rows(self):
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.parse_consumer_group_snapshot('{"group":"empty","members":0}')

    def test_scaling_fixture_supports_bounded_generated_workload(self):
        fixture = (K8S / "resilience" / "fixture.yaml").read_text()
        self.assertIn('path.startswith("/scale/")', fixture)
        self.assertIn('count > 500', fixture)
        self.assertIn('scaling_entries(path, count)', fixture)

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
            / "active"
            / "subspecs"
            / "backend-kafka-consumer-horizontal-scaling.md"
        ).read_text()
        self.assertIn("`SCALABILITY.KAFKA_CONSUMERS`", text)
        self.assertIn("umbrella R26", text)
        self.assertIn("scenario S9", text)


if __name__ == "__main__":
    unittest.main()
