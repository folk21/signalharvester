---
type: Implementation Guide
title: Development agent pipeline implementation guidance
description: Advisory implementation suggestions for active staged development agent pipeline work.
---
# Development agent pipeline — implementation plan

This is **advisory guidance** for [`development-agent-pipeline.md`](development-agent-pipeline.md). The active specification and current repository contracts remain normative. `MAIN_MODEL` prepares this context; `WORKER_MODEL` may choose a better implementation inside its validated editable scope, and must report material deviations.

## Owning area and context

- Owning area: `tools/agent-pipeline/` (contributor tooling, not a functional Java module).
- Required repository instructions: root `README.md`, `AGENTS.md`, `docs/specs/README.md` and the active development-agent-pipeline specification.
- Owning documentation: `tools/agent-pipeline/README.md`; verification conventions: `docs/TESTS.md`.
- No product feature ID applies to this contributor-tooling workflow. Do not invent one.
- No other module's private implementation is needed for this stage.

## A4 — delegation packets, context and evidence

Requirements: AP9, AP10, AP14. Expected direction: extend `agent_pipeline.py` with a small structured packet rather than execute generated shell commands. Bind the normative specification, advisory companion and selected read-only context by digest; enforce actual changed paths after model execution, including untracked files. Existing one-shot and repair commands should remain compatible for legacy runs.

Likely existing files (**expected**): `tools/agent-pipeline/agent_pipeline.py`, `tools/agent-pipeline/tests/test_agent_pipeline.py`, `tools/agent-pipeline/README.md`.

Likely new files (**candidate**): `tools/agent-pipeline/examples/*` and a simple opt-in pilot runner. Avoid a new Python dependency, web service, or opaque agent framework. Scope and risk metadata must never silently authorize a contract or security policy change.

For evidence enrichment, prefer compact references and digests over raw model output. Independently review source-bound worker changes and all required checks after each repair; do not reuse stale PASS evidence.

## A5 — feature-branch progression (future)

Requirements: AP12, AP15. Treat each verified stage source state as an immutable promotion candidate. Verify feature-branch HEAD and run base before any commit; never enable automatic merge or archival. No A5 implementation is authorized by the A4 pilot packet.

## Alternatives, assumptions and open questions

- A Stage Task Packet can use strict JSON without a new dependency. A future typed schema may be useful if many sub-specs need structured authoring; do not add one prematurely.
- Read-only Aider context guides the model, but is **not** filesystem read isolation. Git diff enforcement controls what can be accepted; it does not sandbox a model's access to repository files.
- Limit initial automated delegation to bounded low-risk stages. High-risk cases and unknown ownership need explicit human-led planning.
- The developer must initiate final `MAIN_MODEL` verification in ChatGPT and explicitly accept the complete sub-spec before a paired archive move.


## A4 bounded handoff-integrity pilot — concrete verification scenarios

This section advises the bounded A4 implementation of AP2, AP10, and AP13.
The normative specification and the approved Stage Task Packet remain authoritative.
The goal is a repeatable comparison with the earlier `workerAttempts` pilot: same
production task and editable Python paths, more precise verification guidance.
It does not authorize A5, merge, archival, or changes to reviewer/security policy.

### Suggested implementation

- Extend `write_handoff()` additively with `workerAttempts`, preserving every legacy
  handoff key and the existing `worker` summary. Keep the change localized to the
  pipeline controller and its Python tests.
- Enumerate `worker-attempt-N.json` using canonical positive decimal names, sort by
  numeric attempt, and validate every discovered JSON record before publishing.
  The latest `worker.json` is authoritative for the latest attempt; do not
  silently discard malformed or conflicting snapshots.
- Compare the **entire authoritative latest worker record** to its snapshot where
  both exist. Comparing only the selected public fields misses `taskSha256`,
  `model`, `logSha256`, `command`, and other evidence-bearing values.
