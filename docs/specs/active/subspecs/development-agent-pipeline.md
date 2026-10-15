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

Stage A1, the repository-local pipeline foundation under `tools/agent-pipeline/`, is accepted after canonical developer verification. The real Stage 6E6 pilot produced enough workflow evidence to start A2.1, but Stage 6E6 itself remains verification-pending and is deliberately paused. The pause preserves the unresolved transient-recovery timing and cleanup findings instead of tuning product policy around the harness.

Stages A2.1, A2.2, and A2.3 are implemented and verification-pending. A2.1 runs one-shot Aider + DeepSeek in an isolated Git branch/worktree with fail-closed preflight and no worker auto-commits. A2.2 adds pinned Gitleaks worktree scanning and ordered required verification. A2.3 invokes a separate fresh-context reviewer and emits a compact main-model handoff. A3's first code slice introduces intrinsic no-diff blocking and manually triggered, at-most-three repair attempts with preserved evidence and mandatory fresh checks/review. A3's second slice adds the opt-in automatic check/review/retry cycle with conservative deterministic failure classification, terminal escalation evidence, and distinct per-worker check logs. Both A3 slices are implementation-complete but verification-pending; actual provider-backed end-to-end P2 acceptance and stage-level editable-scope enforcement remain open. Sub-spec stage scheduling, feature-branch progression, notifications, and automatic acceptance remain unimplemented. The first real documentation pilot confirmed Aider/DeepSeek execution but produced no source changes after repeated `SEARCH/REPLACE` mismatch; it stopped at the external pilot scope guard before security checks and review. It is not end-to-end acceptance evidence.

This specification does not replace the current product implementation focus in `backend-observability-intelligence.md`. Product semantics remain owned by their product specification, implementation, contracts, tests, and current-state documentation.

## Goal

Reduce routine manual coordination and human-factor omissions in AI-assisted development while increasing reproducibility and reviewability.

The target pipeline should make required steps explicit and fail closed when evidence is missing. Coding workers may implement bounded tasks and perform routine verification, but repository rules and externally authorized acceptance remain authoritative.

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

Current behavior and commands are owned by [`../../../../tools/agent-pipeline/README.md`](../../../../tools/agent-pipeline/README.md). This specification owns intended multi-stage evolution and acceptance criteria, not the current command reference.

## Target operating model (planned, not yet implemented)

The intended unit of autonomous work is **one implementation stage of an active sub-spec**, not an open-ended instruction to finish a feature. A developer and a main-model design/review session establish architecture, requirements, ownership, and acceptance conditions. Aider is a replaceable coding worker. The repository-owned pipeline is the deterministic controller of context, Git state, verification, retries, evidence, and stopping rules. The developer remains the final acceptance authority.

### Model roles and authority

The roles `MAIN_MODEL`, `WORKER_MODEL`, `REVIEWER_MODEL`, `ORCHESTRATOR`, and `DEVELOPER` are defined in the root `AGENTS.md`. They are workflow responsibilities, not fixed model products.

`MAIN_MODEL` and the developer establish requirements, known ownership, and architectural direction. `WORKER_MODEL` implements approved bounded stages through Aider, guided but not dictated by the advisory plan. `REVIEWER_MODEL` independently checks each attempt. `ORCHESTRATOR` enforces policy and evidence. Only the developer initiates the final `MAIN_MODEL` review and decides acceptance.

### Delegation classification and risk

Every proposed stage must be classified before worker execution:

- **Bounded / known ownership:** the requirement, existing feature IDs, owning modules, public contracts, editable scope, and verification expectations are identifiable from the active spec and repository. It may be delegated after preflight and risk authorization.
- **Discovery / architecture required:** ownership, feature vocabulary, integration boundaries, or intended behavior is unresolved. It must remain human-led until the developer and main model resolve the decisions and update the active spec. The worker must not invent ownership or a new feature ID.

Classification and **risk** are independent. A known-owner stage may still be high-risk because it affects published contracts, database migrations, transaction boundaries, concurrency, security, infrastructure, or policy. Such a stage must receive the configured explicit human/main-model authorization; a known module or feature ID alone does not grant autonomy. The pipeline must re-evaluate risk against actual changed paths and reviewer findings, not just the initial declaration.

### Sub-spec module impact, advisory implementation plan, and stage contract

