from __future__ import annotations

import importlib.util
import json
import threading
import unittest
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import urlopen


MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_pipeline.py"
SPEC = importlib.util.spec_from_file_location("verify_pipeline", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class VerifyPipelineTest(unittest.TestCase):
    """Verifies the live-backend black-box client against a deterministic loopback API."""

    def setUp(self) -> None:
        state = {"run_id": "00000000-0000-0000-0000-000000000901"}

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                if self.path == "/api/v1/sources":
                    self._json([{"id": "source-1", "enabled": True}])
                    return
                if self.path.startswith("/api/v1/results?"):
                    self._json([
                        {
                            "monitoringProfileId": "profile-test",
                            "normalizedItemId": "a" * 64,
                        }
                    ])
                    return
                if self.path.startswith("/api/v1/results/" + "a" * 64):
                    self._json(
                        {
                            "monitoringProfileId": "profile-test",
                            "normalizedItemId": "a" * 64,
                            "correlationId": state["run_id"],
                            "classification": "GENERAL",
                            "score": 100,
                            "url": "https://example.test/item",
                            "title": "Example item",
                        }
                    )
                    return
                self.send_error(404)

            def do_POST(self):  # noqa: N802
                if self.path == "/api/v1/admin/collection-runs":
                    content_length = int(self.headers.get("Content-Length", "0"))
                    payload = json.loads(self.rfile.read(content_length))
                    self.server.profile_id = payload["monitoringProfileId"]
                    self._json(
                        {
                            "collectionRunId": state["run_id"],
                            "status": "SUCCEEDED",
                            "publishedCount": 1,
                            "failedCount": 0,
                            "sources": [
                                {
                                    "sourceId": "source-1",
                                    "status": "PUBLISHED",
                                    "failureMessage": None,
                                }
                            ],
                        }
                    )
                    return
                self.send_error(404)

            def log_message(self, fmt, *args):
                return

            def _json(self, payload):
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.client = MODULE.HttpClient(f"http://{host}:{port}", 2.0)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_run_trial_reaches_materialized_result(self) -> None:
        """Run collection and observe the correlated result through the public REST API."""
        run = MODULE.run_trial(
            self.client,
            "profile-test",
            "GENERAL",
            result_wait_seconds=2.0,
            preview_limit=1,
            require_all_sources=False,
        )

        self.assertEqual("SUCCEEDED", run["status"])
        self.assertEqual("profile-test", self.server.profile_id)

    def test_rejects_backend_without_enabled_sources(self) -> None:
        """Reject an environment that cannot execute collection work."""

        class NoSourceClient:
            def get_json(self, path, query=None):
                return []

        with self.assertRaises(MODULE.TrialError):
            MODULE.run_trial(
                NoSourceClient(),
                "profile-test",
                "GENERAL",
                result_wait_seconds=1.0,
                preview_limit=1,
                require_all_sources=False,
            )

    def test_main_verifies_local_rss_fixture_end_to_end(self) -> None:
        """Drive the local RSS fixture mode through a loopback backend and observe two Results."""
        state = {"source": None, "deleted": False}
        run_id = "00000000-0000-0000-0000-000000000903"
        source_id = "00000000-0000-0000-0000-000000000904"
        result_ids = ["b" * 64, "c" * 64]

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                if self.path == "/api/v1/sources":
                    sources = [] if state["source"] is None else [state["source"]]
                    self._json(sources)
                    return
                if self.path.startswith("/api/v1/results?"):
                    self._json([
                        {"monitoringProfileId": "unused", "normalizedItemId": result_id}
                        for result_id in result_ids
                    ])
                    return
                for index, result_id in enumerate(result_ids, start=1):
                    if self.path.startswith(f"/api/v1/results/{result_id}"):
                        self._json({
                            "normalizedItemId": result_id,
                            "correlationId": run_id,
                            "classification": "GENERAL",
                            "score": 100,
                            "url": f"http://fixture/items/{index}",
                            "title": f"Fixture item {index}",
                        })
                        return
                self.send_error(404)

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length))
                if self.path == "/api/v1/sources":
                    state["source"] = {"id": source_id, **payload}
                    self._json(state["source"], status=201)
                    return
                if self.path == "/api/v1/admin/collection-runs":
                    with urlopen(state["source"]["location"], timeout=2) as response:
                        feed = response.read().decode("utf-8")
                    self_test.assertEqual(2, feed.count("<item>"))
                    self._json({
                        "collectionRunId": run_id,
                        "status": "SUCCEEDED",
                        "publishedCount": 2,
                        "failedCount": 0,
                        "sources": [
                            {"sourceId": source_id, "status": "PUBLISHED", "rawItemId": "1" * 64, "failureMessage": None},
                            {"sourceId": source_id, "status": "PUBLISHED", "rawItemId": "2" * 64, "failureMessage": None},
                        ],
                    }, status=201)
                    return
                self.send_error(404)

            def do_DELETE(self):  # noqa: N802
                if self.path == f"/api/v1/sources/{source_id}":
                    state["deleted"] = True
                    state["source"] = None
                    self.send_response(204)
                    self.end_headers()
                    return
                self.send_error(404)

            def log_message(self, fmt, *args):
                return

            def _json(self, payload, status=200):
                body = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self_test = self
        backend = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=backend.serve_forever, daemon=True)
        thread.start()
        host, port = backend.server_address
        try:
            exit_code = MODULE.main([
                "--base-url", f"http://{host}:{port}",
                "--profile", "profile-rss-fixture",
                "--local-rss-fixture",
                "--result-wait", "2",
                "--preview-limit", "0",
            ])
        finally:
            backend.shutdown()
            backend.server_close()
            thread.join(timeout=2)

        self.assertEqual(0, exit_code)
        self.assertTrue(state["deleted"])

    def test_local_rss_fixture_registers_two_entry_feed_and_cleans_up(self) -> None:
        """Expose a deterministic feed through a temporary source and delete it afterward."""
        calls = {"created": None, "deleted": None}

        class FixtureClient:
            def post_json(self, path, payload):
                calls["created"] = (path, payload)
                with urlopen(payload["location"], timeout=2) as response:
                    feed = response.read().decode("utf-8")
                self_test.assertEqual(2, feed.count("<item>"))
                return {"id": "00000000-0000-0000-0000-000000000902"}

            def delete(self, path):
                calls["deleted"] = path

        self_test = self
        with MODULE.local_rss_fixture(FixtureClient()) as (source_id, location):
            self.assertEqual("00000000-0000-0000-0000-000000000902", source_id)
            self.assertTrue(location.endswith("/feed.xml"))

        self.assertEqual("/api/v1/sources", calls["created"][0])
        self.assertEqual(
            "/api/v1/sources/00000000-0000-0000-0000-000000000902",
            calls["deleted"],
        )


if __name__ == "__main__":
    unittest.main()
