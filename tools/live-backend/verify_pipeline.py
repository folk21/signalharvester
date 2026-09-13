#!/usr/bin/env python3
"""Black-box verification of a running SignalHarvester backend through public REST APIs only."""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "http://localhost:8080"
RESULT_API_LIMIT = 200


class TrialError(RuntimeError):
    """Raised when the live backend does not satisfy the expected black-box flow."""


@dataclass(frozen=True)
class HttpClient:
    base_url: str
    timeout: float

    def get_json(self, path: str, query: dict[str, Any] | None = None) -> Any:
        suffix = ""
        if query:
            suffix = "?" + urlencode({key: value for key, value in query.items() if value is not None})
        return self._request_json("GET", path + suffix, None)

    def post_json(self, path: str, payload: dict[str, Any]) -> Any:
        return self._request_json("POST", path, payload)

    def delete(self, path: str) -> None:
        self._request_json("DELETE", path, None)

    def _request_json(self, method: str, path: str, payload: dict[str, Any] | None) -> Any:
        url = self.base_url.rstrip("/") + path
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(url, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=self.timeout) as response:
                content = response.read()
        except HTTPError as failure:
            detail = failure.read().decode("utf-8", errors="replace")
            raise TrialError(f"{method} {url} returned HTTP {failure.code}: {detail}") from failure
        except URLError as failure:
            raise TrialError(f"{method} {url} failed: {failure.reason}") from failure

        if not content:
            return None
        try:
            return json.loads(content)
        except json.JSONDecodeError as failure:
            raise TrialError(f"{method} {url} returned invalid JSON") from failure


def run_importer(manifest: Path, base_url: str, timeout: float) -> None:
    importer = Path(__file__).resolve().parents[1] / "source-import" / "import_sources.py"
    command = [
        sys.executable,
        str(importer),
        "--file",
        str(manifest),
        "--base-url",
        base_url,
        "--timeout",
        str(timeout),
    ]
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    completed = subprocess.run(command, env=environment, check=False)
    if completed.returncode != 0:
        raise TrialError(f"source importer failed with exit code {completed.returncode}")


def run_trial(
    client: HttpClient,
    profile_id: str,
    information_category: str,
    result_wait_seconds: float,
    preview_limit: int,
    require_all_sources: bool,
    expected_source_id: str | None = None,
    expected_source_results: int | None = None,
) -> dict[str, Any]:
    sources = client.get_json("/api/v1/sources")
    enabled_sources = [source for source in sources if source.get("enabled") is True]
    if not enabled_sources:
        raise TrialError("backend has no enabled sources to collect")

    print(f"Enabled sources: {len(enabled_sources)}")
    run = client.post_json(
        "/api/v1/admin/collection-runs",
        {
            "monitoringProfileId": profile_id,
            "informationCategory": information_category,
        },
    )
    run_id = required_string(run, "collectionRunId")
    published_count = required_int(run, "publishedCount")
    failed_count = required_int(run, "failedCount")
    status = required_string(run, "status")
    print(
        f"Collection: run={run_id} status={status} "
        f"publishedItems={published_count} failures={failed_count}"
    )

    for outcome in run.get("sources", []):
        outcome_status = outcome.get("status")
        if outcome_status != "PUBLISHED":
            print(
                "  source=" + str(outcome.get("sourceId"))
                + " status=" + str(outcome_status)
                + " message=" + str(outcome.get("failureMessage"))
            )

    if published_count == 0:
        raise TrialError("collection published no items; downstream Results flow cannot be verified")
    if failed_count and require_all_sources:
        raise TrialError(f"collection reported {failed_count} failures and --require-all-sources was requested")

    if expected_source_id is not None:
        source_published = [
            outcome
            for outcome in run.get("sources", [])
            if outcome.get("sourceId") == expected_source_id and outcome.get("status") == "PUBLISHED"
        ]
        if expected_source_results is not None and len(source_published) != expected_source_results:
            raise TrialError(
                f"expected source {expected_source_id} to publish {expected_source_results} item(s), "
                f"observed {len(source_published)}"
            )
        raw_item_ids = {outcome.get("rawItemId") for outcome in source_published}
        if len(raw_item_ids) != len(source_published):
            raise TrialError(f"source {expected_source_id} produced duplicate raw-item identities")

    expected_results = expected_source_results if expected_source_results is not None else 1
    deadline = time.monotonic() + result_wait_seconds
    matching_details: list[dict[str, Any]] = []
    while time.monotonic() < deadline:
        matching_details = results_for_run(client, profile_id, run_id, expected_source_id)
        if len(matching_details) >= expected_results:
            break
        time.sleep(0.25)

    if len(matching_details) < expected_results:
        raise TrialError(
            f"Results materialization timed out: expected at least {expected_results} analyzed result(s) "
            f"for run {run_id}, observed {len(matching_details)}"
        )

    print(f"Results: materialized={len(matching_details)} profile={profile_id}")
    for detail in matching_details[:preview_limit]:
        title = detail.get("title") or detail.get("normalizedItemId", "")[:12]
        print(
            f"  {title} | classification={detail.get('classification')} "
            f"score={detail.get('score')} | {detail.get('url')}"
        )
    return run


