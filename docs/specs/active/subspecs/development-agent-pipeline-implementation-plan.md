
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
