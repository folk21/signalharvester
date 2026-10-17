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

## A5 — feature-branch progression (A5.1 verification-pending)

Requirements: AP10, AP12, AP15. A5.1 provides one manually approved, fail-closed
stage commit. Do not schedule later stages or archive the specification.

- Require an existing `feature/*` branch bound in the original stage manifest,
  its exact full parent commit, a clean developer checkout at that parent and
  an un-checked-out feature branch in all Git worktrees.
- Accept only an A4.1 source-bound bounded LOW-risk packet and the exact worker
  bytes validated by the original worker digest. All required checks and their
  logs must be present and PASS, and the independent provider attempt must be
  PASS and match the same worker digest. Re-read the current policy/task/packet
  and reject stale or conflicting evidence.
- Use an alternate Git index to compose an exact commit tree from the worker
  source without modifying either checkout. Write a private promotion intent
  before advancing the branch with an exact-old SHA Git `update-ref` CAS.
  Emit a promotion receipt only after the successful ref update. A failed or
  interrupted promotion intent requires developer-led reconciliation.
- Never checkout/reset/merge/rebase a branch as part of promotion. Developer
  explicitly checks out the promoted feature branch and initializes a fresh
  source-bound packet for the next stage; A5.2 may implement serialized
  orchestration only after independent review.

All A5 stage commits are implementation evidence, **not developer acceptance**.
Only the developer may explicitly approve final lifecycle synchronization and
paired archival after MAIN_MODEL review. High-risk and uncertain checkpoints
remain external, not an implicit `--developer-approved` permission bypass.

## A5.2 — serialized next-stage preparation (advisory)

Requirements: AP12 and AP15. Preserve A5.1's CAS-only ref mutation: after a
successful promotion, the developer explicitly checks out the feature branch,
then the controller verifies the previous receipt and prepares one new run.
Read prior evidence from an explicit absolute path, including private A4.4
custody, without reopening it for writes. Require a complete ordered stage
roster and the next LOW-risk packet. Check the previous `promotion-intent.json`
and `promotion.json` identity, reviewer source digest, check logs and the exact
Git parent/HEAD before creating any next-run files. The new manifest should
carry a predecessor receipt SHA-256; never assume a current-byte digest is a
remote notarization. Keep this command separate from `run-cycle`: the next
worker, final MAIN_MODEL review and developer acceptance remain external.

**Portability obligation:** maintain the contributor-facing
[`tools/agent-pipeline/PORTING.md`](../../../../tools/agent-pipeline/PORTING.md)
as a concrete mapping from this repository's conventions to those of a target
project. Document hard-coded spec paths, SHA-1-shaped Git IDs, provider and
security tooling, branch rules, and canonical checks. Never claim that copying
SignalHarvester's `pipeline-policy.json` into another project is sufficient.

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

## A4.2b — structured worker deviations (implementation blueprint)

**Status:** proposed advisory design, not implemented or authorized by this
change. This section expands AP2, AP9, AP10, AP13, and AP14. The active
sub-specification is the normative source; A4.2b does not add requirement IDs,
change risk authorization, or grant new editable paths.

**Boundary:** A4.2a's `summarize_run_evidence()` is available in the current
controller and has passed local developer checks. A4.2b should attach an
**explicitly reported, source-bound account of material implementation-plan
deviations** to a single run. It must not infer truth or approval from an LLM
statement. Multi-run/sub-spec aggregation, model-output capture automation,
feature-branch promotion, notifications, merge, or archival remain separate
stages.

### 1. Repository entry points and ownership

