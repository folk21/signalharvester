#!/usr/bin/env python3
"""Import versioned source manifests through SignalHarvester's public REST API."""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import SplitResult, urlsplit, urlunsplit
from urllib.request import Request, urlopen

SUPPORTED_MANIFEST_VERSION = 1
SUPPORTED_SOURCE_TYPES = frozenset({"REST", "RSS", "HTML"})
DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_TIMEOUT_SECONDS = 10.0
MAX_ERROR_BODY_CHARS = 1000


class ManifestValidationError(ValueError):
    """Signals that the source manifest is structurally or semantically invalid."""


class SourceApiError(RuntimeError):
    """Signals an HTTP or transport failure while calling the SignalHarvester API."""


@dataclass(frozen=True)
class SourceDefinition:
    """Represents one validated source definition from an import manifest."""

    name: str
    type: str
    location: str
    enabled: bool
    settings: dict[str, str]

    @property
    def identity(self) -> tuple[str, str]:
        """Returns the importer identity used for idempotent matching."""
        return self.type, normalize_location(self.location)

    def to_request_payload(self) -> dict[str, Any]:
        """Maps the definition to the public source-create REST payload."""
        return {
            "name": self.name,
            "type": self.type,
            "location": self.location,
            "enabled": self.enabled,
            "settings": self.settings,
        }


@dataclass(frozen=True)
class ExistingSource:
    """Represents the minimum source state required for importer identity matching."""

    id: str
    name: str
    type: str
    location: str
    enabled: bool
    settings: dict[str, str]

    @property
    def identity(self) -> tuple[str, str]:
        """Returns the normalized importer identity of the persisted source."""
        return self.type, normalize_location(self.location)


@dataclass(frozen=True)
class ImportResult:
    """Summarizes one manifest source outcome."""

    source: SourceDefinition
    status: str
    detail: str


@dataclass(frozen=True)
class ImportSummary:
    """Aggregates source-import outcomes for process exit and console reporting."""

    created: int
    skipped: int
    planned: int
    failed: int

    @property
    def successful(self) -> bool:
        """Returns whether every requested import action completed without failure."""
        return self.failed == 0


