
---
type: Specification
title: Development agent pipeline
description: Active contributor-tooling specification for bounded coding-worker execution, fail-closed verification evidence, independent review, escalation, and externally authorized acceptance lifecycle automation.
document_role: subspec
spec_status: active
parent: ../spec-signal-harvester-platform.md
---
# Development agent pipeline

## Status

Active contributor-tooling specification.

Stage A1, the repository-local pipeline foundation under
`tools/agent-pipeline/`, is accepted after canonical developer verification.
The separate product Stage 6E6 remains paused at its verified recovery and
cleanup boundaries.

**Implemented but not yet fully accepted:**

- **A2.1–A2.3:** isolated Aider execution, pinned Gitleaks plus canonical
  verification, and fresh-context reviewer/handoff integration.
- **A3:** no-diff protection, manual bounded repair, and the opt-in
  check/review/retry controller with per-attempt evidence.
- **A4.1:** bounded JSON Stage Task Packets, read-only context digest binding,
  and editable-path enforcement.
- **A4.2a:** read-only single-run evidence aggregation with explicit `BLOCKED`
  semantics. A live DeepSeek pilot passed Gitleaks, canonical checks, and
  EA-01…EA-12, but independent reviewer execution failed, so the run remained
  `BLOCKED`. The subsequent `MAIN_MODEL` recovery patch passed developer
  `./run_checks.sh`, the pipeline regressions, and the independent oracle;
  its reviewer/acceptance checkpoint remains separate.

- **A4.2b:** structured worker deviation collection passed developer recovery
  verification (100/100 Agent Pipeline tests and 26/26 independent oracle),
  but the live pilot remains `BLOCKED` because its independent reviewer returned
  `INVALID_REVIEW_SCHEMA`. Reviewer JSON-contract hardening and a synthetic
  provider smoke probe are verification-pending under A2.3; neither substitutes
  for a source-bound reviewer verdict or developer acceptance.

**A4.3 (verification-pending):** read-only aggregation of explicitly selected
stage runs with an explicit expected stage roster, strict source-packet binding,
visible missing stages, preserved blocked history, and no acceptance authority.

**A4.4 (verification-pending):** opt-in private preservation of complete
selected source-bound run evidence outside `build/`, with a digest inventory,
portable A4.3 replay and separate compact external-review summary. No
promotion/commit proof is generated in A4.4; the complete promoted-stage
checkpoint must await A5 evidence.

**A5.1 (verification-pending):** explicit, developer-authorized LOW-risk
stage promotion uses the verified isolated worker bytes, revalidates check and
review evidence, and updates only an un-checked-out `feature/*` ref with
compare-and-swap. A promotion intent/receipt records the selected commit but
does not constitute sub-spec acceptance. Automatic multi-stage scheduling,
notification, merge, and archival remain future work.

**A5.2 (verification-pending):** explicit, one-step preparation of the next
LOW-risk stage validates the predecessor promotion receipt/check/review,
ordered stage roster and exact feature-branch HEAD. It does not execute
workers or promote subsequent stages and is not autonomous scheduling.

**A5.3a (verification-pending):** explicit checkpoint-driven coordination
of one approved LOW-risk stage within a bounded ordered roster. Read-only
preflight is the default; separately authorized execution records an intent,
runs the existing A3 cycle once and stops at promotion or escalation. A5.1
CAS promotion and A5.2 next-stage preparation stay distinct human checkpoints.

**A5.3b (live pilot evidence; final acceptance not inferred):** the first-stage smoke pilot completed a two-attempt worker cycle with source-bound reviewer PASS and canonical checks. The repaired evidence summary passed with the digest-validation hotfix. Reviewed CLI regression scenarios are present in the source snapshot; recorded evidence and Git integration alone do not authorize final developer acceptance.

**Still planned:** unattended multi-stage scheduling/batching beyond explicit
checkpoints, notifications (A6), and overall pipeline v1 acceptance (A7).
Historical failed pilots, including
Aider edit-format/no-diff failures, remain evidence rather than acceptance.

This specification does not replace the product implementation focus in
`backend-observability-intelligence.md`. Product behavior is governed by
its own specification, contracts, tests, and current-state documentation.

## Goal

Reduce routine manual coordination and human-factor omissions in AI-assisted development while increasing reproducibility and reviewability.

The target pipeline should make required steps explicit and fail closed when evidence is missing. Coding
workers may implement bounded tasks and perform routine verification, but repository rules and
externally authorized acceptance remain authoritative.

## Current foundation

Accepted Stage A1 provides:

- bounded worker task packets;
- repository/spec preflight and clean-base checks;
- immutable task/policy digests;
- machine-readable run manifests and command evidence;
- policy-defined canonical verification;
- structured independent-review ingestion;
- blocking and escalation rules;
- conservative changed-path risk hints;
- a mandatory main-model checkpoint before acceptance.

Current behavior and commands are owned by
[`../../../../tools/agent-pipeline/README.md`](../../../../tools/agent-pipeline/README.md). This
specification owns intended multi-stage evolution and acceptance criteria, not the current command
reference.

## Target operating model (planned, not yet implemented)

The unit of autonomous work is **one implementation stage of an active
sub-spec**, not an open-ended instruction to finish a feature.

The developer and `MAIN_MODEL` establish architecture, requirements, ownership,
and acceptance conditions. Aider is a replaceable coding worker. The
repository-owned pipeline controls context, Git state, checks, retries,
evidence, and stop conditions. **Only the developer may accept a stage or
sub-spec.**

### Model roles and authority

The roles `MAIN_MODEL`, `WORKER_MODEL`, `REVIEWER_MODEL`, `ORCHESTRATOR`, and `DEVELOPER` are defined in the root `AGENTS.md`. They are workflow responsibilities, not fixed model products.

`MAIN_MODEL` and the developer establish requirements, known ownership, and architectural direction.
`WORKER_MODEL` implements approved bounded stages through Aider, guided but not dictated by the advisory
plan. `REVIEWER_MODEL` independently checks each attempt. `ORCHESTRATOR` enforces policy and evidence.
Only the developer initiates the final `MAIN_MODEL` review and decides acceptance.

### Delegation classification and risk

Every proposed stage must be classified before worker execution:

- **Bounded / known ownership:** the requirement, existing feature IDs, owning modules, public
  contracts, editable scope, and verification expectations are identifiable from the active spec and
  repository. It may be delegated after preflight and risk authorization.
- **Discovery / architecture required:** ownership, feature vocabulary, integration boundaries, or
  intended behavior is unresolved. It must remain human-led until the developer and main model resolve
  the decisions and update the active spec. The worker must not invent ownership or a new feature ID.

Classification and **risk** are independent. A known-owner stage may still be high-risk because it
affects published contracts, database migrations, transaction boundaries, concurrency, security,
infrastructure, or policy. Such a stage must receive the configured explicit human/main-model
authorization; a known module or feature ID alone does not grant autonomy. The pipeline must re-evaluate
risk against actual changed paths and reviewer findings, not just the initial declaration.

### Sub-spec module impact, advisory implementation plan, and stage contract

Each newly authored staged sub-spec must identify known affected owning modules and relevant external
public contracts/APIs in its normative `Implementation scope` or `Delegation plan` section. It must
explicitly mark uncertain ownership for human-led discovery rather than authorize invented modules or
Feature IDs. A sub-spec **may** include a concise `Delegation plan` section when bounded ownership is
known. The map should apply to all its bounded stages by default and avoid duplicating module
documentation. Stage-specific differences should be recorded only where necessary. The plan identifies:

- stable feature IDs from `docs/FEATURES.md`, or an explicit human-led discovery status when none is yet defined;
- owning modules and relevant local `AGENTS.md` files;
- required read-only context: root `README.md`/`AGENTS.md`, active specification and parent, owning module READMEs/contracts/tests, and only the public contracts or APIs required from other modules;
- permitted editable paths, including necessary tests and owning documentation, and prohibited paths/boundaries;
- required focused checks, canonical verification, applicable static-analysis/rare checks, and stage-specific evidence;
- the stage sequence, requirement IDs, completion criteria, risk classification, and any deviations from the default map.

This is specification content, not additional YAML frontmatter or a new feature-ID registry. If a
sub-spec has no reliable map, its stages default to human-led. A map must never grant access to other
modules' private implementation by omission or override their contracts. Relevant context should be
resolved from the **current** repository for each stage; a generated file list is a starting point, not
proof that every required document was read.

`MAIN_MODEL` must prepare a same-directory
`<subspec-stem>-implementation-plan.md` companion for each newly authored
staged sub-spec. Its design alternatives, file-by-file implementation steps,
reference algorithms/code and test scenarios are **advisory**. The intended
depth and formatting are defined once in `docs/specs/README.md`.

The companion cannot introduce new requirements, grant editable paths or act
as a separate active specification. Discovery stages must record unresolved
questions rather than invent designs. Material changes to ownership or
assumptions require developer/`MAIN_MODEL` reconciliation before delegation.