| Concern | Current source / owner | Suggested A4.2b action |
|---|---|---|
| Normative requirements | `development-agent-pipeline.md`, AP2/AP9/AP10/AP13/AP14 | Read-only; no new authorization |
| Stage and context identities | `manifest.json` / `manifest.stagePacket` | Bind only to persisted identifiers |
| Worker attempt identities | `worker.json`, `worker-attempt-N.json` | Reuse `collect_worker_attempts()`; compare authoritative digests |
| Aggregate report | `summarize_run_evidence(run_dir)` | Add one optional, clearly non-authoritative deviations summary |
| Published external checkpoint | `write_handoff()` | Defer wiring until read-only contract is independently verified |
| Runtime and checks | `run_automated_cycle()`, reviewer, Gitleaks, policy | Do not change in this bounded slice |
| Tests | `tools/agent-pipeline/tests/test_agent_pipeline.py` | New `RunDeviationEvidenceTest` and adversarial cases |
| Independent acceptance | `tools/agent-pipeline/benchmarks/` | Candidate new read-only oracle in a preparatory patch, outside worker editable scope |

Read the live implementations before coding: `collect_worker_attempts()`,
`_worker_attempt_summary()`, `summarize_run_evidence()`, `write_handoff()`,
`read_json()` and `sha256_file()`. Also read the owning
`tools/agent-pipeline/README.md`, `docs/TESTS.md`, root instructions and stage
packet schema. Do not copy code snippets in this plan blindly; use the current
source signatures and record concrete differences.

No local `tools/agent-pipeline/AGENTS.md` is present in the referenced snapshot;
root `AGENTS.md` applies unless a newer local guide has been added.

### 2. Design choice: explicitly supplied evidence, not transcript mining

**Recommended first slice:** a pure, read-only collector over optional
`worker-deviations-attempt-N.json` files in an already persisted run directory.
These files are **controller-side evidence inputs**, not changes that the worker
may write inside its source worktree. Creating/importing them is a separate,
explicitly authorized step. Merely adding this reader does not enable Aider to
create evidence files or widen its editable path list.

Do **not** search Aider stdout/stderr for phrases such as "no deviations".
Narrative assistant replies are unstructured and can contain misleading
claims, credentials, or instructions. Future capture automation would need a
separate bounded protocol, explicit origin/provenance and negative tests.

Presence semantics are essential:

- **`UNREPORTED`** — no explicit record exists. This is *unknown*, not zero
  deviations and not evidence of compliance.
- **`REPORTED_NONE`** — a valid, source-bound record explicitly declares an
  empty list. It is still a worker claim, not independent verification.
- **`REPORTED`** — at least one bounded, source-bound deviation is recorded.
- **Invalid/conflicting record** — reject aggregation with `ValueError` rather
  than silently omitting evidence or producing an apparently clean report.

The source of a deviation report should be described as
`WORKER_MODEL_CLAIM`, **not** `REVIEWER_VERIFIED` or `DEVELOPER_ACCEPTED`.
Developer decisions and reviewer findings retain their own evidence channels.

### 3. Suggested persisted JSON shape (version 1)

**Illustrative payload only; no live producer or CLI is added in A4.2b.**
The eventual import action must verify the file is placed under the correct
run, without following symlinks, and with documented custody/provenance.

```json
{
  "schemaVersion": 1,
  "runId": "example-run-001",
  "stage": "A4",
  "attempt": 2,
  "taskSha256": "<64-character SHA-256 hex from manifest>",
  "changeSha256": "<64-character SHA-256 hex from worker attempt>",
  "reportedBy": "WORKER_MODEL_CLAIM",
  "deviations": [
    {
      "id": "DEV-001",
      "kind": "DESIGN_ALTERNATIVE",
      "requirementIds": ["AP14"],
      "summary": "Used an existing reader instead of a second parser",
      "rationale": "Avoided duplicate input validation",
      "affectedPaths": ["tools/agent-pipeline/agent_pipeline.py"],
      "testImpact": "Added a malformed-record regression test",
      "riskImpact": "NONE_IDENTIFIED"
    }
  ]
}
```

Required top-level keys should be exact. Unknown keys, malformed JSON,
arrays where objects are required, booleans as attempt numbers, duplicate
IDs, duplicate paths, and unsupported enum values should fail closed.

Suggested bounded schema limits for this initial slice (tune only through an
explicitly reviewed packet):