Each newly authored staged sub-spec must identify known affected owning modules and relevant external public contracts/APIs in its normative `Implementation scope` or `Delegation plan` section. It must explicitly mark uncertain ownership for human-led discovery rather than authorize invented modules or Feature IDs. A sub-spec **may** include a concise `Delegation plan` section when bounded ownership is known. The map should apply to all its bounded stages by default and avoid duplicating module documentation. Stage-specific differences should be recorded only where necessary. The plan identifies:

- stable feature IDs from `docs/FEATURES.md`, or an explicit human-led discovery status when none is yet defined;
- owning modules and relevant local `AGENTS.md` files;
- required read-only context: root `README.md`/`AGENTS.md`, active specification and parent, owning module READMEs/contracts/tests, and only the public contracts or APIs required from other modules;
- permitted editable paths, including necessary tests and owning documentation, and prohibited paths/boundaries;
- required focused checks, canonical verification, applicable static-analysis/rare checks, and stage-specific evidence;
- the stage sequence, requirement IDs, completion criteria, risk classification, and any deviations from the default map.

This is specification content, not additional YAML frontmatter or a new feature-ID registry. If a sub-spec has no reliable map, its stages default to human-led. A map must never grant access to other modules' private implementation by omission or override their contracts. Relevant context should be resolved from the **current** repository for each stage; a generated file list is a starting point, not proof that every required document was read.

`MAIN_MODEL` must prepare a same-directory `<subspec-stem>-implementation-plan.md` companion for each newly authored staged sub-spec, as defined by `docs/specs/README.md`. Its proposed architecture, alternative approaches, per-stage implementation steps, existing/candidate files, context references, and test suggestions are **advisory**. The companion is not an independent active specification, must not introduce requirements or grant editable paths, and remains optional historical context for legacy sub-specs. For discovery stages it may contain unresolved questions rather than invented detailed code plans. If an assumption or module ownership materially changes, `MAIN_MODEL` and the developer must reconcile the normative sub-spec and advisory plan before further delegated execution.

The `MAIN_MODEL` and developer prepare an explicit **stage task packet** from the approved sub-spec. It must bind the spec/stage/requirement IDs, allowed scope, base revision, context manifest, risk authorization, check plan, and completion conditions. When a companion plan exists, the packet must identify and digest its exact version alongside the normative sub-spec version. The plan is supplied read-only to the worker, never as editable scope or an authorization override. The pipeline must validate and digest this packet and its policy before starting a model. It must not execute a model-generated shell script or silently reinterpret natural-language instructions as repository authorization. A universal runner should consume validated packets rather than requiring a custom script per sub-spec.

Aider receives the **requirement and constraints** plus advisory implementation guidance, not a prewritten patch or line-by-line replacement. The worker may deviate from the proposed approach inside the approved scope, but must report material deviations with a rationale, affected files/contracts/tests, and risk impact. Existing local implementation/tests within the owning module may be read as needed. Other modules should be represented by their published APIs/contracts except when an explicitly approved cross-module stage requires more. Worker context expansion or changed paths outside authorization must stop for re-planning rather than being accepted as a convenient implementation detail. Prompt scope is not a filesystem security boundary; the orchestrator must enforce changed-path and policy boundaries independently.

### Per-stage execution and bounded repair

Once a stage has a clean, immutable base and approved packet, the planned controller must:

1. Prepare an isolated worker worktree and load the minimal required context, checking applicable repository and module instructions.
2. Run a bounded Aider implementation attempt with no worker auto-commit or unsupervised shell/credential/deployment access.
3. Validate the actual diff, untracked files, allowed paths, worker exit state, policy integrity, and source digest. A successful model response or exit code with no implementation diff must **not** count as a completed implementation stage. Explicit already-satisfied/no-op results require external stage resolution rather than silent progression.
4. Run stage-specific focused tests, Gitleaks, canonical `./run_checks.sh`, and applicable configured lint/static-analysis/rare checks. Use repository-owned commands and record exit codes, logs, versions, and source binding. Never weaken or skip required checks to make a failure pass.
5. Only after the required checks pass, run independent review with fresh context and record structured findings with stable identifiers and severity/category.
6. If checks or review expose a **repairable, bounded code defect**, produce a new worker attempt with the failed commands, actionable redacted diagnostic summary, and open findings. Revalidate the diff, rerun all required checks against the new source state, and request a **new independent review**. Do not reuse a previous PASS after source changes.
7. After **one initial implementation plus at most three repair attempts**, stop and escalate unresolved failures. Also stop immediately for unauthorized scope changes, high-risk/ambiguous design decisions, secret exposure, non-deterministic failures requiring interpretation, external-environment outages, or integrity violations. Environment retries, when explicitly permitted, must not be treated as a reason for the coding model to change product code.
8. Preserve attempt-by-attempt evidence even after a later success; do not rewrite failed attempts. An interrupted or incomplete stage must resume only from a verified safe state or require developer intervention.