- Select compact bounded metadata for the public list. Each snapshot that exists
  should have an `evidenceFile` reference and its SHA-256; when only a legacy
  `worker.json` exists for attempt 1, an entry without a snapshot reference is
  acceptable. Do not invent snapshot files.
- Snapshot digests computed during handoff bind the resulting index to the
  **current** file bytes. They do not prove an earlier immutable history unless
  compared with a separately trusted digest. Do not claim historical tamper
  detection without such a trust anchor.
- Use `type(value) is int` for attempt numbers. Python booleans compare equal to
  integers, so equality alone is not a valid numeric-schema check.
- Use source-consistent Git fixtures or narrowly isolate `update_status()` for
  tests specifically about handoff serialization. Never change production
  source-integrity checks to satisfy an inconsistent fixture.

### Concrete scenario inventory

The worker should add focused regression tests for these cases. The independent
`tools/agent-pipeline/benchmarks/handoff_integrity_oracle.py` runs corresponding
black-box checks against the final worker implementation without being editable
by the worker. The scenario IDs below are **test case IDs**, not new normative
specification requirement IDs.

| Scenario | Setup and operation | Required observable result |
|---|---|---|
| HI-01 | No `worker.json`, no attempt snapshots; call `write_handoff` | `workerAttempts` is empty; existing handoff keys remain |
| HI-02 | Legacy `worker.json` with attempt 1; no snapshot | Exactly one summary entry; no invented `evidenceFile` |
| HI-03 | Single attempt 1 has both authoritative worker and matching snapshot | Exactly one entry; snapshot filename and SHA-256 present |
| HI-04 | Snapshot attempts 1, 2, 3 and matching latest worker 3 | Three numeric-ordered entries; every existing snapshot has correct filename and SHA-256 |
| HI-05 | A discovered snapshot contains malformed JSON or a JSON array | Fail closed; no `handoff.json` publication |
| HI-06 | Snapshot named `worker-attempt-01.json` or `worker-attempt-0.json` | Reject noncanonical filename |
| HI-07 | Snapshot filename N but its `attempt` is N+1, `true`, a string, or a float | Reject all malformed payload attempt identifiers |
| HI-08 | `worker.json` attempt is `true`, zero, or a noninteger | Reject invalid authoritative attempt identifier |
| HI-09 | Snapshots 1 and 3, latest worker 3 | Reject missing attempt 2 rather than silently omitting it |
| HI-10 | Snapshots 1, 2; authoritative worker indicates 1 | Reject stale/future snapshot 2 |
| HI-11 | Snapshots exist but `worker.json` does not | Reject missing authoritative latest worker |
| HI-12 | Snapshot 1 and latest worker 1 differ only in `taskSha256`, `model`, `logSha256`, `command`, or `preflight` | Reject mismatch in **each** field, even if public fields match |
| HI-13 | Historical snapshot contains extra log/command/model transcript fields | Do not copy raw logs, commands, or model output into `workerAttempts` |
| HI-14 | A summary was emitted with snapshot digest, then snapshot bytes change | Recalculating SHA-256 of the file no longer matches the published digest |

The independent oracle intentionally mocks only `update_status()` to isolate
handoff rendering and evidence collection; canonical pipeline tests remain
responsible for real Git/worktree verification, Gitleaks and reviewer gates.
An oracle PASS alone is **not** developer acceptance. A real run must still
complete required checks and independent review and receive explicit MAIN_MODEL
and developer checkpoints.


## A4.2a — run evidence aggregation pilot (advisory)

Requirements: AP2, AP10, AP13 and AP14. This is a bounded, read-only
**single-run evidence report** slice. Structured implementation-plan deviations,
per-sub-spec aggregation, notification delivery, and A5 stage promotion remain
separate future work. The active specification and Stage Task Packet are normative.

### Proposed approach