| Field | Suggested validation |
|---|---|
| `schemaVersion` | Exactly integer `1` (not `true`) |
| `runId`, `stage` | Non-empty strings identical to manifest; no path interpretation |
| `attempt` | Strict positive integer matching canonical filename and worker evidence |
| `taskSha256`, `changeSha256` | Lowercase 64-digit hexadecimal and identical to authoritative evidence |
| `reportedBy` | Literal `WORKER_MODEL_CLAIM`; never reviewer/developer identity |
| `deviations` | Array, at most 20 records; empty array means explicitly reported none |
| `id` | Unique `DEV-[0-9]{3}` in each attempt, ordered by numeric suffix |
| `kind` | `DESIGN_ALTERNATIVE`, `TEST_STRATEGY`, `FILE_LAYOUT`, `OTHER` |
| `requirementIds` | Unique IDs from `manifest.stagePacket.requirementIds` when a stage packet is present |
| `summary`, `rationale`, `testImpact` | Non-empty bounded strings, e.g. 1–500 characters; raw text stays out of compact output |
| `affectedPaths` | Nonempty unique normalized repository-relative paths, all within this worker attempt's changed files |
| `riskImpact` | `NONE_IDENTIFIED`, `POTENTIAL`, `REQUIRES_DECISION` |

For legacy runs without a stage packet, avoid inventing a requirement mapping:
a non-empty `requirementIds` array should require explicit known authority; a
legacy report can use an empty array and should remain clearly unverified.
Treat any claim requiring new module ownership, security changes, or expanded
scope as `REQUIRES_DECISION`; this reader **records** it but does not authorize
execution or repair. Do not make `NONE_IDENTIFIED` a trusted risk assessment.

### 4. Source binding and failure semantics

Recommended read order:

1. Read and validate `manifest.json`; reuse A4.2a's helper for independent
   identity checks where practical rather than creating a second report parser.
2. Call `collect_worker_attempts(run_dir)` to get a validated contiguous worker
   history. Preserve legacy no-worker and single-worker cases.
3. Scan only `worker-deviations-attempt-*.json`; reject filenames with leading
   zero, zero, suffixes, non-decimal attempts or unexpected symlink targets.
4. For each explicit deviation record, validate schema and compare `runId`,
   `stage`, `taskSha256`, `attempt`, and `changeSha256` with the manifest and
   corresponding worker attempt. A report for a nonexistent attempt fails.
5. Validate requirement IDs, changed paths, bounded text, enum values, and
   uniqueness. Do not trust a claim's stated risk as approved risk.
6. Return attempt-ordered compact summaries containing presence state,
   deviation IDs/categories/counts, safe risk flags, canonical filename and
   `sha256_file()` of **current** file bytes.
7. If a deviation file exists but fails any validation, raise a typed error;
   do not drop it. If no file exists, emit `UNREPORTED` and no invented digest.

A digest published now only binds the report to the file's current bytes. It
does **not** prove who authored a report, when they wrote it, or that a prior
version has not changed. Future source-bound custody or signature rules must
be designed separately; a `reportedBy` string is never authentication.

The reader must not modify `manifest.json`, `worker.json`, the deviation
records, status, review evidence or source files. It must not invoke Git,
network, Aider, reviewer or `update_status()`.

### 5. Reference algorithm (illustrative Python)

This is an architectural sketch, **not a finished diff**. `validate_payload`
and `compact_entry` are explicit design responsibilities, not existing project
functions. Use strict validation and reuse existing helpers where available.

