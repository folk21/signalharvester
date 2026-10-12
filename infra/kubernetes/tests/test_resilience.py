import contextlib
import importlib.util
import json
import sys
import tempfile
import unittest
from unittest import mock
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
        self.assertIn('"SIGNALHARVESTER_DB_CONNECTION_TIMEOUT_MS": "5000"', runner)
        self.assertIn('"SIGNALHARVESTER_DB_CONNECT_TIMEOUT_SECONDS": "3"', runner)
        self.assertIn('"SIGNALHARVESTER_DB_SOCKET_TIMEOUT_SECONDS": "10"', runner)
        self.assertIn('"SIGNALHARVESTER_ANALYSIS_KAFKA_MAX_ATTEMPTS": "3"', runner)

    def test_acceptance_runner_emits_labeled_operational_evidence(self):
        runner = (RESILIENCE / "run_acceptance.py").read_text()
        for label in (
            "NORMAL_OPERATION",
            "POD_RESTART",
            "SLOW_EXTERNAL_SOURCE",
            "POSTGRESQL_OUTAGE",
            "KAFKA_LAG",
            "OUTBOX_BACKLOG",
            "KAFKA_BROKER_RESTART",
        ):
            self.assertIn(f'"{label}"', runner)
        self.assertIn("record_scenario_start", runner)
        self.assertIn("record_scenario_fault_boundary", runner)
        self.assertIn('"FAULT_START"', runner)
        self.assertIn('"FAULT_END"', runner)
        self.assertIn("record_scenario_end", runner)
        self.assertIn("resilience-scenario-evidence.json", runner)
        self.assertIn("HEALTH_EXPECTATION_REMAIN_HEALTHY", runner)
        self.assertIn("ALERT_EXPECTATION_NO_ATTENTION", runner)
        self.assertIn("HEALTH_EXPECTATION_DESCRIPTIVE_ONLY", runner)
        self.assertIn("ALERT_EXPECTATION_DESCRIPTIVE_ONLY", runner)

    def test_scenario_marker_uses_the_operations_test_scenario_contract(self):
        class FakeAdmin:
            def __init__(self):
                self.path = None
                self.payload = None

            def post_json(self, path, payload, expected=201, timeout=None):
                self.path = path
                self.payload = payload
                return {"id": "change-id"}

        class FakeScenario:
            scenario_run_id = "scenario-run-id"
            scenario_id = "resilience-kafka-lag"
            label = "KAFKA_LAG"

        admin = FakeAdmin()
        marker = acceptance.record_scenario_marker(admin, FakeScenario(), "START")

        self.assertEqual({"id": "change-id"}, marker)
        self.assertEqual("/api/v1/admin/operations/changes/markers", admin.path)
        self.assertEqual("TEST_SCENARIO", admin.payload["category"])
        self.assertEqual("SCENARIO", admin.payload["targetType"])
        self.assertEqual("scenario-run-id", admin.payload["targetId"])
        self.assertEqual("KAFKA_LAG", admin.payload["details"]["label"])
        self.assertEqual("START", admin.payload["details"]["phase"])

    def test_fault_boundary_marker_reuses_the_operations_test_scenario_contract(self):
        class FakeAdmin:
            def __init__(self):
                self.payloads = []

            def post_json(self, path, payload, expected=201, timeout=None):
                self.payloads.append((path, payload))
                return {"id": f"change-{payload['details']['phase']}"}

        class FakeScenario:
            scenario_run_id = "scenario-run-id"
            scenario_id = "resilience-kafka-lag"
            label = "KAFKA_LAG"

            def __init__(self):
                self.markers = []

            def add_change_marker(self, phase, change):
                self.markers.append((phase, change))

        admin = FakeAdmin()
        scenario = FakeScenario()
        acceptance.record_scenario_fault_boundary(admin, scenario, "FAULT_START")
        acceptance.record_scenario_fault_boundary(admin, scenario, "FAULT_END")

        self.assertEqual(["FAULT_START", "FAULT_END"], [item[0] for item in scenario.markers])
        self.assertEqual(["FAULT_START", "FAULT_END"], [payload["details"]["phase"] for _, payload in admin.payloads])
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.record_scenario_fault_boundary(admin, scenario, "INVALID")

    def test_backend_restart_uses_distinct_surviving_replica_and_existing_jwt(self):
        class FakeRunner:
            namespace = "signalharvester-test"

            def __init__(self):
                self.killed = False
                self.commands = []

            def namespaced(self, *args, check=True):
                self.commands.append(args)
                if args[:2] == ("get", "pods"):
                    return acceptance.CommandResult(
                        json.dumps(
                            {
                                "items": [
                                    {
                                        "metadata": {"name": "backend-a"},
                                        "status": {"conditions": [{"type": "Ready", "status": "True"}]},
                                    },
                                    {
                                        "metadata": {"name": "backend-b"},
                                        "status": {"conditions": [{"type": "Ready", "status": "True"}]},
                                    },
                                ]
                            }
                        ),
                        "",
                        0,
                    )
                if args[:3] == ("get", "pod", "backend-a"):
                    return acceptance.CommandResult(
                        json.dumps(
                            {
                                "status": {
                                    "containerStatuses": [{"restartCount": 1 if self.killed else 0}],
                                    "conditions": [{"type": "Ready", "status": "True"}],
                                }
                            }
                        ),
                        "",
                        0,
                    )
                if args[:4] == ("exec", "backend-a", "--", "kill"):
                    self.killed = True
                    return acceptance.CommandResult("", "", 0)
                raise AssertionError(f"unexpected kubectl arguments: {args!r}")

        class FakeViewer:
            def __init__(self, base_url="http://127.0.0.1:18081"):
                self.base_url = base_url
                self.retargeted = []

            def for_base_url(self, base_url):
                self.retargeted.append(base_url)
                return FakeViewer(base_url)

            def request(self, method, path):
                return 200, []

        forwarded = []

        @contextlib.contextmanager
        def fake_port_forward(runner, resource, local_port, remote_port):
            forwarded.append((resource, local_port, remote_port))
            yield

        def immediate_wait(description, timeout, interval, predicate):
            self.assertTrue(predicate(), description)

        runner = FakeRunner()
        viewer = FakeViewer()
        with (
            mock.patch.object(acceptance, "port_forward", fake_port_forward),
            mock.patch.object(acceptance, "wait_until", immediate_wait),
        ):
            observations = acceptance.restart_backend_container(runner, viewer, 30.0, 18082)

        self.assertTrue(runner.killed)
        self.assertEqual(
            [("pod/backend-b", 18082, 8080), ("pod/backend-a", 18082, 8080)],
            forwarded,
        )
        self.assertEqual(200, observations["survivingReplicaHttpStatus"])
        self.assertEqual(200, observations["restartedReplicaHttpStatus"])

    def test_long_lived_port_forwards_restart_after_selected_pod_exit(self):
        runner = (RESILIENCE / "run_acceptance.py").read_text()
        self.assertIn("restarting port-forward", runner)
        self.assertIn("wait_for_port_forward_ready", runner)
        self.assertIn("replacement_log_offset", runner)
        self.assertIn("threading.Thread", runner)
        self.assertIn("--surviving-backend-port", runner)

    def test_port_forward_readiness_uses_only_the_current_process_generation_log(self):
        class FakeProcess:
            def poll(self):
                return None

        with tempfile.NamedTemporaryFile(mode="w", delete=False) as log:
            log.write("Forwarding from 127.0.0.1:18081 -> 8080\n")
            generation_offset = log.tell()
            log_path = Path(log.name)

        try:
            with (
                mock.patch.object(acceptance.time, "monotonic", side_effect=[0.0, 1.0]),
                self.assertRaisesRegex(acceptance.AcceptanceError, "did not become ready"),
            ):
                acceptance.wait_for_port_forward_ready(
                    FakeProcess(),
                    log_path,
                    generation_offset,
                    "service/signalharvester-backend",
                    timeout=0.5,
                )

            with log_path.open("a") as log:
                log.write("Forwarding from 127.0.0.1:18081 -> 8080\n")

            with mock.patch.object(acceptance.time, "monotonic", side_effect=[0.0, 0.1]):
                acceptance.wait_for_port_forward_ready(
                    FakeProcess(),
                    log_path,
                    generation_offset,
                    "service/signalharvester-backend",
                    timeout=0.5,
                )
        finally:
            log_path.unlink(missing_ok=True)

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

    def test_preflight_forwards_the_requested_rollout_timeout(self):
        class FakeRunner:
            namespace = "signalharvester-test"

            def __init__(self):
                self.args = None

            def run(self, args, **kwargs):
                self.args = args
                return acceptance.CommandResult("ok", "", 0)

        runner = FakeRunner()
        acceptance.run_preflight(runner, "45s")

        self.assertEqual("env", runner.args[0])
        self.assertIn("SIGNALHARVESTER_K8S_NAMESPACE=signalharvester-test", runner.args)
        self.assertIn("SIGNALHARVESTER_K8S_VERIFY_TIMEOUT=45s", runner.args)

    def test_backend_environment_guard_waits_for_registered_probe_after_rollout(self):
        class FakeRunner:
            def __init__(self):
                self.events = []

            def namespaced(self, *args, check=True):
                if args == ("get", "deployment", acceptance.BACKEND_DEPLOYMENT, "-o", "json"):
                    return acceptance.CommandResult(
                        json.dumps(
                            {
                                "spec": {
                                    "template": {
                                        "spec": {
                                            "containers": [{"env": []}],
                                        }
                                    }
                                }
                            }
                        ),
                        "",
                        0,
                    )
                if args == (
                    "set",
                    "env",
                    f"deployment/{acceptance.BACKEND_DEPLOYMENT}",
                    "SIGNALHARVESTER_ANALYSIS_ENABLED=false",
                ):
                    self.events.append("set")
                    return acceptance.CommandResult("", "", 0)
                if args == (
                    "rollout",
                    "status",
                    f"deployment/{acceptance.BACKEND_DEPLOYMENT}",
                    "--timeout=45s",
                ):
                    self.events.append("rollout")
                    return acceptance.CommandResult("", "", 0)
                raise AssertionError(f"unexpected kubectl arguments: {args!r}")

        runner = FakeRunner()
        guard = acceptance.BackendEnvironmentGuard(runner, "45s")
        guard.set_post_rollout_probe(lambda: runner.events.append("api-probe"))

        guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", "false")

        self.assertEqual(["set", "rollout", "api-probe"], runner.events)

    def test_postgres_baseline_repairs_interrupted_zero_replica_state(self):
        class FakeRunner:
            def __init__(self):
                self.replicas = 0
                self.commands = []

            def namespaced(self, *args, **kwargs):
                self.commands.append(args)
                if args == ("get", "statefulset", "postgres", "-o", "json"):
                    return acceptance.CommandResult(
                        json.dumps({"spec": {"replicas": self.replicas}}),
                        "",
                        0,
                    )
                if args == ("scale", "statefulset/postgres", "--replicas=1"):
                    self.replicas = 1
                    return acceptance.CommandResult("", "", 0)
                if args == ("rollout", "status", "statefulset/postgres", "--timeout=45s"):
                    return acceptance.CommandResult("", "", 0)
                raise AssertionError(f"unexpected kubectl arguments: {args!r}")

        runner = FakeRunner()
        with mock.patch("sys.stderr"):
            repaired = acceptance.restore_postgres_acceptance_baseline(runner, "45s")

        self.assertTrue(repaired)
        self.assertEqual(1, runner.replicas)
        self.assertEqual(
            [
                ("get", "statefulset", "postgres", "-o", "json"),
                ("scale", "statefulset/postgres", "--replicas=1"),
                ("rollout", "status", "statefulset/postgres", "--timeout=45s"),
            ],
            runner.commands,
        )

    def test_postgres_baseline_rejects_unexpected_topology_without_mutating_it(self):
        class FakeRunner:
            def __init__(self):
                self.commands = []

            def namespaced(self, *args, **kwargs):
                self.commands.append(args)
                if args == ("get", "statefulset", "postgres", "-o", "json"):
                    return acceptance.CommandResult(json.dumps({"spec": {"replicas": 2}}), "", 0)
                raise AssertionError(f"unexpected kubectl arguments: {args!r}")

        runner = FakeRunner()
        with self.assertRaisesRegex(acceptance.AcceptanceError, "will not rewrite an unexpected topology"):
            acceptance.restore_postgres_acceptance_baseline(runner, "45s")

        self.assertEqual([("get", "statefulset", "postgres", "-o", "json")], runner.commands)

    def test_postgres_baseline_leaves_expected_replica_count_to_normal_preflight(self):
        class FakeRunner:
            def __init__(self):
                self.commands = []

            def namespaced(self, *args, **kwargs):
                self.commands.append(args)
                if args == ("get", "statefulset", "postgres", "-o", "json"):
                    return acceptance.CommandResult(json.dumps({"spec": {"replicas": 1}}), "", 0)
                raise AssertionError(f"unexpected kubectl arguments: {args!r}")

        runner = FakeRunner()
        repaired = acceptance.restore_postgres_acceptance_baseline(runner, "45s")

        self.assertFalse(repaired)
        self.assertEqual([("get", "statefulset", "postgres", "-o", "json")], runner.commands)

    def test_application_database_recovery_retries_db_backed_read_until_http_200(self):
        class FakeAdmin:
            def __init__(self):
                self.statuses = [500, 200]
                self.requests = []

            def request(self, method, path):
                self.requests.append((method, path))
                status = self.statuses.pop(0)
                return status, {"message": "temporary database failure"} if status != 200 else []

        def bounded_wait(description, timeout, interval, predicate):
            self.assertEqual("application database connectivity after PostgreSQL recovery", description)
            self.assertEqual(30.0, timeout)
            self.assertEqual(1.0, interval)
            with self.assertRaises(acceptance.AcceptanceError):
                predicate()
            self.assertTrue(predicate())

        admin = FakeAdmin()
        with (
            mock.patch.object(acceptance, "wait_until", bounded_wait),
            mock.patch.object(acceptance.time, "monotonic", side_effect=[10.0, 10.125]),
        ):
            recovery_ms = acceptance.wait_for_application_database_recovery(admin, 30.0)

        self.assertEqual(125, recovery_ms)
        self.assertEqual(
            [("GET", "/api/v1/sources"), ("GET", "/api/v1/sources")],
            admin.requests,
        )

    def test_backend_rollout_api_recovery_retries_transient_tunnel_failure(self):
        class FakeAdmin:
            def __init__(self):
                self.calls = 0

            def request(self, method, path):
                self.calls += 1
                if self.calls == 1:
                    raise OSError("port-forward selected pod disappeared")
                return 200, []

        def bounded_wait(description, timeout, interval, predicate):
            self.assertEqual("backend API connectivity after Deployment rollout", description)
            self.assertEqual(30.0, timeout)
            self.assertEqual(1.0, interval)
            with self.assertRaisesRegex(OSError, "selected pod disappeared"):
                predicate()
            self.assertTrue(predicate())

        admin = FakeAdmin()
        with (
            mock.patch.object(acceptance, "wait_until", bounded_wait),
            mock.patch.object(acceptance.time, "monotonic", side_effect=[20.0, 20.25]),
        ):
            recovery_ms = acceptance.wait_for_backend_rollout_api_recovery(admin, 30.0)

        self.assertEqual(250, recovery_ms)
        self.assertEqual(2, admin.calls)

    def test_consumer_group_recovery_status_parses_current_rpk_array_shape(self):
        payload = json.dumps(
            [
                {
                    "group_name": acceptance.ANALYSIS_GROUP,
                    "state": "Stable",
                    "members": 2,
                    "total_lag": 1,
                    "partitions": [
                        {"partition": 0, "lag": 0, "member_id": "member-a"},
                        {"partition": 1, "lag": 1, "member_id": "member-b"},
                        {"partition": 2, "lag": 0, "member_id": "member-b"},
                    ],
                }
            ]
        )

        status = acceptance.parse_consumer_group_recovery_status(payload, acceptance.ANALYSIS_GROUP)

        self.assertEqual("Stable", status["state"])
        self.assertEqual(2, status["members"])
        self.assertEqual(3, status["assignedPartitions"])
        self.assertEqual(1, status["totalLag"])

    def test_kafka_application_recovery_waits_for_all_groups_to_be_stable(self):
        class FakeRunner:
            def namespaced(self, *args, **kwargs):
                if args == ("get", "deployment", acceptance.BACKEND_DEPLOYMENT, "-o", "json"):
                    return acceptance.CommandResult(json.dumps({"spec": {"replicas": 2}}), "", 0)
                raise AssertionError(f"unexpected kubectl arguments: {args!r}")

        stable_analysis = {"state": "Stable", "members": 2, "assignedPartitions": 3, "totalLag": 0}
        rebalancing_results = {
            "state": "PreparingRebalance",
            "members": 2,
            "assignedPartitions": 0,
            "totalLag": 0,
        }
        stable_results = {"state": "Stable", "members": 4, "assignedPartitions": 6, "totalLag": 0}
        stable_event = {"state": "Stable", "members": 6, "assignedPartitions": 9, "totalLag": 0}

        def bounded_wait(description, timeout, interval, predicate):
            self.assertEqual("application Kafka consumer groups after Redpanda recovery", description)
            self.assertEqual(30.0, timeout)
            self.assertEqual(1.0, interval)
            with self.assertRaisesRegex(acceptance.AcceptanceError, acceptance.RESULTS_GROUP):
                predicate()
            self.assertTrue(predicate())

        statuses = [
            stable_analysis,
            rebalancing_results,
            stable_event,
            stable_analysis,
            stable_results,
            stable_event,
        ]
        with (
            mock.patch.object(acceptance, "consumer_group_recovery_status", side_effect=statuses),
            mock.patch.object(acceptance, "wait_until", bounded_wait),
            mock.patch.object(acceptance.time, "monotonic", side_effect=[10.0, 10.4]),
        ):
            observations = acceptance.wait_for_kafka_application_recovery(FakeRunner(), 30.0)

        self.assertEqual(400, observations["kafkaApplicationRecoveryMs"])
        self.assertEqual(2, observations["analysisConsumerMembersAfterBrokerRestart"])
        self.assertEqual(4, observations["resultsConsumerMembersAfterBrokerRestart"])
        self.assertEqual(6, observations["eventObservationConsumerMembersAfterBrokerRestart"])
        self.assertEqual(0, observations["analysisConsumerLagAfterBrokerRestart"])

    def test_redpanda_recovery_uses_scenario_timeout_for_single_collection_mutation(self):
        class FakeRunner:
            def __init__(self):
                self.commands = []

            def namespaced(self, *args, **kwargs):
                self.commands.append(args)
                return acceptance.CommandResult("", "", 0)

        class FakeTracker:
            def source(self, path, label):
                self.source_call = (path, label)
                return "source-id"

            def profile(self, source_id, label):
                self.profile_call = (source_id, label)
                return "profile-id"

        collection_timeouts = []

        def fake_run_collection(admin, profile_id, *, timeout=None):
            collection_timeouts.append(timeout)
            self.assertEqual("profile-id", profile_id)
            return {"status": "SUCCEEDED"}

        recovery = {
            "kafkaApplicationRecoveryMs": 750,
            "analysisConsumerMembersAfterBrokerRestart": 2,
            "resultsConsumerMembersAfterBrokerRestart": 4,
            "eventObservationConsumerMembersAfterBrokerRestart": 6,
            "analysisConsumerLagAfterBrokerRestart": 0,
        }
        runner = FakeRunner()
        tracker = FakeTracker()
        with (
            mock.patch.object(acceptance, "rollout_statefulset"),
            mock.patch.object(acceptance, "wait_for_kafka_application_recovery", return_value=recovery),
            mock.patch.object(acceptance, "run_collection", side_effect=fake_run_collection),
            mock.patch.object(acceptance, "wait_results"),
            mock.patch.object(acceptance, "result_count", return_value=1),
            mock.patch.object(acceptance.time, "monotonic", side_effect=[20.0, 20.6]),
        ):
            observations = acceptance.restart_redpanda_and_verify(
                runner, object(), object(), tracker, 120.0, "240s"
            )

        self.assertEqual([120.0], collection_timeouts)
        self.assertEqual(600, observations["postBrokerCollectionMs"])
        self.assertEqual(750, observations["kafkaApplicationRecoveryMs"])
        self.assertEqual(1, observations["recoveredResults"])
        self.assertEqual(("delete", "pod", "redpanda-0", "--wait=false"), runner.commands[0])

    def test_analysis_retry_log_counts_ignore_unrelated_logs_and_count_terminal_outcome(self):
        logs = "\n".join(
            (
                "unrelated startup log",
                "Analysis Kafka processing attempt 1 failed for topic-0@7; retrying",
                "Analysis Kafka processing attempt 2 failed for topic-0@7; retrying",
                "Analysis Kafka record topic-0@7 moved to dead letter after 3 attempt(s)",
            )
        )

        self.assertEqual((2, 1), acceptance.analysis_retry_log_counts(logs))

    def test_postgres_outage_evidence_records_retry_counts_and_dlq_detection_time(self):
        runner = (RESILIENCE / "run_acceptance.py").read_text()

        for measurement in (
            "analysisRetryLogCountDuringFault",
            "analysisTerminalDlqLogCountDuringFault",
            "analysisDlqDetectionMs",
            "databaseRecoveryMs",
        ):
            self.assertIn(measurement, runner)

    def test_broker_restart_evidence_records_application_recovery_timings(self):
        runner = (RESILIENCE / "run_acceptance.py").read_text()

        for measurement in (
            "kafkaApplicationRecoveryMs",
            "postBrokerCollectionMs",
            "analysisConsumerMembersAfterBrokerRestart",
            "resultsConsumerMembersAfterBrokerRestart",
            "eventObservationConsumerMembersAfterBrokerRestart",
        ):
            self.assertIn(measurement, runner)

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
