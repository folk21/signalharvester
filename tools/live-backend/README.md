---
type: Tool Guide
title: Live backend pipeline verification
description: Black-box verification of source import, manual collection, Analysis, persistence, and Results REST against a running SignalHarvester backend.
---
# Live backend pipeline verification

`verify_pipeline.py` exercises a running backend only through public REST APIs. It is intentionally not part of `run_checks.sh` because it requires a separately running backend and, for real-source trials, external network access.

The workflow is:

```text
optional source manifest import
    -> GET /api/v1/sources
    -> POST /api/v1/admin/collection-runs
    -> Kafka / Analysis / Results internally
    -> GET /api/v1/results
    -> profile-scoped result detail
```

By default the tool creates a unique `live-trial-...` monitoring-profile id. This avoids previous Analysis deduplication state turning a repeated real-source trial into rejected duplicates instead of fresh analyzed Results.

Run against already configured enabled sources:

```bash
python3 tools/live-backend/verify_pipeline.py \
  --base-url http://localhost:8080 \
  --category GENERAL
```

For a deterministic RSS/Atom extraction check with no public-network dependency, start the backend on the host and run:

```bash
python3 tools/live-backend/verify_pipeline.py \
  --base-url http://localhost:8080 \
  --local-rss-fixture \
  --category GENERAL
```

This mode starts a temporary loopback RSS server with two entries, creates one temporary RSS source through the public REST API, requires two distinct published source outcomes and two materialized Results, then deletes the temporary source. Other already-enabled sources may still participate in the same collection run; use a clean local database for the most isolated signal. Because the backend must reach the loopback fixture, this mode targets a backend process running on the same host rather than inside an isolated container network.

Import a manifest first:

```bash
python3 tools/live-backend/verify_pipeline.py \
  --base-url http://localhost:8080 \
  --manifest tools/source-import/sources.real-trial.json \
  --category GENERAL
```

External-source failures are printed but do not fail the general trial when at least one item publishes and at least one analyzed Result correlated to the run materializes successfully. Add `--require-all-sources` when every source/item must succeed. The deterministic local RSS mode applies stronger source-specific assertions for its two fixture entries.

The Results feed is bounded to 200 items. General trials therefore verify downstream reachability rather than assuming a one-to-one relationship between every published raw event and analyzed Results; Analysis may legitimately reject duplicates.

Tool self-tests use a loopback fake server only:

```bash
./tools/live-backend/run_tests.sh
```