The `MAIN_MODEL` and developer prepare an explicit **stage task packet** from the approved sub-spec. It
must bind the spec/stage/requirement IDs, allowed scope, base revision, context manifest, risk
authorization, check plan, and completion conditions. When a companion plan exists, the packet must
identify and digest its exact version alongside the normative sub-spec version. The plan is supplied
read-only to the worker, never as editable scope or an authorization override. The pipeline must
validate and digest this packet and its policy before starting a model. It must not execute a
model-generated shell script or silently reinterpret natural-language instructions as repository
authorization. A universal runner should consume validated packets rather than requiring a custom script
per sub-spec.

Aider receives the **requirement and constraints** plus advisory implementation guidance, not a
prewritten patch or line-by-line replacement. The worker may deviate from the proposed approach inside
the approved scope, but must report material deviations with a rationale, affected
files/contracts/tests, and risk impact. Existing local implementation/tests within the owning module may
be read as needed. Other modules should be represented by their published APIs/contracts except when an
explicitly approved cross-module stage requires more. Worker context expansion or changed paths outside
authorization must stop for re-planning rather than being accepted as a convenient implementation
detail. Prompt scope is not a filesystem security boundary; the orchestrator must enforce changed-path
and policy boundaries independently.

### Per-stage execution and bounded repair

Once a stage has a clean, immutable base and approved packet, the planned controller must:

1. Prepare an isolated worker worktree and load the minimal required context, checking applicable repository and module instructions.
2. Run a bounded Aider implementation attempt with no worker auto-commit or unsupervised shell/credential/deployment access.
3. Validate the actual diff, untracked files, allowed paths, worker exit state, policy integrity, and
  source digest. A successful model response or exit code with no implementation diff must **not** count
  as a completed implementation stage. Explicit already-satisfied/no-op results require external stage
  resolution rather than silent progression.
4. Run stage-specific focused tests, Gitleaks, canonical `./run_checks.sh`, and applicable configured
  lint/static-analysis/rare checks. Use repository-owned commands and record exit codes, logs, versions,
  and source binding. Never weaken or skip required checks to make a failure pass.
5. Only after the required checks pass, run independent review with fresh context and record structured findings with stable identifiers and severity/category.
6. If checks or review expose a **repairable, bounded code defect**, produce a new worker attempt with
  the failed commands, actionable redacted diagnostic summary, and open findings. Revalidate the diff,
  rerun all required checks against the new source state, and request a **new independent review**. Do
  not reuse a previous PASS after source changes.
7. After **one initial implementation plus at most three repair attempts**, stop and escalate unresolved
  failures. Also stop immediately for unauthorized scope changes, high-risk/ambiguous design decisions,
  secret exposure, non-deterministic failures requiring interpretation, external-environment outages, or
  integrity violations. Environment retries, when explicitly permitted, must not be treated as a reason
  for the coding model to change product code.
8. Preserve attempt-by-attempt evidence even after a later success; do not rewrite failed attempts. An interrupted or incomplete stage must resume only from a verified safe state or require developer intervention.

A successful **stage verification** does not itself mean **sub-spec acceptance**. No worker may close a specification, modify acceptance rules, or mark its own review as authoritative.

### Developer-led recovery after a terminal stage

A terminal `BLOCKED` or `ESCALATED` stage must preserve the failed worker,
check and review evidence, and must not be resumed with the same single-use
`run-cycle` identifier. The developer may supply an updated repository snapshot
and redacted review archive for a full `MAIN_MODEL` code, test and architecture
review. `MAIN_MODEL` may produce a corrected source patch under the established
repository and scope rules, but must distinguish worker faults from
orchestrator/reviewer failures and cannot grant acceptance. The corrected
source state must receive new applicable checks and independent review before
stage progression; prior PASS evidence is never inherited. Detailed
repository workflow is owned by `tools/agent-pipeline/README.md`.

### Feature-branch progression and checkpoints

The target is one developer-approved `feature/<topic>` branch for a sub-spec. Scratch `agent/<run-id>`
branches/worktrees isolate individual attempts; they are not the final destination of accepted stage
commits. Aider retains `--no-auto-commits`. After a stage passes all required checks/review and any
mandatory escalation checkpoint, the orchestrator may promote its exact verified source state as **one
traceable stage commit on the feature branch**. Promotion must verify the expected parent commit, policy
and source digests, and a clean serialized branch transition. A stale base, conflict, or changed
verification input blocks promotion rather than triggering silent rebase or merge.

The next stage uses the newly promoted feature-branch HEAD and its own immutable task packet, checks,
and reviewer. Automatic cross-stage progression is allowed only for approved bounded stages under the
configured autonomy policy and must stop at any human-led or high-risk stage. No autonomous merge into
the default branch is authorized.

