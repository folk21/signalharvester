#!/usr/bin/env python3
"""Regression tests for source-import manifest validation and idempotent REST behavior."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import threading
import unittest
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "import_sources.py"
SPEC = importlib.util.spec_from_file_location("signalharvester_source_import", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
source_import = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = source_import
SPEC.loader.exec_module(source_import)


class FakeClient:
    """Provides deterministic existing/create behavior for importer orchestration tests."""

    def __init__(self, existing=None, failures=None):
        self.existing = list(existing or [])
        self.failures = set(failures or [])
        self.created = []

    def list_sources(self):
        return list(self.existing)

    def create_source(self, source):
        if source.name in self.failures:
            raise source_import.SourceApiError(f"synthetic failure for {source.name}")
        created = source_import.ExistingSource(
            id=f"created-{len(self.created) + 1}",
            name=source.name,
            type=source.type,
            location=source.location,
            enabled=source.enabled,
            settings=source.settings,
        )
        self.created.append(created)
        return created


class SourceImportManifestTest(unittest.TestCase):
    """Verifies manifest validation, identity normalization, and duplicate rejection."""

    def test_normalizes_location_for_identity_without_mutating_manifest_location(self):
        """Normalize scheme/host/default port and empty path for importer identity matching."""
        source = source_import.SourceDefinition(
            "Jobs",
            "REST",
            "HTTPS://Example.COM:443?team=java",
            True,
            {},
        )

        self.assertEqual(("REST", "https://example.com/?team=java"), source.identity)
        self.assertEqual("HTTPS://Example.COM:443?team=java", source.location)

    def test_loads_defaults_and_rejects_duplicate_normalized_identity(self):
        """Apply backend-compatible defaults and reject duplicate type/location identities."""
        manifest = {
            "version": 1,
            "sources": [
                {"name": "One", "type": "RSS", "location": "https://EXAMPLE.test/feed"},
                {"name": "Two", "type": "RSS", "location": "https://example.test:443/feed"},
            ],
        }

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "sources.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(source_import.ManifestValidationError, "duplicates"):
                source_import.load_manifest(path)

    def test_rejects_unknown_fields_and_non_string_settings(self):
        """Reject typo-prone manifest fields and settings that violate the REST string map contract."""
        with self.assertRaisesRegex(source_import.ManifestValidationError, "unsupported fields"):
            source_import.parse_source_definition(
                {
                    "name": "Jobs",
                    "type": "REST",
                    "location": "https://example.test/jobs",
                    "extra": "typo",
                },
                0,
            )

        with self.assertRaisesRegex(source_import.ManifestValidationError, "keys and values must be strings"):
            source_import.parse_source_definition(
                {
                    "name": "Jobs",
                    "type": "REST",
                    "location": "https://example.test/jobs",
                    "settings": {"limit": 10},
                },
                0,
            )


class SourceImportOrchestrationTest(unittest.TestCase):
    """Verifies create/skip/failure semantics of the idempotent source bootstrap workflow."""

    def source(self, name="Jobs", location="https://example.test/jobs"):
        return source_import.SourceDefinition(name, "REST", location, True, {"query": "java"})

    def test_skips_existing_identity_and_does_not_reconcile_different_attributes(self):
        """Treat type/location as identity and leave differing persisted attributes untouched."""
        existing = source_import.ExistingSource(
            id="source-01",
            name="Old name",
            type="REST",
            location="https://EXAMPLE.test:443/jobs",
            enabled=False,
            settings={},
        )
        client = FakeClient(existing=[existing])

        results = source_import.import_sources([self.source()], client, dry_run=False, fail_fast=False)

        self.assertEqual("SKIPPED", results[0].status)
        self.assertIn("intentionally not updated", results[0].detail)
        self.assertEqual([], client.created)

    def test_dry_run_reports_missing_source_without_creating_it(self):
        """Resolve existing state during dry-run but never issue source creation."""
        client = FakeClient()

        results = source_import.import_sources([self.source()], client, dry_run=True, fail_fast=False)

        self.assertEqual("PLANNED", results[0].status)
        self.assertEqual([], client.created)

    def test_creates_missing_sources(self):
        """Create each manifest identity absent from the current backend source list."""
        client = FakeClient()

        results = source_import.import_sources([self.source()], client, dry_run=False, fail_fast=False)

        self.assertEqual("CREATED", results[0].status)
        self.assertEqual(["Jobs"], [created.name for created in client.created])

    def test_continues_after_create_failure_by_default(self):
        """Record a failed create and continue importing later independent sources by default."""
        client = FakeClient(failures={"Broken"})
        sources = [
            self.source("Broken", "https://example.test/broken"),
            self.source("Healthy", "https://example.test/healthy"),
        ]

        results = source_import.import_sources(sources, client, dry_run=False, fail_fast=False)

        self.assertEqual(["FAILED", "CREATED"], [result.status for result in results])
        self.assertEqual(["Healthy"], [created.name for created in client.created])

    def test_fail_fast_stops_after_first_create_failure(self):
        """Stop processing later manifest entries when fail-fast mode observes a create failure."""
        client = FakeClient(failures={"Broken"})
        sources = [
            self.source("Broken", "https://example.test/broken"),
            self.source("Healthy", "https://example.test/healthy"),
        ]

        results = source_import.import_sources(sources, client, dry_run=False, fail_fast=True)

        self.assertEqual(["FAILED"], [result.status for result in results])
        self.assertEqual([], client.created)

    def test_reports_ambiguous_existing_identity_as_failure(self):
        """Require manual cleanup when persisted state contains duplicate importer identities."""
        source = self.source()
        existing = [
            source_import.ExistingSource("source-01", "One", "REST", source.location, True, {}),
            source_import.ExistingSource("source-02", "Two", "REST", source.location, True, {}),
        ]

        results = source_import.import_sources([source], FakeClient(existing=existing), False, False)

        self.assertEqual("FAILED", results[0].status)
        self.assertIn("manual cleanup required", results[0].detail)


class SourceApiClientTest(unittest.TestCase):
    """Verifies the stdlib HTTP client against the public `/api/v1/sources` JSON contract."""

    def setUp(self):
        class Handler(BaseHTTPRequestHandler):
            sources = []
            requests = []

            def do_GET(self):
                if self.path != "/api/v1/sources":
                    self.send_error(404)
                    return
                self.respond(200, self.sources)

            def do_POST(self):
                if self.path != "/api/v1/sources":
                    self.send_error(404)
                    return
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length))
                self.requests.append(payload)
                created = {"id": "source-created", **payload}
                self.sources.append(created)
                self.respond(201, created)

            def respond(self, status, payload):
                body = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                pass

        Handler.sources = []
        Handler.requests = []
        self.handler = Handler
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.client = source_import.SourceApiClient(f"http://{host}:{port}", 2.0)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2.0)

    def test_round_trips_list_and_create_through_public_json_shape(self):
        """Use GET/POST with backend-compatible JSON fields and expected HTTP status codes."""
        source = source_import.SourceDefinition(
            "Jobs",
            "REST",
            "https://example.test/jobs",
            True,
            {"query": "java"},
        )

        self.assertEqual([], self.client.list_sources())
        created = self.client.create_source(source)
        listed = self.client.list_sources()

        self.assertEqual("source-created", created.id)
        self.assertEqual([source.to_request_payload()], self.handler.requests)
        self.assertEqual([created], listed)


if __name__ == "__main__":
    unittest.main()