```python
DEVIATION_FILENAME = re.compile(r"^worker-deviations-attempt-([1-9][0-9]*)\.json$")


def collect_deviation_summaries(run_dir: Path) -> list[dict[str, Any]]:
    manifest = read_json(run_dir / "manifest.json")
    workers = collect_worker_attempts(run_dir)
    by_attempt = {item["attempt"]: item for item in workers}

    files: dict[int, Path] = {}
    for candidate in sorted(run_dir.glob("worker-deviations-attempt-*.json")):
        match = DEVIATION_FILENAME.fullmatch(candidate.name)
        if match is None or candidate.is_symlink() or not candidate.is_file():
            raise ValueError("Invalid deviation evidence filename or file type")
        number = int(match.group(1))
        if number in files or number not in by_attempt:
            raise ValueError("Duplicate or orphan deviation evidence")
        files[number] = candidate

    result = []
    for number, worker in sorted(by_attempt.items()):
        path = files.get(number)
        if path is None:
            result.append({"attempt": number, "state": "UNREPORTED"})
            continue
        payload = read_json(path)
        validate_payload(payload, manifest=manifest, worker=worker,
                         file_attempt=number)  # implement strict schema checks
        entries = payload["deviations"]
        result.append({
            "attempt": number,
            "state": "REPORTED" if entries else "REPORTED_NONE",
            "source": "WORKER_MODEL_CLAIM",
            "count": len(entries),
            "entries": [compact_entry(item) for item in entries],
            "evidenceFile": path.name,
            "evidenceSha256": sha256_file(path),
        })
    return result
```

**Important integration detail:** `collect_worker_attempts()` returns a compact
summary, not full `worker.json`. It includes `taskSha256`, `changeSha256`, and
`changedFiles`, which are enough for the binding above. Validate that the
manifest's stage packet is present before enforcing its requirement ID list;
do not silently assume all runs were initialized with `init-stage`.

### 6. Compact report and privacy boundary

A4.2b may add `deviations` to the A4.2a result **additively**. Keep all
existing `summarize_run_evidence()` fields unchanged. A representative
projection (not a second persisted contract) is:

```json
{
  "deviations": [
    {
      "attempt": 1,
      "state": "UNREPORTED"
    },
    {
      "attempt": 2,
      "state": "REPORTED",
      "source": "WORKER_MODEL_CLAIM",
      "count": 1,
      "entries": [
        {"id": "DEV-001", "kind": "DESIGN_ALTERNATIVE", "riskImpact": "NONE_IDENTIFIED"}
      ],
      "evidenceFile": "worker-deviations-attempt-2.json",
      "evidenceSha256": "<SHA-256 of current file bytes>"
    }
  ]
}
```

Do not propagate `summary`, `rationale`, `testImpact`, model transcripts,
commands, environment values, raw review text, or arbitrary paths into the
compact report beyond validated `affectedPaths` if a reviewer explicitly
needs them. Prefer omitting `affectedPaths` from the public summary and
keeping them in the bound source file. Reviewers can evaluate the claimed
rationale from the source artifact via a separately authorized interface.

If stage packet or run state disagrees with record identity, fail closed.
Avoid modifying `write_handoff()` or export format until the reader tests and
canonical checks are accepted. If handoff integration is later authorized,
it must preserve legacy handoff keys and must not turn a claim into PASS.

### 7. Explicit acceptance and escalation boundaries

- `UNREPORTED` must never become `REPORTED_NONE`, including for no-op runs.
- A worker's statement that there were no deviations is not verification.
- A claimed deviation neither repairs nor weakens a requirement; a reviewer
  may still issue `CHANGES_REQUIRED` or `ESCALATE`.
- `REQUIRES_DECISION` is visible to the main-model/developer checkpoint;
  it cannot automatically authorize an expanded edit scope.
- A newly changed source state requires fresh checks and reviewer evidence;
  earlier PASS verdicts remain historical.
- No automatic commit, promotion, merge, notification, acceptance or paired
  archival is part of A4.2b.

### 8. Deterministic verification matrix (DV-01…DV-20)

The IDs below name **test cases**, not AP requirement IDs or stable product
features. Exercise the public read-only helper and, if approved, the additive
single-run aggregate projection. Tests must use complete worker fixtures and
real current-byte SHA-256, not placeholder hashes where code verifies bytes.