After the final stage, the pipeline should assemble a **sub-spec handoff**
with the evidence needed for an external decision:

- requirement and stage coverage, plus feature IDs;
- exact sub-spec and advisory-plan identities and digests;
- base/final commits and per-stage commits;
- focused, canonical, security, and static-analysis results;
- independent-review findings, remaining risks, deviation reports and repair history.

It should notify the developer on both terminal failure/escalation and
readiness for final architectural review. Notification transport must be
configured explicitly and must not leak secrets or raw findings.

The developer initiates `MAIN_MODEL` verification using current repository
sources and bound evidence. Its recommendation is not acceptance. Only
explicit developer acceptance permits synchronized current-state documentation
and the **paired move** of the sub-spec and its plan to the archive.

### Bounded A4.3 sub-spec evidence aggregation

A4.3 introduces a read-only, in-memory summary over *explicitly supplied* run
IDs and ordered expected stage IDs. Selection order is not certified temporal
order, stage promotion, or a complete sub-spec implementation plan. Each run
must carry an A4.1 source-bound stage packet, with consistent normative
specification and advisory companion identities, validated packet bytes,
per-run evidence digests, checks and independent reviewer state. The summary
must expose missing expected stages, multiple attempts including failures,
revision changes of the spec and plan, and explicit absent review/deviation
claims. Malformed, conflicting, cross-spec or symlink evidence must fail
closed. Snapshot digests must not be described as historical immutability.

No emitted summary may assert developer acceptance, authorize commits, schedule
stages, merge branches, archive documents, or turn worker-reported deviations
into verified facts. The A4.3 reader may be exposed as an explicit CLI command,
but must not change single-run `handoff.json` behavior, reviewer policy,
security checks, A4.2b report semantics, or A5 progression. Full sub-spec
handoff and final decision remain separate gates.

### Bounded A4.4 private evidence preservation and external handoff

The selected A4.1 stage runs must be source-bound and pass A4.3 fail-closed
integrity validation before export. The opt-in custodian creates an owner-only
private evidence directory **outside the repository/build tree**, including
unmodified stage packets, manifests, worker/check/reviewer snapshots,
repair/deviation evidence and logs. The directory must be complete enough to
re-run the A4.3 summary after the original ignored `build/` tree is removed.
Include a current-byte SHA-256 inventory of each copied file and verify the
copy before publication; refuse existing destinations, symlinks, nonregular
entries, unbounded data and source changes during copying. Digest equality
is an integrity snapshot, not proof of historical immutability.

A separate compact handoff index may contain stage/requirement/feature
coverage, observed source/plan digests and base commits, checks, worker repair
history, reviewer verdict metadata, deviation claims, missing stages and
blocking states. It must exclude raw commands, check logs and worker transcripts.
The private raw directory is sensitive and **must not** be presented as a
redacted review artifact. Distinguish unrecorded promoted/final commits from
successful stage states: A5 will supply future branch-promotion provenance.
Never infer external acceptance, automatically archive specifications, commit
branches, merge, notify or call a provider. Preserving `BLOCKED` history must
not upgrade it to `READY_FOR_MAIN_REVIEW`.


## Requirements

### AP1 — repository authority

The pipeline must treat the current repository as source of truth. Worker prompts, prior chat context,
generated handoff files, and model output must not override `AGENTS.md`, active specifications,
contracts, tests, or owning documentation.

### AP2 — evidence before progression

Required transitions must depend on machine-readable evidence rather than a worker assertion that a step was completed. Missing, failed, stale, or internally inconsistent required evidence must block progression.

### AP3 — separate execution from acceptance

A coding worker must not accept its own implementation, weaken a required check, or archive an active specification on its own authority. Acceptance and archival must require the configured external checkpoint.

### AP4 — isolated worker execution

When direct worker execution is introduced, each run must use an explicit base revision and isolated Git branch/worktree so unrelated local state cannot silently enter the worker result.

### AP5 — independent review and bounded repair

Implementation and independent review should use separate model contexts. Routine deterministic failures
and review findings may enter bounded repair/re-review loops, but architecture, security, contract,
persistence, transaction, concurrency, migration, non-determinism, and specification ambiguity must
remain escalation boundaries according to repository policy.

### AP6 — repository-owned quality checks

Security/static-analysis tools introduced into the pipeline must have explicit repository-owned configuration and evidence. A worker must not silently install, disable, weaken, or reinterpret such checks to obtain a passing result.

