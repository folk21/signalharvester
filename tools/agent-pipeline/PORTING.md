---
type: Tool Guide
title: Porting the development agent pipeline
description: Safe adaptation checklist for another repository.
---
# Porting the development agent pipeline to another project

This is a **migration guide**, not a claim that the SignalHarvester controller is
plug-and-play in arbitrary repositories. The implementation is Python + Git,
but it currently contains SignalHarvester conventions for specification layout,
verification, Aider, Gitleaks, DeepSeek, and evidence storage. A different
project's repository rules and executable checks must take precedence.

Use this guide when giving the SignalHarvester repository to a model working in
another project. Inspect the other project's current source before writing a
patch. Do not copy SignalHarvester product specifications, Java module layout,
checks, credentials, or provider settings into an unrelated codebase.

## 1. What the model should preserve

The reusable design is a set of **trust and lifecycle boundaries**, not a set
of directory names or a particular model vendor:

1. **Authority:** repository instructions, active requirements and contracts
   are normative; the implementation plan is advisory; a worker's proposal is
   not permission to change scope.
2. **Stage identity:** an explicitly approved task/packet binds a stage ID,
   specification identity, authorized paths, source digests, worker model and
   risk level. Replanning requires a new packet/run, not silent mutation.
3. **Git isolation:** a clean, immutable base and an isolated worker tree keep
   unverified edits separate from the developer checkout. The worker never
   auto-commits or performs an unauthorized merge.
4. **Verification:** required commands and security checks run against the
   actual worker bytes, with logs and digests; failure, unknown results and
   source drift block progression. Never substitute a model's claim for a gate.
5. **Independent review:** a fresh reviewer checks the same source digest;
   malformed/incomplete provider output fails closed. It is evidence, not
   acceptance. Avoid forwarding secrets or raw logs to unapproved endpoints.
6. **Bounded repair:** retry only classified implementation defects, under an
   explicit attempt budget. Never auto-retry provider, infrastructure, policy,
   security or architecture escalations.
7. **Promotion:** create a stage commit only from verified source bytes, with
   explicit authorization, expected parent SHA and atomic Git ref CAS. No
   automatic merge/rebase/branch reset. Preserve a repairable intent/receipt.
8. **Sequence:** a new stage must start on the exact promoted branch HEAD,
   with a new immutable packet and a checked predecessor receipt. A stage
   skipped in the declared roster, changed reviewer, missing evidence or
   altered Git ancestry must stop the workflow.
9. **Evidence custody:** preserve full run evidence in a private directory
   outside build-cleanup paths; publish separate compact sanitized summaries.
   A SHA-256 file inventory detects later changes to current bytes but does
   not provide an external immutable trust anchor.
10. **Human authority:** `MAIN_MODEL`/architect reviews and `DEVELOPER`
    acceptance remain explicit. Neither checks, reviewer PASS, promotion,
    notifications nor a successful stage sequence accepts a complete sub-spec.

## 2. Adaptation matrix — inspect before changing code

| Concern | SignalHarvester reference | What must be selected in the target repository |
|---|---|---|
| Repository instructions | root/local `AGENTS.md`, `README.md` | Actual rule files, ownership hierarchy and module boundaries |
| Specification lifecycle | `docs/specs/active/`, `spec_status: active`, companion `*-implementation-plan.md` | Existing specs, lifecycle states, stage/requirement IDs and optional plan convention |
| Path authorization | `owningPaths`, `editablePaths`, protected paths, module API context guard | Target's allowed/protected paths, package boundaries and generated-file rules |
| Canonical verification | `./run_checks.sh`, Gradle, Java integration tests | Target's exact build/test/lint/typecheck/contract/integration commands and runtime prerequisites |
| Security checks | pinned Gitleaks, repository-owned configuration | Approved scanner versions, scope, scan policy and reporting/redaction rules |
| Worker | pinned Aider CLI, configured model | Supported executor/version, isolated config, edit protocol, provider approval and cost/time budgets |
| Reviewer | DeepSeek JSON-object adapter | Independently approved provider, exact structured schema, timeouts and privacy policy; or explicit manual review workflow |
| Git | `feature/*`, `agent/*`, SHA-1 full 40-character OIDs | Branch naming, object hash format, worktrees, CI protections, human authorization workflow |
| Evidence | ignored `build/reports/agent-pipeline` and private external custody | Paths outside all cleanup tasks, retention, filesystem permissions, secure backup and incident response |
| Risk policy | `pipeline-policy.json`, regex risk rules | Target's security, migration, infra, contract and data-loss escalations; default unknown to manual |
| Delivery | main-model handoff, explicit developer approval | Actual code review, PR/merge rules, release controls and spec archival permissions |

**Important compatibility restrictions:** the current code enforces
`docs/specs/active`, `spec_status: active`, an Aider-specific adapter,
`feature/*` branches, and SHA-1-shaped 40-character commit IDs. A repo using
other paths, Mercurial, SHA-256 Git objects or different provider protocols
needs deliberate code and test changes. Do **not** weaken validation solely to
make a port appear to work. Some target projects cannot support unattended
worker execution safely; a read-only/manual handoff is a valid outcome.

### Checkpoint-driven sequence portability (A5.3a)

The A5.3a `drive-stage` CLI performs a read-only preflight by default and
executes **one** already approved LOW-risk stage only with explicit
`--execute --developer-approved`. It persists an execution intent, delegates
to the target's own verified worker/check/reviewer cycle and always stops at
human promotion or escalation. A target project's implementation must preserve
non-replayable interrupted runs and avoid treating a Git ref update, provider
verdict or model-generated summary as developer acceptance.