| ID | Fixture / mutation | Expected observable result |
|---|---|---|
| DV-01 | One valid worker, no deviation file | `UNREPORTED`, no invented evidence digest |
| DV-02 | One valid worker, explicit empty `deviations` | `REPORTED_NONE`, source `WORKER_MODEL_CLAIM`, digest present |
| DV-03 | One valid record with a bounded design alternative | `REPORTED`; safe IDs/categories, not raw narrative |
| DV-04 | Three workers, records for attempts 1 and 3 | Numeric ordering; attempt 2 is `UNREPORTED` |
| DV-05 | `worker-deviations-attempt-01.json` or attempt 0 | Reject noncanonical names |
| DV-06 | File attempt 2 but payload attempt 1, `true`, string or float | Reject every mismatch/type |
| DV-07 | Deviation file for absent worker attempt | Reject orphan evidence |
| DV-08 | Malformed JSON or JSON array | Fail closed |
| DV-09 | Mismatched `runId`, `stage` or manifest task digest | Fail closed |
| DV-10 | Mismatched `changeSha256` from a worker attempt | Fail closed |
| DV-11 | Duplicate `DEV-001` IDs or unsorted IDs | Reject rather than silently deduplicate |
| DV-12 | Unknown `kind`, `riskImpact` or extra object key | Reject unsupported schema |
| DV-13 | `affectedPaths` contains absolute, `..`, duplicate or unchanged path | Reject unsupported scope claim |
| DV-14 | `requirementIds` outside stage packet list | Reject claim of unauthorized requirement linkage |
| DV-15 | Excess records or overlong text | Reject bounded-size violation |
| DV-16 | Raw sentinel in rationale/command-like input | No raw text appears in compact output |
| DV-17 | A stored deviation file changes after summary | Old published digest no longer matches current bytes |
| DV-18 | Record exists without `worker.json` or with invalid snapshot history | Reject through worker evidence validator |
| DV-19 | Legacy worker (no stage packet) and empty requirement IDs | Explicitly reported record can be summarized without invented spec authority |
| DV-20 | Claim is `REQUIRES_DECISION` with otherwise valid source binding | Visible but never accepted or promoted |

**Additional tests:** mixed `BLOCKED` and `READY_FOR_MAIN_REVIEW` states must
retain A4.2a semantics; absence of a report cannot be treated as review PASS;
corrupt evidence must not suppress an already recorded failed check; no writes
to run files; deterministic repeated invocation; no symlink traversal.

### 9. Suggested implementation order and validation

1. **Before delegation:** developer and `MAIN_MODEL` review the suggested
   schema/custody boundary and Stage Task Packet; resolve who produces or
   imports the explicit JSON artifact. Without a trusted origin, this stage
   is limited to *reading untrusted worker claims*, not authenticated provenance.
2. Add read-only filename/schema validation and `collect_deviation_summaries()`
   as narrowly scoped private/public helpers in the current controller.
3. Add additive `deviations` projection to `summarize_run_evidence()` only if
   the Stage Task Packet explicitly includes this integration.
4. Add realistic fixtures and all DV-01…DV-20 regression scenarios under
   `tools/agent-pipeline/tests/test_agent_pipeline.py`.
5. Prepare a separate uneditable oracle and opt-in pilot **before** worker
   execution. Keep those files outside worker editable paths.
6. Run focused Python tests and the oracle first, then the canonical
   `./tools/agent-pipeline/run_tests.sh` and `./run_checks.sh` in the
   authorized developer environment. Use an isolated Git worktree for the
   worker attempt. Verify both a valid record and actual `BLOCKED` evidence.
7. Require a new independent reviewer request for the final worker source
   state, then explicit `MAIN_MODEL` review and developer acceptance.

Suggested stage packet scope: **expected** edits to
`tools/agent-pipeline/agent_pipeline.py` and
`tools/agent-pipeline/tests/test_agent_pipeline.py` only; the normative spec,
companion, benchmark oracle, policy, scanner and `run_checks.sh` remain
read-only. No runnable Stage Task Packet is created by this documentation
patch; authorize it separately after the source state and design are checked.

### 10. Alternatives, assumptions and open decisions

- **Chosen first step:** ingest explicit, untrusted per-attempt files. This is
  testable without provider access and does not infer structured data from
  Aider's free-form terminal output.