def results_for_run(
    client: HttpClient,
    profile_id: str,
    run_id: str,
    source_id: str | None = None,
) -> list[dict[str, Any]]:
    summaries = client.get_json(
        "/api/v1/results",
        {"monitoringProfileId": profile_id, "sourceId": source_id, "limit": RESULT_API_LIMIT},
    )
    matching: list[dict[str, Any]] = []
    for summary in summaries:
        normalized_item_id = summary.get("normalizedItemId")
        if not isinstance(normalized_item_id, str) or not normalized_item_id:
            continue
        detail = client.get_json(
            f"/api/v1/results/{normalized_item_id}",
            {"monitoringProfileId": profile_id},
        )
        if detail.get("correlationId") == run_id:
            matching.append(detail)
    return matching


@contextlib.contextmanager
def local_rss_fixture(client: HttpClient):
    """Expose a deterministic two-entry RSS feed and register it as a temporary backend source."""

    feed = b"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>SignalHarvester live fixture</title>
  <item><guid>fixture-entry-1</guid><title>Java backend fixture</title>
    <link>/items/1</link><description>Java backend Kafka integration</description>
    <pubDate>Sun, 13 Sep 2026 18:00:00 GMT</pubDate></item>
  <item><guid>fixture-entry-2</guid><title>PostgreSQL fixture</title>
    <link>/items/2</link><description>PostgreSQL analysis result fixture</description>
    <pubDate>Sun, 13 Sep 2026 18:01:00 GMT</pubDate></item>
</channel></rss>
"""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            if self.path != "/feed.xml":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/rss+xml; charset=UTF-8")
            self.send_header("Content-Length", str(len(feed)))
            self.end_headers()
            self.wfile.write(feed)

        def log_message(self, fmt, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    location = f"http://{host}:{port}/feed.xml"
    source = None
    try:
        source = client.post_json(
            "/api/v1/sources",
            {
                "name": f"Live RSS fixture {uuid.uuid4().hex[:8]}",
                "type": "RSS",
                "location": location,
                "enabled": True,
                "settings": {},
            },
        )
        yield required_string(source, "id"), location
    finally:
        if source is not None:
            source_id = source.get("id")
            if isinstance(source_id, str) and source_id:
                try:
                    client.delete(f"/api/v1/sources/{source_id}")
                except TrialError as failure:
                    print(f"WARNING: failed to delete temporary RSS fixture source: {failure}", file=sys.stderr)
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def required_string(value: dict[str, Any], key: str) -> str:
    candidate = value.get(key)
    if not isinstance(candidate, str) or not candidate:
        raise TrialError(f"backend response is missing non-empty string field {key!r}")
    return candidate


def required_int(value: dict[str, Any], key: str) -> int:
    candidate = value.get(key)
    if not isinstance(candidate, int):
        raise TrialError(f"backend response is missing integer field {key!r}")
    return candidate


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.environ.get("SIGNALHARVESTER_BASE_URL", DEFAULT_BASE_URL),
        help="running backend base URL",
    )
    parser.add_argument("--manifest", type=Path, help="optional source manifest to import before the trial")
    parser.add_argument(
        "--profile",
        default=None,
        help="monitoring profile id; defaults to a unique live-trial id to avoid dedup from earlier runs",
    )
    parser.add_argument("--category", default="GENERAL", help="collection information category")
    parser.add_argument("--timeout", type=float, default=10.0, help="HTTP timeout in seconds")
    parser.add_argument("--result-wait", type=float, default=30.0, help="maximum wait for Results materialization")
    parser.add_argument("--preview-limit", type=int, default=5, help="number of materialized results to print")
    parser.add_argument(
        "--require-all-sources",
        action="store_true",
        help="fail when the collection run reports any source/item failure",
    )
    parser.add_argument(
        "--local-rss-fixture",
        action="store_true",
        help="create a temporary local two-item RSS source and verify its extraction end to end",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.timeout <= 0 or args.result_wait <= 0 or args.preview_limit < 0:
        print("ERROR: timeout/result-wait must be positive and preview-limit non-negative", file=sys.stderr)
        return 2
    profile_id = args.profile or f"live-trial-{uuid.uuid4().hex[:12]}"
    try:
        if args.manifest:
            run_importer(args.manifest, args.base_url, args.timeout)
        client = HttpClient(args.base_url, args.timeout)
        if args.local_rss_fixture:
            with local_rss_fixture(client) as (source_id, location):
                print(f"Local RSS fixture: source={source_id} location={location}")
                run_trial(
                    client,
                    profile_id,
                    args.category,
                    args.result_wait,
                    args.preview_limit,
                    args.require_all_sources,
                    expected_source_id=source_id,
                    expected_source_results=2,
                )
        else:
            run_trial(
                client,
                profile_id,
                args.category,
                args.result_wait,
                args.preview_limit,
                args.require_all_sources,
            )
    except TrialError as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    print("Live backend pipeline verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
