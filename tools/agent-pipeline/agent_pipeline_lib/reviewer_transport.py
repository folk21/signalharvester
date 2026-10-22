"""Reviewer transport subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import ipaddress
import json
import os
import socket
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from . import checks as checks_module
from . import core as core_module
from . import reviewer_contract as reviewer_contract_module
from . import reviewer_policy as reviewer_policy_module
from . import status as status_module
from . import workspaces as workspaces_module

class _RejectReviewerRedirect(urllib.request.HTTPRedirectHandler):
    """Never forward a bearer token to a redirect destination."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Reviewer HTTP redirect is prohibited")


def _assert_public_reviewer_dns(endpoint: str) -> None:
    """Reject private/reserved DNS answers before contacting a registered reviewer endpoint.

    This is defense in depth only: deployment network egress must also block private IPs
    because a resolver/proxy or an upstream provider can change destinations after lookup.
    """
    hostname = urllib.parse.urlsplit(endpoint).hostname
    if not hostname:
        raise ValueError("Missing reviewer DNS hostname")
    answers = socket.getaddrinfo(hostname, 443, type=socket.SOCK_STREAM)
    if not answers or any(not ipaddress.ip_address(info[4][0]).is_global for info in answers):
        raise ValueError("Reviewer DNS resolved to a nonpublic address")


def _open_reviewer_request(request: urllib.request.Request, timeout: int):
    """Reject nonpublic DNS destinations and all redirects before a bearer request."""
    _assert_public_reviewer_dns(request.full_url)
    return urllib.request.build_opener(_RejectReviewerRedirect()).open(request, timeout=timeout)