- **Deferred:** a model-native structured response channel and automatic
  `worker-deviations-attempt-N.json` producer. It needs a precise capture
  protocol and source custody rules; the existing Aider adapter does not
  guarantee such a protocol.
- **Deferred:** reviewer verification of every deviation, severity-based
  acceptance policy, and a new mandatory "all deviations acknowledged" gate.
  These would change risk/acceptance behavior and need explicit authorization.
- **Open:** choose the trusted importer/producer and its permission boundary
  before implementing any write action. The read-only collector is useful
  without that action for developer-supplied sanitized evidence.
- **Trade-off:** storing rationale in a bounded file helps auditability but
  creates a sensitive-data handling responsibility. Compact handoff output
  intentionally publishes only validated categorical metadata and digests.

No part of this blueprint authorizes unreviewed changes to
`pipeline-policy.json`, reviewer behavior, canonical gates, model provider or
repository lifecycle.

## A4.3 — explicitly selected sub-spec evidence summary (advisory)

**Normative authority:** AP2, AP9, AP10, AP13 and AP14 of
`development-agent-pipeline.md`. Do not infer acceptance from a collector.

A first bounded implementation should use `summarize_run_evidence()` as the
per-run evidence validator and add a pure `summarize_subspec_evidence()` over
explicit run directories. The caller supplies a reviewed stage roster, in
order; the collector must not parse Markdown into unapproved stage authority,
scan directories to guess runs, perform Git operations, or modify evidence.

Require A4.1 `stagePacket` metadata and digest-matching `stage-packet.json`
for every run. Reject mixed normative `specPath` or companion `planPath`,
unknown or duplicate stages, repeated run IDs, inconsistent packet identity,
malformed/currently conflicting review/check/worker/deviation evidence, and
symlinked evidence. Preserve historical blocked attempts when a later
separately selected run is ready. Carry per-run compact check/review and
source-bound worker-deviation summaries, current-byte evidence digests,
requirement/feature IDs, and stage-packet/source digests. When the same
specification or advisory plan changed bytes between runs, expose all observed
versions; do not silently normalize them to the latest bytes.

A bounded opt-in CLI can print the JSON index, without persisting it. Prefer
focused deterministic cases SE-01..SE-15 and an independent read-only oracle
for missing/duplicate stages,
blocked history, cross-spec injection, packet drift, plan mismatch, source
versions, sensitive-data omission, read-only behavior and symlink rejection.
Future A5 stage commits, autonomous sequencing, notifications and paired
archival are **not** authorized by this slice. The independent reviewer, a
source-bound run verdict and explicit developer acceptance remain separate.


## A4.4 — private run custody and compact sub-spec handoff (advisory)

**Normative authority:** A4.4 boundary and AP2/AP9/AP10/AP13/AP14 in the active
spec. A4.3 `summarize_subspec_evidence()` remains the source-bound validator.
An opt-in export should copy the exact selected run evidence, including the
stage-packet bytes, to an owner-only output directory **outside** all Gradle
`build/` paths. Use safe filename validation, bounded file counts/sizes,
reject symlinks and directories, verify the source during copying, create a
SHA-256 inventory, then recompute A4.3 from the copied bytes before publishing.
A separate compact `review-handoff.json` should omit raw LLM/check content and
explicitly retain missing/blocked stages and external acceptance requirements.

Suggested deterministic scenarios HC-01..HC-05 and HE-01..HE-09: replay after
removing the original run, safe field projection, digest tampering, missing
stages, persistent failed attempts, archive no-clobber, repository-path denial,
symlink rejection, and CLI opt-in validation. This is a *private retention*
boundary, not a provider-backed live pilot. Private `runs/` can include secrets
and code and must not be sent to ChatGPT by default. The compact index is an
evidence map; reviewers still need a separately approved code-delta source.
Future A5 is responsible for expected branch ancestry, per-stage commits and
final promoted commit evidence. A4.4 must not fabricate them. Never commit,
merge, notify, auto-accept or archive active specs from this command.