A successful **stage verification** does not itself mean **sub-spec acceptance**. No worker may close a specification, modify acceptance rules, or mark its own review as authoritative.

### Feature-branch progression and checkpoints

The target is one developer-approved `feature/<topic>` branch for a sub-spec. Scratch `agent/<run-id>` branches/worktrees isolate individual attempts; they are not the final destination of accepted stage commits. Aider retains `--no-auto-commits`. After a stage passes all required checks/review and any mandatory escalation checkpoint, the orchestrator may promote its exact verified source state as **one traceable stage commit on the feature branch**. Promotion must verify the expected parent commit, policy and source digests, and a clean serialized branch transition. A stale base, conflict, or changed verification input blocks promotion rather than triggering silent rebase or merge.

The next stage uses the newly promoted feature-branch HEAD and its own immutable task packet, checks, and reviewer. Automatic cross-stage progression is allowed only for approved bounded stages under the configured autonomy policy and must stop at any human-led or high-risk stage. No autonomous merge into the default branch is authorized.

After the final stage, the pipeline should assemble a **sub-spec handoff**: requirement/stage coverage, feature IDs, exact sub-spec and companion-plan identities/digests, base and final commit IDs, all stage commits, focused/canonical/security/static-analysis evidence, independent review findings, remaining risks, and deviations/repair history. It must notify the developer both on terminal failure/escalation and when the sub-spec is ready for a main-model architectural review and **human acceptance**. Notification transport must be explicitly configured and must not leak credentials or raw secret findings. The developer must initiate final `MAIN_MODEL` verification through ChatGPT using the current repository and handoff evidence; model review is a recommendation, not an acceptance decision. Only the developer's explicit acceptance permits lifecycle documentation synchronization and the **paired move** of the sub-spec and its implementation plan into the archive under existing repository rules.

## Requirements

### AP1 — repository authority

The pipeline must treat the current repository as source of truth. Worker prompts, prior chat context, generated handoff files, and model output must not override `AGENTS.md`, active specifications, contracts, tests, or owning documentation.

### AP2 — evidence before progression

Required transitions must depend on machine-readable evidence rather than a worker assertion that a step was completed. Missing, failed, stale, or internally inconsistent required evidence must block progression.

### AP3 — separate execution from acceptance

A coding worker must not accept its own implementation, weaken a required check, or archive an active specification on its own authority. Acceptance and archival must require the configured external checkpoint.

### AP4 — isolated worker execution

When direct worker execution is introduced, each run must use an explicit base revision and isolated Git branch/worktree so unrelated local state cannot silently enter the worker result.

### AP5 — independent review and bounded repair

Implementation and independent review should use separate model contexts. Routine deterministic failures and review findings may enter bounded repair/re-review loops, but architecture, security, contract, persistence, transaction, concurrency, migration, non-determinism, and specification ambiguity must remain escalation boundaries according to repository policy.

### AP6 — repository-owned quality checks

Security/static-analysis tools introduced into the pipeline must have explicit repository-owned configuration and evidence. A worker must not silently install, disable, weaken, or reinterpret such checks to obtain a passing result.

### AP7 — lifecycle consistency

Later lifecycle automation must keep active-spec location, lifecycle status, umbrella/index navigation, implementation-task state, CHANGELOG, and owning current-state documentation consistent. A partially completed archival or contradictory lifecycle state must fail closed.

### AP8 — bounded autonomy

Risk-based batching may reduce main-model checkpoints only after repeated pilot evidence supports it. High-risk changes and configured escalation findings must always force the required main-model checkpoint regardless of nominal batch size.

### AP9 — explicit stage delegation and context authority

The pipeline must fail closed for autonomous stage execution unless an approved sub-spec/stage packet identifies stable requirements and feature IDs, ownership, authorized editable paths, required read-only context, checks, and completion conditions. Unknown ownership must trigger human-led planning. A read-only prompt instruction alone must never be treated as enforcement of edit scope.

