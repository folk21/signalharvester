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