Before adopting unattended scheduling, map the target's real Git checkout
policy to A5.1 CAS and A5.2 next-stage authority. In SignalHarvester these
are intentionally separated by a manual branch checkout; an automated loop
would need a new reviewed design for ref/worktree transitions, lock ownership,
concurrent writers, recovery, cost/risk limits and developer checkpoints.
Do not silently add autonomous checkout, merge or ref movement as a portability
shortcut. Execute expensive checks and provider calls only after explicit
approval. Preserve private evidence outside directories cleared by build tools.

### First live pilot transfer (A5.3b)

Begin with a **test-only**, first-stage packet if the target repository has a
reliable local test harness. The SignalHarvester candidate at
`examples/a53b-preflight-smoke-stage-packet.json` is an example of limiting
worker edit authorization to one test file while holding the controller and
policy read-only. Adapt the requirement IDs, actual Git fixture paths,
canonical checks and port-specific subprocess assertions to the target
project. Never assume that `A5.3b`, `feature/*`, the example packet, or the
SignalHarvester test file are valid authorities in another repository.

Prove preflight idempotency, no secret/provider access in tests, rejection of
stale/dirty HEAD and no intent/worker artifacts before allowing live model
execution. Distinguish **validation of a packet** from **developer approval**:
accepting JSON schema cannot attest a human decision. This pilot does not
establish permission for unattended multi-stage scheduling.

## 3. Suggested porting process for another model

**Discovery (no edits):** read the target root and nearest local instructions,
active specification navigation, ownership docs, current test commands, Git
policy and existing automation. Identify whether a new dependency, public API
or security configuration change needs developer approval. Inventory any
existing source-bound workflows that should be reused rather than replaced.

**Design:** write down a project-specific mapping for every row above. Define
schema/evidence contracts, role boundaries, branch policy, risk escalation,
external provider data protection, and the precise review/acceptance gates.
Produce one small, explicit implementation spec with acceptance tests. Leave
unmapped areas as blockers, never guesses.

**Implementation:** port one bounded slice at a time. Prefer standard-library
Python + local Git where compatible. Do not hardcode SignalHarvester package
names, check commands, API URLs, credentials or model names. Keep the target's
existing CI and spec lifecycle authoritative. Protect file-mode changes,
symlinks, untracked files and verification inputs. Validate any shell commands
as repository-owned trusted configuration, not worker-generated instructions.

**Validation:** exercise deterministic fake worker/provider fixtures; negative
cases (stale HEAD, malformed review, missing logs, protected file edits,
unapproved risk, interrupted Git CAS, provider outages); focused tests;
canonical target checks; and a manually approved live smoke test if privacy
and provider access permit it. Never report test success without running it.
For developer-facing copy/paste commands, run `set -e` inside a child script or
`( ... )` subshell, **not** in the interactive shell. A failed gate must still
produce a nonzero status and readable diagnostics without closing the user's
terminal. Portable filesystem checks should compare canonicalized `Path`
identities (including symlink aliases on macOS) when exact text spelling is
not the contract being tested.

**Rollout:** keep stage execution opt-in at first. Capture sanitized evidence,
obtain independent fresh-context review, then explicit developer acceptance.
Do not enable autonomous multi-stage work or notifications until the target's
risk, failure and recovery tests pass.

## 4. Complete request template for the target-project model

Copy the following into a conversation **in the target project**, alongside
permission to read the SignalHarvester reference and the target repository:

> Adapt the SignalHarvester development-agent pipeline **design** to this
> repository. First read this project's root/local agent instructions,
> specifications, architecture, checks, Git rules and current code. Read
> `tools/agent-pipeline/PORTING.md` and the active pipeline specification in
> SignalHarvester as the design reference, not as this project's authority.
> Produce a mapping of assumptions that hold, that need replacement, and that
> are unsupported. Preserve source-bound stages, immutable evidence,
> independent review, bounded repair, fail-closed security gates, exact Git
> ancestry, private custody, and explicit human acceptance. Select a minimal
> first slice, implement it with target-specific tests and documentation, and
> report all unverified gates. Do not copy SignalHarvester-specific checks,
> model credentials, paths or spec lifecycle without target-repository evidence.
> Do not install new dependencies, send private source to a provider, or
> enable autonomous merges without explicit authorization.

## 5. Existing SignalHarvester commands as a reference, not defaults

`init-stage` starts a new approved LOW-risk stage packet. `run-cycle` runs
bounded implementation/check/review within **one** stage. `export-subspec`
preserves private evidence outside `build/`; `verify-subspec-export` checks its
inventory. `promote-stage` performs an explicitly approved Git CAS on an
un-checked-out feature ref. `prepare-next-stage` verifies the previous
promotion and initializes the next immutable stage at the developer's clean,
checked-out feature HEAD. **It does not invoke a worker or promote the next
stage**. For CLI usage and exact argument contracts, see [README.md](README.md).

Even in SignalHarvester, automated orchestration of the entire sub-spec,
notifications, final review and archival are later milestones. The reusable
architecture does not turn these future capabilities into implemented ones.

## Repair attempt evidence identity

Preserve the task actually sent to each model attempt as a bounded evidence artifact.
The initial attempt uses the manifest-bound `task.md` digest; a repair attempt
uses its own `repair-task-N.md` digest, with the original task retained as a
verifiable prefix and bounded feedback appended. Evidence summaries must
validate the attempt-specific task bytes and worker snapshot agreement; do not
replace this check with manifest-digest equality for all attempts, nor accept
arbitrary worker-supplied digests. Keep raw repair feedback and source files in
private evidence custody, not in compact public summaries.