def _invoke_reviewer_provider(adapter: dict[str, Any], packet: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Perform one provider request and return validated review plus safe completion metadata."""
    key = os.environ.get(adapter["apiKeyEnvironment"])
    if not key:
        raise ValueError("Reviewer API credential is missing from the environment")
    phase = "request"
    diagnostic: dict[str, Any] = {}
    parsed_review: dict[str, Any] | None = None
    try:
        body = reviewer_contract_module._review_request_body(adapter["model"], packet)
        profile = reviewer_policy_module._reviewer_endpoint_registry().get(adapter["endpoint"])
        if profile == "openrouter-private":
            body["provider"] = reviewer_policy_module._validated_openrouter_routing(adapter.get("providerRouting"))
        elif profile != "openai-chat-completions" or "providerRouting" in adapter:
            raise ValueError("Unapproved or inconsistent reviewer request profile")
        request = urllib.request.Request(
            adapter["endpoint"], data=json.dumps(body).encode("utf-8"),
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            method="POST",
        )
        with _open_reviewer_request(request, timeout=adapter["timeoutSeconds"]) as response:
            raw = response.read(256 * 1024 + 1)
        phase = "envelope"
        if len(raw) > 256 * 1024:
            raise ValueError("Reviewer response exceeds limit")
        envelope = json.loads(raw)
        choice = envelope["choices"][0]
        if "finish_reason" in choice:
            diagnostic["completionCode"] = reviewer_contract_module._review_completion_code(choice["finish_reason"])
            if diagnostic["completionCode"] != "STOP":
                raise ValueError("Reviewer completion was not final")
        content = choice["message"]["content"]
        if not isinstance(content, str):
            raise ValueError("Reviewer content must be a JSON string")
        phase = "content"
        parsed_review = json.loads(content)
        if not isinstance(parsed_review, dict):
            raise ValueError("Reviewer content must be a JSON object")
        parsed_review["reviewer"] = adapter["model"]
        phase = "schema"
        review = reviewer_contract_module.validate_review(parsed_review)
        return review, diagnostic
    except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError) as exc:
        diagnostic["reasonCode"] = reviewer_contract_module._review_failure_reason_code(exc, phase)
        if isinstance(exc, urllib.error.HTTPError):
            diagnostic["httpStatus"] = exc.code
        if phase == "schema" and parsed_review is not None:
            diagnostic["schemaFailureCode"] = reviewer_contract_module._review_schema_failure_code(parsed_review)
        raise reviewer_contract_module.ReviewerProviderFailure(diagnostic) from None


def probe_independent_reviewer(policy: dict[str, Any]) -> dict[str, Any]:
    """Test the configured provider's structured response without a worker run or repository content."""
    adapter = policy["independentReview"]["adapter"]
    packet = {
        "stage": "REVIEWER_TRANSPORT_PROBE", "spec": "synthetic-probe",
        "task": "Review this harmless documentation addition. Report only concrete defects.",
        "changedFiles": ["docs/reviewer-transport-probe.md"],
        "checks": [{"name": "canonical", "status": "PASS"}],
        "diff": ("diff --git a/docs/reviewer-transport-probe.md b/docs/reviewer-transport-probe.md\n"
                 "new file mode 100644\n--- /dev/null\n+++ b/docs/reviewer-transport-probe.md\n"
                 "@@ -0,0 +1 @@\n+Reviewer transport probe.\n"),
    }
    try:
        review, diagnostic = _invoke_reviewer_provider(adapter, packet)
    except reviewer_contract_module.ReviewerProviderFailure as error:
        return {"status": "FAIL", "probeOnly": True, **error.diagnostic}
    return {"status": "PASS", "probeOnly": True, "verdict": review["verdict"],
            "findingsCount": len(review["findings"]), **diagnostic}


def run_independent_review(repo_root: Path, run_dir: Path, manifest: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    """Invoke a fresh-context, read-only reviewer after verified worker evidence."""
    if (run_dir / "review.json").exists() or (run_dir / "review-attempt.json").exists():
        raise ValueError("Review already attempted; a new run is required")
    worker = workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
    if worker.get("status") != "PASS":
        raise ValueError("Successful worker execution required before review")
    checks = core_module.read_json(run_dir / "checks.json")
    checks_module.validate_security_check_evidence(policy, checks, worker)
    state, _ = status_module.derive_state(policy, checks, None, worker)
    if state != core_module.STATE_REVIEW_REQUIRED:
        raise ValueError("All required checks must pass before independent review")
    reviewer_policy = policy["independentReview"]["adapter"]
    worktree = workspaces_module.execution_root(repo_root, manifest)
    base = manifest["repository"]["baseCommit"]
    diff = subprocess.run(
        ["git", "diff", "--no-ext-diff", "--binary", base, "--"],
        cwd=worktree, text=True, capture_output=True, check=True,
    ).stdout
    # Include untracked files in the review without adding them to the Git index.
    for name in worker["changedFiles"]:
        if subprocess.run(["git", "ls-files", "--error-unmatch", "--", name],
                          cwd=worktree, capture_output=True).returncode != 0:
            content = (worktree / name)
            if content.is_file() and not content.is_symlink():
                diff += f"\n--- /dev/null\n+++ b/{name}\n" + content.read_text(encoding="utf-8", errors="replace")
    if not diff.strip() or len(diff.encode("utf-8")) > reviewer_policy["maxDiffBytes"]:
        raise ValueError("Review diff is empty or exceeds the configured bounded context")
    if not os.environ.get(reviewer_policy["apiKeyEnvironment"]):
        raise ValueError("Reviewer API credential is missing from the environment")
    packet = {
        "stage": manifest["stage"], "spec": manifest["specPath"],
        "task": (run_dir / "task.md").read_text(encoding="utf-8"),
        "changedFiles": worker["changedFiles"], "checks": [
            {"name": x.get("name"), "status": x.get("status")}
            for x in checks.get("checks", [])
        ], "diff": diff,
    }
    started = core_module.utc_now()
    try:
        review, diagnostics = _invoke_reviewer_provider(reviewer_policy, packet)
        core_module.write_json(run_dir / "review.json", review)
        reviews_path = run_dir / "reviews.json"
        reviews = core_module.read_json(reviews_path)
        reviews.setdefault("reviews", []).append(review)
        core_module.write_json(reviews_path, reviews)
        core_module.write_json(run_dir / "review-attempt.json", {"status": "PASS", "at": started,
            "model": reviewer_policy["model"], "workerSha256": worker["changeSha256"],
            **diagnostics})
        return review
    except (reviewer_contract_module.ReviewerProviderFailure, OSError, ValueError, KeyError, IndexError, TypeError, AttributeError) as exc:
        attempt = {"status": "FAIL", "at": started,
            "model": reviewer_policy["model"], "reason": "reviewer request or schema failed"}
        if isinstance(exc, reviewer_contract_module.ReviewerProviderFailure):
            attempt.update(exc.diagnostic)
        else:
            attempt["reasonCode"] = "REVIEW_EVIDENCE_FAILURE"
        core_module.write_json(run_dir / "review-attempt.json", attempt)
        raise ValueError("Independent reviewer failed; see review-attempt.json (no raw response stored)") from None