### AP7 — lifecycle consistency

Later lifecycle automation must keep active-spec location, lifecycle status, umbrella/index navigation,
implementation-task state, CHANGELOG, and owning current-state documentation consistent. A partially
completed archival or contradictory lifecycle state must fail closed.

### AP8 — bounded autonomy

Risk-based batching may reduce main-model checkpoints only after repeated pilot evidence supports it.
High-risk changes and configured escalation findings must always force the required main-model
checkpoint regardless of nominal batch size.

### AP9 — explicit stage delegation and context authority

The pipeline must fail closed for autonomous stage execution unless an approved sub-spec/stage packet
identifies stable requirements and feature IDs, ownership, authorized editable paths, required read-only
context, checks, and completion conditions. Unknown ownership must trigger human-led planning. A
read-only prompt instruction alone must never be treated as enforcement of edit scope.

### AP10 — source-bound per-attempt verification

Every implementation or repair attempt must preserve its own source digest, diff, test/check outputs,
and reviewer identity. Each worker-attempt task digest must bind the exact task supplied to that
attempt: the manifest-bound initial `task.md`, or the existing bounded `repair-task-N.md`
that preserves the original task and adds narrowly scoped repair feedback. Evidence summarization
must reject missing, mismatched, or unbound repair-task bytes instead of requiring all repair
attempts to repeat the initial task digest. Any source change invalidates prior PASS evidence. Empty diffs, unexpected
commits, unauthorized changed paths, or missing review evidence must not advance an implementation
stage.

### AP11 — bounded repair and stop conditions

The controller must cap automated work at one initial attempt plus three repair attempts per stage. It
must distinguish actionable deterministic implementation defects from infrastructure failures,
model/provider errors, policy violations, and decisions requiring human authority. It must report an
explicit terminal reason rather than continuing retries indefinitely.

### AP12 — guarded feature-branch stage commits

Before recording a stage commit on the approved feature branch, the controller must verify base
ancestry, unchanged source/policy evidence, successful applicable checks, independent review, and
required checkpoint authorization. One stage's commit must become the next stage's exact base; conflicts
and stale branches require external resolution. Stage commits are not sub-spec acceptance and must not
merge into the default branch.

#### A5.2 bounded serialized stage preparation

An opt-in next-stage initialization must validate the previous stage's
source-bound promotion receipt and intent, independent reviewer/check evidence,
the exact `feature/*` branch HEAD and its recorded parent commit. The developer
must explicitly supply an ordered, duplicate-free stage roster and the next
approved LOW-risk Stage Task Packet with a separate opt-in approval flag. The controller must reject skipped stages,
missing or altered predecessor evidence, foreign spec/plan identities, stale
or dirty checkouts, and reused run IDs. It may initialize the next immutable
`PREPARED` run at the promoted HEAD, with a digest reference to the predecessor
promotion; it must not automatically execute the worker, authorize later
stages, merge, accept, or archive a sub-spec. If prior run evidence was moved
to private A4.4 custody, the workflow must permit an explicitly selected
absolute predecessor directory. Current-byte digests alone are not external
historical attestations.

#### A5.3a checkpoint-driven bounded sequence execution

An opt-in sequence driver must accept an explicit ordered stage roster, exact
`feature/*` branch and one already initialized, immutable Stage Task Packet.
It must revalidate current Git HEAD, policy/task/context digests, the LOW-risk
BOUNDED packet and, for a later stage, the predecessor promotion intent/receipt,
reviewer/check evidence and committed ancestry. A missing predecessor, skipped
stage, changed source or dirty/wrong checkout must stop before worker execution.

Inspection must be read-only. Execution requires a separate developer flag and
may drive **one** existing worker/check/review/repair cycle only. It must write
an intent before side effects and preserve that intent if interrupted; it must
not restart a consumed run identifier. Successful review stops at an explicit
human stage-promotion checkpoint, and failure stops for developer escalation.
The driver must not checkout, promote, merge, automatically schedule a later
packet, grant final acceptance or archive specifications. A5.1's CAS-only ref
update and A5.2's explicit HEAD checkout prevent safe unattended crossing of
stage boundaries under the current contract. Automatic batching is future A6
work and requires additional authority and recovery controls.

#### A5.3b — source-bound first-stage smoke pilot (developer review required)

Before authorizing future unattended batching under A6, a developer-approved
A5.3b pilot must exercise `drive-stage` on a **new first-stage** LOW-risk,
BOUNDED packet and record its exact checked-out `feature/*` HEAD. The pilot's
bounded worker task may add deterministic **test-only** regression coverage for
repeated read-only CLI preflight, first-stage roster identity, and rejection of
stale/dirty Git state. It must not expand authority to edit the controller,
policy, scanner, specifications, acceptance rules, or Git promotion logic.
The developer must review and approve the packet before initializing a run.