### AP10 — source-bound per-attempt verification

Every implementation or repair attempt must preserve its own source digest, diff, test/check outputs, and reviewer identity. Any source change invalidates prior PASS evidence. Empty diffs, unexpected commits, unauthorized changed paths, or missing review evidence must not advance an implementation stage.

### AP11 — bounded repair and stop conditions

The controller must cap automated work at one initial attempt plus three repair attempts per stage. It must distinguish actionable deterministic implementation defects from infrastructure failures, model/provider errors, policy violations, and decisions requiring human authority. It must report an explicit terminal reason rather than continuing retries indefinitely.

### AP12 — guarded feature-branch stage commits

Before recording a stage commit on the approved feature branch, the controller must verify base ancestry, unchanged source/policy evidence, successful applicable checks, independent review, and required checkpoint authorization. One stage's commit must become the next stage's exact base; conflicts and stale branches require external resolution. Stage commits are not sub-spec acceptance and must not merge into the default branch.

### AP13 — sub-spec handoff and notifications

A terminal run must expose compact, source-linked evidence across stages and actionable notifications on completion or escalation. The developer and main model must review the complete sub-spec before acceptance. Automated archival and current-state documentation updates require an explicit external acceptance decision.

### AP14 — distinct model roles and advisory plan authority

`MAIN_MODEL`, `WORKER_MODEL`, `REVIEWER_MODEL`, `ORCHESTRATOR`, and `DEVELOPER` must have the distinct responsibilities defined above. Newly authored staged sub-specs must include known module impact and a same-directory advisory implementation plan. The plan must map existing stage/requirement IDs to useful architectural guidance and candidate files without becoming a second acceptance contract. A worker's justified internal design alternative may be accepted within the unchanged authorized scope, but material deviations must be recorded; unknown or expanded ownership requires human-led re-planning.

### AP15 — developer-initiated final verification and paired archival

The developer must explicitly request a final `MAIN_MODEL` sub-spec review through ChatGPT using current repository, stage/check/reviewer evidence, and any advisory plan deviations. No PASS status or main-model recommendation alone authorizes acceptance. After explicit developer acceptance, lifecycle updates must move the sub-spec and its companion together to the archive, synchronize current-state owners and navigation, and fail closed on a split pair or inconsistent state. Legacy sub-specs without a companion are exempt from retroactive creation.

## Implementation stages

- [x] **A1 — foundation:** bounded task/evidence state, required canonical check, structured independent review, conservative escalation, and mandatory main-model checkpoint.
- [ ] **P1 — real-stage pilot (paused product run, evidence collected):** Stage 6E6 exposed real workflow friction across live environment readiness, deployment provenance, REST-boundary coverage, time-window-aware recovery expectations, and cleanup after partial failure. The product stage remains verification-pending, but these observations are sufficient to proceed with bounded pipeline automation. P1 closes only when its final process findings are reviewed and recorded; it does not imply Stage 6E6 acceptance.
- [ ] **A2 — worker execution and routine verification automation:**
  - [ ] **A2.1 — isolated one-shot worker (implemented, verification-pending):** create a branch/worktree from the immutable base commit, verify the repository-supported Aider version/CLI contract and deterministic runtime prerequisites before model access, invoke Aider + the configured DeepSeek model through a repository-owned adapter isolated from ambient Aider configuration, preserve worker evidence plus a source-state digest, reject unexpected worker commits or post-worker source drift, and run later checks against the worker tree.
  - [ ] **A2.2 — repository-owned security/verification checks (implemented, verification-pending):** pin the Gitleaks version and repository-owned configuration, scan the isolated worktree including uncommitted changes, record redacted evidence, reject worker bypass of scanner/canonical configuration, and orchestrate ordered required checks.
  - [ ] **A2.3 — review invocation and handoff (implemented, verification-pending):** invoke an independent fresh-context reviewer through an explicit adapter and prepare the first compact main-model handoff from worker/check/review evidence.
