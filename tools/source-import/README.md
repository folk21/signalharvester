---
type: Tool Guide
title: Source manifest importer
description: Black-box REST bootstrap tool for idempotently creating SignalHarvester source configuration from versioned JSON manifests.
---
# Source manifest importer

`import_sources.py` bootstraps source configuration through the public `/api/v1/sources` REST API. It does not connect to PostgreSQL and intentionally reuses backend validation, transactions, and source lifecycle rules.

The tool is intended for controlled local/trial setup and repeatable environment bootstrap. It is not a bidirectional configuration synchronizer.

## Requirements

- Python 3.11+;
- a running SignalHarvester backend reachable over HTTP;
- trusted source configuration. The backend does not yet enforce an outbound SSRF/destination policy, so do not import untrusted locations.

The importer uses only the Python standard library.

## Manifest format

The manifest is versioned so later schema changes can be introduced explicitly:

```json
{
  "version": 1,
  "sources": [
    {
      "name": "Jobs feed",
      "type": "RSS",
      "location": "https://example.test/jobs.xml",
      "enabled": false,
      "settings": {}
    }
  ]
}
```

Version 1 accepts the backend source types `REST`, `RSS`, and `HTML`. `enabled` defaults to `false` and `settings` defaults to `{}`. Settings keys and values must be strings, matching the REST contract.

See [`sources.example.json`](sources.example.json) for a copyable starting point.

Do not put credentials or secrets directly into manifests or source `settings`. Source-secret references require a separate backend capability that is not implemented yet.

## Identity and idempotency

The importer identifies a source by:

```text
source type + normalized location
```

Location normalization is intentionally conservative and is used only for matching:

- scheme and hostname are lowercased;
- default ports `:80` and `:443` are removed;
- an empty path is normalized to `/`;
- path and query semantics are otherwise preserved.

The original manifest location is sent unchanged to the backend.

A repeated import therefore skips an already persisted source with the same type/location identity. Source `name` is not an identity.

If an existing source has the same identity but different `name`, `enabled`, or `settings`, the importer reports the difference and **does not update it**. If multiple persisted sources share one importer identity, the entry fails and requires manual cleanup rather than guessing which row to update.

Duplicate identities inside one manifest are rejected before any API mutation.

## Usage

Validate the manifest against current backend state without creating anything:

```bash
python3 tools/source-import/import_sources.py \
  --file tools/source-import/sources.example.json \
  --base-url http://localhost:8080 \
  --dry-run
```

Import missing sources:

```bash
python3 tools/source-import/import_sources.py \
  --file path/to/sources.json \
  --base-url http://localhost:8080
```

`--base-url` defaults to `SIGNALHARVESTER_BASE_URL` when that environment variable is set, otherwise to `http://localhost:8080`.

Use a bounded HTTP timeout when required:

```bash
python3 tools/source-import/import_sources.py \
  --file path/to/sources.json \
  --timeout 5
```

By default one failed `POST` is reported and later independent source entries are still attempted. Add `--fail-fast` to stop after the first create or ambiguous-identity failure.

Typical output:

```text
[SKIPPED] RSS https://example.test/jobs.xml - Jobs feed: existing id=... name='Jobs feed'
[CREATED] REST https://example.test/jobs - Jobs API: created id=...
Summary: created=1 skipped=1 planned=0 failed=0
```

Exit codes:

- `0` — manifest processed without failed source outcomes;
- `1` — REST/transport failure or at least one source import failure;
- `2` — invalid manifest or CLI validation failure.

## Tests

Run importer tests directly:

```bash
./tools/source-import/run_tests.sh
```

The tests use deterministic fakes and a loopback HTTP server only; they do not require a running SignalHarvester backend or public network access.