Preflight must remain read-only and must not invoke the worker, reviewer or
checks. Real execution requires a separate explicit developer decision, must
consume the unique run ID once, and must end at the A5.3a promotion/escalation
checkpoint. The pilot must retain complete source-bound evidence and the
independent review result; missing or failed provider evidence remains
`BLOCKED`, never accepted. Preserve evidence outside `build/` before cleanup.
No second stage, automatic checkout, promotion, retry, merge or scheduling is
authorized by this pilot. An A6 batching design still requires explicit Git
checkout/recovery, concurrency and cost/risk authorization.

### AP13 — sub-spec handoff and notifications

A terminal run must expose compact, source-linked evidence across stages and actionable notifications on
completion or escalation. The developer and main model must review the complete sub-spec before
acceptance. Automated archival and current-state documentation updates require an explicit external
acceptance decision.

### AP14 — distinct model roles and advisory plan authority

`MAIN_MODEL`, `WORKER_MODEL`, `REVIEWER_MODEL`, `ORCHESTRATOR`, and `DEVELOPER` must have the distinct
responsibilities defined above. Newly authored staged sub-specs must include known module impact and a
same-directory advisory implementation plan. The plan must map existing stage/requirement IDs to useful
architectural guidance and candidate files without becoming a second acceptance contract. A worker's
justified internal design alternative may be accepted within the unchanged authorized scope, but
material deviations must be recorded; unknown or expanded ownership requires human-led re-planning.

### AP15 — developer-initiated final verification and paired archival

The developer must explicitly request a final `MAIN_MODEL` sub-spec review through ChatGPT using current
repository, stage/check/reviewer evidence, and any advisory plan deviations. No PASS status or
main-model recommendation alone authorizes acceptance. After explicit developer acceptance, lifecycle
updates must move the sub-spec and its companion together to the archive, synchronize current-state
owners and navigation, and fail closed on a split pair or inconsistent state. Legacy sub-specs without a
companion are exempt from retroactive creation.

### AP16 — configurable worker and independent reviewer adapters

`WORKER_MODEL` and `REVIEWER_MODEL` are roles independent of executor/vendor. The developer-reviewed `pipeline-policy.json` MUST select `workerExecution.defaultModel` and `independentReview.adapter.model` independently; it MUST bind reviewer endpoint and credential variable to an approved exact HTTPS URL. Missing or unsupported adapters, URLs, model configuration, redirect responses, unsupported reviewer JSON completion or credentials MUST fail closed. The only coding executor implemented is Aider; alternative coding executors require their own reviewed adapters with equivalent isolation, Git/source, scope, preflight, verification, evidence and adversarial conformance tests before enablement. OpenRouter reviewer configuration is an optional transport experiment, not a coding executor or implicit acceptance. The repo-local Aider entry point is `tools/agent-pipeline/run_aider_stage.sh`; it MUST use existing execution/approval boundaries unchanged.

### AP17 — validated processing-state register

`docs/specs/STATE.json` MUST be the single repository-owned current processing-state register and validate under `docs/specs/state.schema.json` plus semantic checks in `tools/specs/validate_state.py`. It MUST reference existing active specification and stage IDs, reject duplicate identifiers, unknown fields, missing/stale paths, malformed types and undeclared concurrency; it MUST be checked in the canonical gate. README and umbrella `current_focus` are informational links only. `STATE.json` MUST NOT substitute for run evidence, explicit stage approval, Git CAS, developer acceptance or spec archival. In this slice only `SINGLE` mode is allowed. The array-based format anticipates per-subspec workstreams, but concurrent stage writers, locks and any `MULTI` mode require later separate authority and tests.

## Implementation stages

- [x] **A1 — foundation:** bounded task/evidence state, required canonical check, structured independent review, conservative escalation, and mandatory main-model checkpoint.
- [ ] **P1 — real-stage pilot (paused product run, evidence collected):** Stage 6E6 exposed real
  workflow friction across live environment readiness, deployment provenance, REST-boundary coverage,
  time-window-aware recovery expectations, and cleanup after partial failure. The product stage remains
  verification-pending, but these observations are sufficient to proceed with bounded pipeline
  automation. P1 closes only when its final process findings are reviewed and recorded; it does not
  imply Stage 6E6 acceptance.