- [ ] **P2 — real-worker end-to-end pilot (in progress):** run a genuinely bounded stage through Aider, actual source changes, Gitleaks, canonical checks, independent review, and handoff. The first documentation attempt reached DeepSeek but failed exact-match edits and produced no diff; this is failure evidence, not acceptance. Verify missing-diff enforcement in the pipeline itself before claiming live completion.
- [ ] **A3 — source-bound repair and re-review (implemented, verification-pending):** intrinsic no-diff blocking, source-bound manual `repair` with three-attempt ceiling, preserved attempt evidence, and opt-in `run-cycle` with fail-closed deterministic check-failure classification and fresh checks/review are implemented. Still required for acceptance: successful real Aider/Gitleaks/reviewer P2 evidence and A4 stage-level scope enforcement before any sub-spec autonomy. No stage is accepted on exit code alone.
- [ ] **A4 — delegation plan, context resolution, structured evidence:** define validated stage task packets derived from normative sub-spec module impact/delegation maps and the advisory companion implementation plan; bind both document digests, resolve owning module instructions and contract-only cross-module context, enforce editable scope, record material worker deviations, ingest scanner/static-analysis outcomes, and emit compact stage and sub-spec handoff evidence.
- [ ] **A5 — sequential feature-branch lifecycle:** support one approved feature branch per sub-spec, guarded per-stage commits from verified isolated worktrees, exact-base next-stage execution, and serialized progression. Require developer-initiated final `MAIN_MODEL` verification and explicit developer acceptance before any permitted current-state documentation synchronization or paired spec/companion archival; do not infer acceptance from commits.
- [ ] **A6 — evidence-backed autonomy and notifications:** permit configured batching of already-classified bounded, low-risk stages with explicit run/cost budgets; stop immediately at high-risk/unknown ownership/ambiguous stages and send actionable completion or escalation notifications. Never merge into the default branch automatically.
- [ ] **A7 — pipeline hardening and v1 acceptance:** verify interruption/recovery, retry ceilings, provider failures, stale branch promotion, source drift, unexpected/no diffs, out-of-scope edits, corrupted/missing evidence, reviewer failure, notification failures, and contradictory spec state before declaring pipeline v1 accepted.

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

- FULL source archives intentionally exclude `.git`, so an external archive-based patch producer cannot truthfully initialize an A1 run against the developer repository base revision. Authoritative A1 run evidence must be created in the real Git worktree until A2 owns isolated worktree preparation.
- A1 records task/check/review evidence but does not invoke the worker or reviewer. Task delivery, fresh-context review invocation, and the final live Kubernetes command therefore remain manual pilot steps and are candidates for later automation only after P1 evidence is reviewed.
- the first Stage 6E6 live attempts demonstrated why live-environment and quality evidence must remain separate from worker success: one run stopped correctly on NotReady Kubernetes nodes, while the next reached application evaluation and exposed a post-restart statistical baseline anomaly. A2/A4 should preserve these external run artifacts structurally instead of reducing them to a worker-reported PASS/FAIL.
- the subsequent Stage 6E6 rerun exposed a real REST serialization defect only after a persisted human-attention alert existed: deterministic persistence/policy tests were green, but `/api/v1/admin/operations/health/alerts` returned HTTP 500 because `HumanAttentionAlertResponse` lacked Micronaut Serde metadata. P1 must treat this as evidence that worker-local checks and live boundary verification are complementary; A4 should preserve external acceptance failures and their fix/retest linkage explicitly.

Additional P1 observations from the later Stage 6E6 live runs:

- deploying the Serde source fix did not initially prove that Kubernetes was executing the rebuilt artifact; deployment provenance must become explicit evidence instead of being inferred from source/test state;
- after the corrected image reached Kubernetes, the alert-history endpoint serialized successfully and the pre-existing alert was resolved through the normal runtime policy, proving that the live boundary and persisted resolution path were reachable;
- a clean two-snapshot baseline and the transient 300-row backlog behaved as expected, but immediate transient recovery remained `DEGRADED` because the five-minute HTTP latency window still carried a rolling-median/MAD anomaly after the outbox had drained; time-window-aware acceptance must be modeled explicitly instead of assuming instantaneous global Health recovery;
- cleanup ran after the backend port-forward context had exited, so temporary profile/source deletes failed with connection refused after the partial scenario failure. Resource cleanup must remain inside a live control channel and must itself produce evidence.

These observations do not count as Stage 6E6 acceptance. The developer intentionally paused that product verification and authorized A2.1 so subsequent development can use the agent pipeline rather than continue accumulating manual coordination. P1 remains an open process-evidence record until its final workflow review is recorded.

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