- Add a pure `summarize_run_evidence(run_dir: Path) -> dict[str, Any]` helper in
  `tools/agent-pipeline/agent_pipeline.py`. It reads persisted JSON evidence; it
  must not invoke Git, network, policy changes, `update_status()`, reviewer or
  worker execution, or modify any evidence file.
- Reuse the existing `collect_worker_attempts()` for validated, numeric-ordered
  worker history. Validate that every worker attempt's task digest matches the
  manifest. Do not equate check attempt numbers with worker attempt numbers.
- Include `schemaVersion: 1`, `runId`, `stage`, `baseCommit`, `taskSha256`,
  `policySha256`, authoritative `state`, `workerAttempts`, bounded `checks`,
  a review-presence/verdict summary, `evidenceDigests`, and
  `requiresExternalAcceptance: true`. The digest map uses the exact filenames
  for **existing** `manifest.json`, `worker.json`, `checks.json`,
  `review.json`, `status.json` only. Do not invent absent evidence.
- `checks` must be deterministic, with each persisted name, status, required
  flag and attempt number; do not copy the command array or check logs.
  Reject conflicts between latest `checks.json` records and `status.json`
  check summaries. `READY_FOR_MAIN_REVIEW` must fail closed unless all
  manifest-required checks and independent review explicitly passed.
- For terminal `BLOCKED`/`ESCALATED` or pre-worker states, preserve honest
  missing evidence: absent reviewer is `{present: false, verdict: null}`;
  missing required checks never become PASS by default. No `accepted`,
  `approved`, or implicit merge/archival status may appear.
- Validate schema, run/stage identity, JSON object types, integer attempts
  without treating `bool` as integer, and SHA-256 integrity of referenced
  current evidence files. This report is a snapshot index, **not** a historical
  tamper-proof trust anchor. Avoid exposing raw model logs, check commands,
  preflight records, authentication material, or reviewer raw responses.
- Keep the existing `write_handoff()` and lifecycle behavior unchanged in this
  slice. The report can be integrated into a later A4.2b handoff once
  its contract and full source-bound checks have been accepted.

### Independent scenarios (EA-01…EA-12)

| ID | Fixture/action | Required observation |
|---|---|---|
| EA-01 | Blocked run with one worker and failing canonical check | Report is produced with correct run/base/blocked state and required checks; never claims acceptance |
| EA-02 | Ready run with Gitleaks, canonical and independent review PASS | Exact check/reviewer summaries, still requires external acceptance |
| EA-03 | Blocked run without `review.json` | Review is explicitly absent, not an implied PASS; no invented review digest |
| EA-04 | Snapshots 1, 2, 3 and authoritative latest worker 3 | Three numeric-ordered `workerAttempts` with digest-linked snapshots |
| EA-05 | Pre-worker run with no `worker.json` or snapshots | Empty worker history and no invented worker digest |
| EA-06 | Malformed or array-shaped required JSON | Fail closed rather than returning a success-shaped report |
| EA-07 | Latest worker conflicts with its snapshot | Fail closed, using the existing worker evidence validator |
| EA-08 | Worker or historical attempt `taskSha256` disagrees with manifest | Reject source-unbound evidence |
| EA-09 | Latest check record disagrees with `status.json.checks` | Reject inconsistent status/check evidence |
| EA-10 | READY state lacks a required PASS check or independent PASS review | Reject unsupported readiness |
| EA-11 | Evidence contains command, raw log and preflight/transcript sentinel strings | None of those raw fields/values appear in summary |
| EA-12 | One existing evidence file changes bytes after report creation | Published `evidenceDigests` no longer matches file; report itself does not imply historical immutability |

The independent oracle at `tools/agent-pipeline/benchmarks/run_evidence_oracle.py`
checks the public `summarize_run_evidence` helper with deterministic temporary
fixtures. It is non-editable to the worker but can be visible via repository
context; **oracle PASS is not an unseen benchmark or developer acceptance**.
The full canonical gate and independent reviewer remain mandatory.