- [ ] **A2 — worker execution and routine verification automation:**
  - [ ] **A2.1 — isolated one-shot worker (implemented, verification-pending):** create a
    branch/worktree from the immutable base commit, verify the repository-supported Aider version/CLI
    contract and deterministic runtime prerequisites before model access, invoke the configured coding model through the Aider adapter isolated from ambient Aider configuration,
    preserve worker evidence plus a source-state digest, reject unexpected worker commits or post-worker
    source drift, and run later checks against the worker tree.
  - [ ] **A2.2 — repository-owned security/verification checks (implemented, verification-pending):**
    pin the Gitleaks version and repository-owned configuration, scan the isolated worktree including
    uncommitted changes, record redacted evidence, reject worker bypass of scanner/canonical
    configuration, and orchestrate ordered required checks.
  - [ ] **A2.3 — review invocation and handoff (implemented, verification-pending):** invoke an
    independent fresh-context reviewer through an explicit adapter and prepare the first compact
    main-model handoff from worker/check/review evidence.
- [ ] **P2 — real-worker end-to-end pilot (in progress):** exercise a bounded
  stage through Aider, a real source diff, Gitleaks, canonical checks,
  independent review and source-bound handoff.

  Historical blockers include an Aider `SEARCH/REPLACE` no-diff pilot and a
  2026-10-15 coding pilot with incomplete `worker.model` test fixtures. That
  pilot's classifier also misread simulated `Docker connection refused` output
  as an infrastructure failure. These remain failed-run evidence, not P2
  acceptance. The subsequent A4.2a pilot passed all code/check/oracle gates
  but failed independent reviewer execution. End-to-end P2 acceptance remains
  open until real reviewer and handoff evidence exist.
- [ ] **A3 — source-bound repair and re-review (implemented, verification-pending):** intrinsic no-diff
  blocking, source-bound manual `repair` with three-attempt ceiling, preserved attempt evidence, and
  opt-in `run-cycle` with fail-closed deterministic check-failure classification and fresh checks/review
  are implemented. Still required for acceptance: successful real Aider/Gitleaks/reviewer P2 evidence
  and A4 stage-level scope enforcement before any sub-spec autonomy. No stage is accepted on exit code
  alone.
- [ ] **A4 — delegation, context and structured evidence
  (partially implemented, verification-pending):** A4.1 supplies validated
  packets, spec/plan/context digest binding, and source-scope enforcement.
  A4.2a adds read-only, single-run aggregation and has passed local developer
  checks after recovery; its provider-backed reviewer checkpoint remains open.
  A4.2b structured deviations passed developer recovery verification, but
  independent provider review is still pending; multi-stage/sub-spec aggregation
  remains planned. Normative Markdown map extraction, explicit developer-approval
  evidence, focused/static-analysis ingestion and complete handoff remain
  future A4 work. A4 does not schedule or commit stages or accept specs.
- [ ] **A5 — sequential feature-branch lifecycle (A5.1 guarded promotion, A5.2
  explicit next-stage preparation and A5.3a checkpointed driver implemented,
  verification-pending):** support one approved feature branch per sub-spec,
  guarded per-stage commits from verified isolated worktrees, exact-base next-stage execution, and
  serialized progression with explicit checkpoints. Require developer-initiated final `MAIN_MODEL` verification and explicit
  developer acceptance before any permitted current-state documentation synchronization or paired
  spec/companion archival; do not infer acceptance from commits.
- [ ] **A5.4 — configurable provider transport and strict processing state (implementation patch, developer verification pending):** remove reviewer-provider URL pinning from controller code, keep the Aider executor as the only enabled coding path, select worker/reviewer models from reviewed policy, require an explicit HTTPS reviewer allowlist without bearer-token redirects, and introduce validated `docs/specs/STATE.json`. No additional executor or parallelism is authorized by these files; developer review and canonical verification remain required.
- [ ] **A6 — evidence-backed autonomy and notifications:** permit configured batching of
  already-classified bounded, low-risk stages with explicit run/cost budgets; stop immediately at
  high-risk/unknown ownership/ambiguous stages and send actionable completion or escalation
  notifications. Never merge into the default branch automatically.
- [ ] **A7 — pipeline hardening and v1 acceptance:** verify interruption/recovery, retry ceilings,
  provider failures, stale branch promotion, source drift, unexpected/no diffs, out-of-scope edits,
  corrupted/missing evidence, reviewer failure, notification failures, and contradictory spec state
  before declaring pipeline v1 accepted.

## Pilot evidence

P1 should answer at least:

- which steps still require manual coordination;
- which required evidence is difficult to collect or interpret;
- whether a worker can follow the bounded task without broad context drift;
- whether independent review catches materially different issues from worker self-checks;
- whether the main-model handoff can be made smaller without losing decision-critical context;
- whether any pipeline rule encourages unnecessary work or obscures a repository-owned decision;
- whether a real model produces an applicable diff, and whether no-op/edit-format failures stop before checks and stage promotion;
- whether ownership and risk are clear enough to authorize the next bounded stage without architecture guesswork.

Pilot findings are development-process evidence. They must not be converted into product requirements or acceptance claims without the owning product specification.

### P1 interim observations

The Stage 6E6 patch preparation already exposes two expected A1 boundaries that P1 must verify in the developer environment:

- FULL source archives intentionally exclude `.git`, so an external archive-based patch producer cannot
  truthfully initialize an A1 run against the developer repository base revision. Authoritative A1 run
  evidence must be created in the real Git worktree until A2 owns isolated worktree preparation.
- A1 records task/check/review evidence but does not invoke the worker or reviewer. Task delivery,
  fresh-context review invocation, and the final live Kubernetes command therefore remain manual pilot
  steps and are candidates for later automation only after P1 evidence is reviewed.
- the first Stage 6E6 live attempts demonstrated why live-environment and quality evidence must remain
  separate from worker success: one run stopped correctly on NotReady Kubernetes nodes, while the next
  reached application evaluation and exposed a post-restart statistical baseline anomaly. A2/A4 should
  preserve these external run artifacts structurally instead of reducing them to a worker-reported
  PASS/FAIL.
- the subsequent Stage 6E6 rerun exposed a real REST serialization defect only after a persisted
  human-attention alert existed: deterministic persistence/policy tests were green, but
  `/api/v1/admin/operations/health/alerts` returned HTTP 500 because `HumanAttentionAlertResponse`
  lacked Micronaut Serde metadata. P1 must treat this as evidence that worker-local checks and live
  boundary verification are complementary; A4 should preserve external acceptance failures and their
  fix/retest linkage explicitly.

Additional P1 observations from the later Stage 6E6 live runs:

- deploying the Serde source fix did not initially prove that Kubernetes was executing the rebuilt artifact; deployment provenance must become explicit evidence instead of being inferred from source/test state;
- after the corrected image reached Kubernetes, the alert-history endpoint serialized successfully and
  the pre-existing alert was resolved through the normal runtime policy, proving that the live boundary
  and persisted resolution path were reachable;
- a clean two-snapshot baseline and the transient 300-row backlog behaved as expected, but immediate
  transient recovery remained `DEGRADED` because the five-minute HTTP latency window still carried a
  rolling-median/MAD anomaly after the outbox had drained; time-window-aware acceptance must be modeled
  explicitly instead of assuming instantaneous global Health recovery;
- cleanup ran after the backend port-forward context had exited, so temporary profile/source deletes
  failed with connection refused after the partial scenario failure. Resource cleanup must remain inside
  a live control channel and must itself produce evidence.

These observations do not count as Stage 6E6 acceptance. The developer intentionally paused that product
verification and authorized A2.1 so subsequent development can use the agent pipeline rather than
continue accumulating manual coordination. P1 remains an open process-evidence record until its final
workflow review is recorded.

## Non-goals

This specification does not authorize:

- a coding model to become architecture or product authority;
- automatic weakening of tests, scanners, contracts, or acceptance criteria;
- automatic dependency installation or upgrade;
- unrestricted shell, network, credential, or deployment access for a worker;
- acceptance based only on model self-review;
- automatic merging to the main branch;
- arbitrary model-authored task scripts, unbounded context expansion, or cross-module private implementation reads without explicit scope approval;
- treating stage commits, provider success, or a passing reviewer as developer acceptance;
- automatic product-spec archival before the configured external acceptance decision.

## Validation

Each implemented pipeline stage must use the smallest deterministic tests that protect its orchestration/evidence semantics and must pass the canonical repository gate when repository policy requires it.

Before A7 acceptance, validation must also include deliberate failure scenarios covering at least:

- dirty or stale base state;
- task/policy mutation after run initialization;
- failed or missing required checks;
- blocking or escalation review findings;
- worker/reviewer interruption or failure;
- corrupted or contradictory evidence;
- inconsistent specification lifecycle state;
- no-diff/malformed-edit runs and changed-path escapes;
- nonrepairable environment failures and exhausted repair budgets;
- stale feature-branch parents, mid-run source/policy drift, and cross-stage evidence reuse;
- completion/escalation notification delivery failure without losing run evidence.

A successful model response is never validation evidence by itself.