class SourceApiClient:
    """Minimal stdlib HTTP client for the SignalHarvester source configuration API."""

    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be greater than zero")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def list_sources(self) -> list[ExistingSource]:
        """Lists existing configured sources from the public REST API."""
        payload = self._request_json("GET", "/api/v1/sources", expected_status=200)
        if not isinstance(payload, list):
            raise SourceApiError("GET /api/v1/sources returned a non-array JSON payload")
        return [parse_existing_source(item, index) for index, item in enumerate(payload)]

    def create_source(self, source: SourceDefinition) -> ExistingSource:
        """Creates one configured source through the public REST API."""
        payload = self._request_json(
            "POST",
            "/api/v1/sources",
            expected_status=201,
            request_payload=source.to_request_payload(),
        )
        return parse_existing_source(payload, None)

    def _request_json(
        self,
        method: str,
        path: str,
        expected_status: int,
        request_payload: dict[str, Any] | None = None,
    ) -> Any:
        body = None
        headers = {"Accept": "application/json"}
        if request_payload is not None:
            body = json.dumps(request_payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = Request(self._base_url + path, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:
                status = response.status
                response_body = response.read()
        except HTTPError as error:
            error_body = error.read().decode("utf-8", errors="replace")
            raise SourceApiError(
                f"{method} {path} failed with HTTP {error.code}: "
                f"{truncate(error_body.strip() or '<empty response body>')}"
            ) from error
        except (URLError, TimeoutError, OSError) as error:
            raise SourceApiError(f"{method} {path} failed: {error}") from error

        if status != expected_status:
            rendered = response_body.decode("utf-8", errors="replace")
            raise SourceApiError(
                f"{method} {path} returned HTTP {status}; expected {expected_status}: "
                f"{truncate(rendered.strip() or '<empty response body>')}"
            )

        if not response_body:
            raise SourceApiError(f"{method} {path} returned an empty response body")

        try:
            return json.loads(response_body)
        except json.JSONDecodeError as error:
            raise SourceApiError(f"{method} {path} returned invalid JSON: {error}") from error


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parses source-import command-line arguments."""
    parser = argparse.ArgumentParser(
        description="Import SignalHarvester sources from a versioned JSON manifest through REST."
    )
    parser.add_argument("--file", type=Path, required=True, help="Versioned source manifest JSON file.")
    parser.add_argument(
        "--base-url",
        default=os.environ.get("SIGNALHARVESTER_BASE_URL", DEFAULT_BASE_URL),
        help=(
            "SignalHarvester backend base URL. Defaults to SIGNALHARVESTER_BASE_URL or "
            f"{DEFAULT_BASE_URL}."
        ),
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        help=f"HTTP timeout in seconds (default: {DEFAULT_TIMEOUT_SECONDS:g}).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List existing sources and report creates without sending POST requests.",
    )
    parser.add_argument(
        "--fail-fast",
        action="store_true",
        help="Stop after the first source create/identity failure instead of continuing.",
    )
    return parser.parse_args(argv)


def normalize_location(location: str) -> str:
    """Normalizes HTTP(S) locations only for importer identity comparison."""
    try:
        parsed = urlsplit(location)
        port = parsed.port
    except ValueError as error:
        raise ManifestValidationError(f"invalid source location {location!r}: {error}") from error

    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise ManifestValidationError(f"source location must use http or https: {location!r}")
    if not parsed.hostname:
        raise ManifestValidationError(f"source location must contain a host: {location!r}")
    if parsed.username is not None or parsed.password is not None:
        raise ManifestValidationError(f"source location must not contain credentials: {location!r}")
    if parsed.fragment:
        raise ManifestValidationError(f"source location must not contain a fragment: {location!r}")
    if any(character.isspace() for character in location):
        raise ManifestValidationError(f"source location must not contain whitespace: {location!r}")

    hostname = parsed.hostname.lower()
    rendered_host = f"[{hostname}]" if ":" in hostname and not hostname.startswith("[") else hostname
    default_port = (scheme == "http" and port == 80) or (scheme == "https" and port == 443)
    netloc = rendered_host if port is None or default_port else f"{rendered_host}:{port}"
    path = parsed.path or "/"
    normalized = SplitResult(scheme, netloc, path, parsed.query, "")
    return urlunsplit(normalized)


def load_manifest(path: Path) -> list[SourceDefinition]:
    """Loads and validates a complete version-1 source manifest before any API mutation."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise ManifestValidationError(f"cannot read manifest {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ManifestValidationError(f"manifest {path} is not valid JSON: {error}") from error

    if not isinstance(document, dict):
        raise ManifestValidationError("manifest root must be a JSON object")

    unknown_root_fields = set(document) - {"version", "sources"}
    if unknown_root_fields:
        raise ManifestValidationError(
            f"manifest contains unsupported root fields: {', '.join(sorted(unknown_root_fields))}"
        )
    if document.get("version") != SUPPORTED_MANIFEST_VERSION:
        raise ManifestValidationError(
            f"manifest version must be {SUPPORTED_MANIFEST_VERSION}; got {document.get('version')!r}"
        )

    raw_sources = document.get("sources")
    if not isinstance(raw_sources, list):
        raise ManifestValidationError("manifest sources must be a JSON array")

    sources = [parse_source_definition(raw_source, index) for index, raw_source in enumerate(raw_sources)]
    ensure_unique_manifest_identities(sources)
    return sources


def parse_source_definition(raw_source: Any, index: int) -> SourceDefinition:
    """Validates one source manifest entry and returns its canonical in-memory form."""
    prefix = f"sources[{index}]"
    if not isinstance(raw_source, dict):
        raise ManifestValidationError(f"{prefix} must be a JSON object")

    allowed_fields = {"name", "type", "location", "enabled", "settings"}
    unknown_fields = set(raw_source) - allowed_fields
    if unknown_fields:
        raise ManifestValidationError(
            f"{prefix} contains unsupported fields: {', '.join(sorted(unknown_fields))}"
        )

    name = raw_source.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ManifestValidationError(f"{prefix}.name must be a non-blank string")

    source_type = raw_source.get("type")
    if source_type not in SUPPORTED_SOURCE_TYPES:
        supported = ", ".join(sorted(SUPPORTED_SOURCE_TYPES))
        raise ManifestValidationError(f"{prefix}.type must be one of {supported}")

    location = raw_source.get("location")
    if not isinstance(location, str) or not location:
        raise ManifestValidationError(f"{prefix}.location must be a non-empty string")
    normalize_location(location)

    enabled = raw_source.get("enabled", False)
    if not isinstance(enabled, bool):
        raise ManifestValidationError(f"{prefix}.enabled must be a boolean when present")

    settings = raw_source.get("settings", {})
    if not isinstance(settings, dict):
        raise ManifestValidationError(f"{prefix}.settings must be a JSON object when present")
    for key, value in settings.items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise ManifestValidationError(f"{prefix}.settings keys and values must be strings")

    return SourceDefinition(
        name=name,
        type=source_type,
        location=location,
        enabled=enabled,
        settings=dict(settings),
    )


def parse_existing_source(raw_source: Any, index: int | None) -> ExistingSource:
    """Validates the fields required from the backend source response."""
    prefix = "created source" if index is None else f"existing sources[{index}]"
    if not isinstance(raw_source, dict):
        raise SourceApiError(f"{prefix} must be a JSON object")

    source_id = raw_source.get("id")
    name = raw_source.get("name")
    source_type = raw_source.get("type")
    location = raw_source.get("location")
    enabled = raw_source.get("enabled")
    settings = raw_source.get("settings")

    if not isinstance(source_id, str) or not source_id:
        raise SourceApiError(f"{prefix}.id must be a non-empty string")
    if not isinstance(name, str):
        raise SourceApiError(f"{prefix}.name must be a string")
    if source_type not in SUPPORTED_SOURCE_TYPES:
        raise SourceApiError(f"{prefix}.type is unsupported: {source_type!r}")
    if not isinstance(location, str):
        raise SourceApiError(f"{prefix}.location must be a string")
    try:
        normalize_location(location)
    except ManifestValidationError as error:
        raise SourceApiError(f"{prefix}.location is invalid: {error}") from error
    if not isinstance(enabled, bool):
        raise SourceApiError(f"{prefix}.enabled must be a boolean")
    if not isinstance(settings, dict) or not all(
        isinstance(key, str) and isinstance(value, str) for key, value in settings.items()
    ):
        raise SourceApiError(f"{prefix}.settings must contain string keys and values")

    return ExistingSource(
        id=source_id,
        name=name,
        type=source_type,
        location=location,
        enabled=enabled,
        settings=dict(settings),
    )


def ensure_unique_manifest_identities(sources: Iterable[SourceDefinition]) -> None:
    """Rejects duplicate importer identities before making any backend request."""
    identities: dict[tuple[str, str], int] = {}
    for index, source in enumerate(sources):
        previous = identities.get(source.identity)
        if previous is not None:
            source_type, location = source.identity
            raise ManifestValidationError(
                f"sources[{index}] duplicates sources[{previous}] by importer identity "
                f"{source_type} {location}"
            )
        identities[source.identity] = index


def import_sources(
    sources: list[SourceDefinition],
    client: SourceApiClient,
    dry_run: bool,
    fail_fast: bool,
) -> list[ImportResult]:
    """Imports missing source identities while preserving existing persisted configuration."""
    if not sources:
        return []

    existing_by_identity: dict[tuple[str, str], list[ExistingSource]] = {}
    for existing in client.list_sources():
        existing_by_identity.setdefault(existing.identity, []).append(existing)

    results: list[ImportResult] = []
    for source in sources:
        matches = existing_by_identity.get(source.identity, [])
        if len(matches) > 1:
            detail = "multiple persisted sources share this type/location identity; manual cleanup required"
            results.append(ImportResult(source, "FAILED", detail))
            if fail_fast:
                break
            continue

        if len(matches) == 1:
            existing = matches[0]
            detail = f"existing id={existing.id} name={existing.name!r}"
            if differs_from_manifest(existing, source):
                detail += "; persisted attributes differ and were intentionally not updated"
            results.append(ImportResult(source, "SKIPPED", detail))
            continue

        if dry_run:
            results.append(ImportResult(source, "PLANNED", "would create source"))
            continue

        try:
            created = client.create_source(source)
        except SourceApiError as error:
            results.append(ImportResult(source, "FAILED", str(error)))
            if fail_fast:
                break
            continue

        existing_by_identity.setdefault(created.identity, []).append(created)
        results.append(ImportResult(source, "CREATED", f"created id={created.id}"))

    return results


def differs_from_manifest(existing: ExistingSource, source: SourceDefinition) -> bool:
    """Returns whether non-identity persisted attributes differ from the manifest."""
    return (
        existing.name != source.name
        or existing.enabled != source.enabled
        or existing.settings != source.settings
    )


def summarize_results(results: Iterable[ImportResult]) -> ImportSummary:
    """Builds process-level counters from individual source outcomes."""
    counts = {"CREATED": 0, "SKIPPED": 0, "PLANNED": 0, "FAILED": 0}
    for result in results:
        counts[result.status] += 1
    return ImportSummary(
        created=counts["CREATED"],
        skipped=counts["SKIPPED"],
        planned=counts["PLANNED"],
        failed=counts["FAILED"],
    )


def print_results(results: Iterable[ImportResult]) -> ImportSummary:
    """Prints deterministic per-source outcomes and the final import summary."""
    materialized = list(results)
    for result in materialized:
        source_type, location = result.source.identity
        print(f"[{result.status}] {source_type} {location} - {result.source.name}: {result.detail}")

    summary = summarize_results(materialized)
    print(
        "Summary: "
        f"created={summary.created} "
        f"skipped={summary.skipped} "
        f"planned={summary.planned} "
        f"failed={summary.failed}"
    )
    return summary


def truncate(value: str) -> str:
    """Bounds remote error text so one response cannot flood importer output."""
    return value if len(value) <= MAX_ERROR_BODY_CHARS else value[:MAX_ERROR_BODY_CHARS] + "..."


def main(argv: list[str] | None = None) -> int:
    """Runs validation, idempotent REST import, and deterministic summary reporting."""
    args = parse_args(argv)
    if args.timeout <= 0:
        print("ERROR: --timeout must be greater than zero", file=sys.stderr)
        return 2

    try:
        sources = load_manifest(args.file)
        client = SourceApiClient(args.base_url, args.timeout)
        results = import_sources(sources, client, args.dry_run, args.fail_fast)
    except ManifestValidationError as error:
        print(f"ERROR: invalid manifest: {error}", file=sys.stderr)
        return 2
    except SourceApiError as error:
        print(f"ERROR: source API request failed: {error}", file=sys.stderr)
        return 1

    summary = print_results(results)
    return 0 if summary.successful else 1


if __name__ == "__main__":
    raise SystemExit(main())
