import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
K8S = ROOT / "infra" / "kubernetes"
RESILIENCE = K8S / "resilience"

spec = importlib.util.spec_from_file_location("resilience_acceptance", RESILIENCE / "run_acceptance.py")
acceptance = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = acceptance
spec.loader.exec_module(acceptance)


class KubernetesResilienceAssetsTest(unittest.TestCase):
    def test_fixture_is_opt_in_versioned_and_health_checked(self):
        fixture = (RESILIENCE / "fixture.yaml").read_text()
        root_kustomization = (K8S / "kustomization.yaml").read_text()
        self.assertIn("python:3.13.7-alpine", fixture)
        self.assertIn("/healthz", fixture)
        self.assertIn("/slow.xml", fixture)
        self.assertNotIn("resilience/fixture.yaml", root_kustomization)
        self.assertNotIn("kind: Secret", fixture)

    def test_acceptance_runner_covers_required_failure_boundaries(self):
        runner = (RESILIENCE / "run_acceptance.py").read_text()
        required_markers = (
            "Stateless JWT and backend container restart",
            "Slow source availability",
            "PostgreSQL outage, bounded Analysis retry, DLQ, and recovery",
            "Kafka lag generation and Analysis recovery",
            "Analysis outbox durability across rollout",
            "Multi-replica scheduler lease",
            "Redpanda restart recovery",
            "Resilience observability evidence",
        )
        for marker in required_markers:
            self.assertIn(marker, runner)
        self.assertIn("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS", runner)
        self.assertIn("SIGNALHARVESTER_ANALYSIS_ENABLED", runner)
        self.assertIn("SIGNALHARVESTER_ANALYSIS_OUTBOX_ENABLED", runner)

    def test_api_session_supports_long_requests_without_json_delete_body(self):
        class FakeResponse:
            status = 204

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self):
                return b""

        class FakeOpener:
            def __init__(self):
                self.request = None
                self.timeout = None

            def open(self, request, timeout):
                self.request = request
                self.timeout = timeout
                return FakeResponse()

        session = acceptance.ApiSession("http://127.0.0.1:18082", 10.0)
        opener = FakeOpener()
        session.opener = opener
        session.cookie_value = lambda name: "csrf-proof" if name == "XSRF-TOKEN" else None

        status, body = session.request(
            "DELETE",
            "/api/v1/sources/source-id",
            timeout=240.0,
        )

        self.assertEqual(204, status)
        self.assertIsNone(body)
        self.assertEqual(240.0, opener.timeout)
        self.assertIsNone(opener.request.data)
        self.assertIsNone(opener.request.get_header("Content-type"))
        self.assertEqual("csrf-proof", opener.request.get_header("X-csrf-token"))

    def test_topic_record_count_parser_sums_partition_ranges(self):
        sample = """
PARTITION  LEADER  EPOCH  REPLICAS  LOG-START-OFFSET  HIGH-WATERMARK
0          0       1      [0]       2                 5
1          0       1      [0]       0                 4
2          0       1      [0]       7                 7
"""
        self.assertEqual(7, acceptance.parse_topic_record_count(sample))

    def test_topic_record_count_parser_rejects_unexpected_output(self):
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.parse_topic_record_count("no partition table here")

    def test_resilience_spec_reuses_stable_feature_ids(self):
        text = (ROOT / "docs" / "specs" / "archive" / "subspecs" / "backend-system-resilience-acceptance.md").read_text()
        for feature in (
            "RELIABILITY.KAFKA_RETRY",
            "RELIABILITY.DEAD_LETTER",
            "ANALYSIS.OUTBOX",
            "COLLECTION.SCHEDULING",
            "SECURITY.AUTHENTICATION",
            "SECURITY.AUTHORIZATION",
            "RUNTIME.CONCURRENCY",
        ):
            self.assertIn(f"`{feature}`", text)


if __name__ == "__main__":
    unittest.main()
