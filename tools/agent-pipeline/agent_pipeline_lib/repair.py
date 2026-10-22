"""Repair subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
import shutil
from pathlib import Path
from typing import Any

from . import checks as checks_module
from . import core as core_module
from . import stage_scope as stage_scope_module
from . import status as status_module
from . import workspaces as workspaces_module

def _latest_checks(checks: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Return the last persisted result for each check name."""
    return {item["name"]: item for item in checks.get("checks", [])
            if isinstance(item, dict) and isinstance(item.get("name"), str)}


def prepare_repair(
    repo_root: Path, run_dir: Path, manifest: dict[str, Any], policy: dict[str, Any],
    *, automated_feedback: str | None = None, automated_details: str | None = None,
) -> tuple[int, Path]:
    """Authorize one low-risk correction and archive previous verification evidence."""
    worker_path = run_dir / "worker.json"
    if not worker_path.exists():
        raise ValueError("Initial worker attempt is missing")
    worker = core_module.read_json(worker_path)
    attempt = worker.get("attempt", 1)
    if type(attempt) is not int or attempt < 1:
        raise ValueError("Invalid worker attempt number")
    maximum = policy.get("repairExecution", {}).get("maxRepairAttempts", 3)
    if attempt > maximum:
        raise ValueError("Repair attempt budget exhausted; developer intervention required")
    workspaces_module.ensure_source_matches_worker_result(repo_root, run_dir, manifest, worker)
    stage_scope_module.ensure_stage_contract(repo_root, run_dir, manifest)
    changed_files = worker.get("changedFiles", [])
    if not isinstance(changed_files, list):
        raise ValueError("Invalid worker changed-file evidence")
    stage_scope_module.enforce_stage_scope(repo_root, manifest, changed_files)
    risk = status_module.classify_risk(changed_files, policy, True)
    if risk["level"] != "LOW":
        raise ValueError("Non-LOW worker source risk requires developer intervention")
    if any(path in core_module.PROTECTED_WORKER_CONTROL_PATHS
           or path.endswith(".gitleaksignore") for path in changed_files):
        raise ValueError("Worker modified protected verification configuration")

    checks = core_module.read_json(run_dir / "checks.json")
    checks_module.validate_security_check_evidence(policy, checks, worker)
    latest = _latest_checks(checks)
    gitleaks = latest.get("gitleaks")
    if gitleaks is not None and gitleaks.get("status") != "PASS":
        raise ValueError("Security scan failure must be escalated, not repaired automatically")
    if (run_dir / "review-attempt.json").exists() and core_module.read_json(run_dir / "review-attempt.json").get("status") != "PASS":
        raise ValueError("Failed reviewer request requires developer intervention")

    review_path = run_dir / "review.json"
    review = core_module.read_json(review_path) if review_path.exists() else None
    if review is not None:
        findings = [f for f in review.get("findings", []) if f.get("status") == "OPEN"]
        if review.get("verdict") == "ESCALATE" or any(
            f.get("category") in policy.get("escalationCategories", [])
            or f.get("severity") in ("HIGH", "CRITICAL") for f in findings
        ):
            raise ValueError("Review contains blocking-risk or escalation findings")
    if worker.get("status") == "FAIL":
        if worker.get("failureReason") != "worker produced no source changes" or worker.get("exitCode") != 0:
            raise ValueError("Worker failure is not a safe no-diff repair candidate")
        cause = "The previous implementation produced no source diff. Re-read the exact file content and make a real change."
    elif any(entry.get("status") == "FAIL" for entry in latest.values()):
        # Scanner failures were rejected above. Do not send raw tool logs or potential secrets to the model.
        failed = [entry for entry in latest.values() if entry.get("status") == "FAIL"]
        cause = "Required checks failed: " + "; ".join(
            f"{item['name']} (exit {item.get('exitCode')})" for item in failed
        ) + ". Diagnose the implementation from current source and tests; do not alter check configuration."
    elif review is not None and review.get("verdict") == "CHANGES_REQUIRED":
        safe_findings = [f"{f.get('id')}: {f.get('category')}, {f.get('severity')}"
                         for f in findings]
        cause = "Independent review requested changes: " + "; ".join(safe_findings)
    else:
        raise ValueError("No qualifying repairable failure is recorded")

    if automated_feedback is not None:
        if automated_feedback not in ("TEST_FAILURE", "COMPILATION_FAILURE"):
            raise ValueError("Unsupported automatic repair feedback category")
        cause += ("\nThe canonical output identified a deterministic "
                  + ("test failure" if automated_feedback == "TEST_FAILURE" else "compilation failure")
                  + ". Inspect the relevant test/compiler diagnostics within the isolated workspace. "
                    "Never change infrastructure, validation scripts, or scanner configuration to bypass the failure.")
        if automated_details is not None:
            # Accept only identifiers produced by the bounded classifier, never raw log text.
            if len(automated_details) > 500 or not re.fullmatch(r"[A-Za-z0-9_(),:; .-]+", automated_details):
                raise ValueError("Unsafe automatic repair diagnostic identifiers")
            cause += "\nVerified failing test identifiers: " + automated_details

    next_attempt = attempt + 1
    task_path = run_dir / f"repair-task-{next_attempt}.md"
    if task_path.exists() or (run_dir / f"worker-attempt-{next_attempt}.json").exists():
        raise ValueError("Next repair attempt already exists")
    previous_snapshot = run_dir / f"worker-attempt-{attempt}.json"
    if previous_snapshot.exists() and core_module.read_json(previous_snapshot) != worker:
        raise ValueError("Prior worker attempt evidence is inconsistent")
    for name in ("checks.json", "review.json", "review-attempt.json", "status.json", "handoff.json"):
        if (run_dir / name).exists() and (run_dir / f"attempt-{attempt}-{name}").exists():
            raise ValueError("Repair evidence archive already exists")
    task_path.write_text(
        (run_dir / "task.md").read_text(encoding="utf-8")
        + "\n## Bounded repair round\n\n" + cause
        + "\nDo not change scope or weaken checks. Return control on ambiguity.\n",
        encoding="utf-8",
    )
    # The prior evidence remains in numbered immutable snapshots; the active view is reset.
    if not previous_snapshot.exists():
        core_module.write_json(previous_snapshot, worker)
    for name in ("checks.json", "review.json", "review-attempt.json", "status.json", "handoff.json"):
        original = run_dir / name
        if original.exists():
            snapshot = run_dir / f"attempt-{attempt}-{name}"
            if snapshot.exists():
                raise ValueError("Repair evidence archive already exists")
            shutil.copy2(original, snapshot)
    core_module.write_json(run_dir / "checks.json", {"schemaVersion": core_module.SCHEMA_VERSION, "checks": []})
    review_path.unlink(missing_ok=True)
    (run_dir / "review-attempt.json").unlink(missing_ok=True)
    (run_dir / "status.json").unlink(missing_ok=True)
    (run_dir / "handoff.json").unlink(missing_ok=True)
    worker_path.unlink()
    return next_attempt, task_path


def python_unittest_diagnostic(tail: str, blockers: str) -> tuple[str, str] | None:
    """Resolve the real unittest report without trusting nested fixture output.

    Passing negative tests can print complete fake unittest runs, including an
    infrastructure exception. A report is eligible only when its error sections
    and (when present) verbose progress agree with its summary. Among multiple
    eligible reports, one uniquely larger completed suite is the outer run;
    ties or contradictory evidence remain unclassified.
    """
    summary = tail.rfind("==> Verification summary\n")
    if summary < 0:
        return None
    failed = re.findall(r"(?m)^  \[FAIL\] (.+?)\s*$", tail[summary:])
    if failed != ["./tools/agent-pipeline/run_tests.sh"]:
        return None
    reports = list(re.finditer(
        r"(?m)^Ran ([1-9][0-9]*) tests? in [0-9.]+s[ \t]*\n[ \t]*\n"
        r"FAILED \((?:errors|failures)=[1-9][0-9]*(?:, (?:errors|failures)=[1-9][0-9]*)*\)[ \t]*$",
        tail[:summary],
    ))
    if not reports:
        return None
    error_pattern = re.compile(
        r"(?ms)^={10,}[ \t]*\n((?:ERROR|FAIL):[^\n]*\n.*?)"
        r"(?=^={10,}[ \t]*$|^Ran [1-9][0-9]* tests? in |\Z)",
    )
    progress_pattern = re.compile(r"(?m)^test_[A-Za-z0-9_]+ \([A-Za-z0-9_.]+\) \.\.\.")
    candidates: list[tuple[int, str, str]] = []
    previous_end = 0
    for report in reports:
        section = tail[previous_end:report.start()]
        previous_end = report.end()
        errors = list(error_pattern.finditer(section))
        expected = sum(int(n) for n in re.findall(r"(?:errors|failures)=([1-9][0-9]*)", report.group()))
        progress = len(progress_pattern.findall(section))
        # Real unittest -v prints one progress line per test method. Each
        # failing subTest can generate multiple ERROR sections for that method.
        count = int(report.group(1))
        # Failing subTests may have an interrupted progress line before the
        # individual ERROR sections, so allow one missing line per failing
        # test method, never a large mismatch with the reported suite size.
        failing_methods = set(re.findall(
            r"(?m)^(?:ERROR|FAIL): (test_[A-Za-z0-9_]+) \(", section,
        ))
        if len(errors) != expected or (progress and (
            progress > count or count - progress > len(failing_methods)
        )):
            continue
        identifiers: list[str] = []
        outcome = "TEST_FAILURE"
        valid = True
        for match in errors:
            details = match.group(1)
            if re.search(blockers, details):
                outcome = "INFRASTRUCTURE_OR_UNKNOWN"
            name_match = re.match(r"(?:ERROR|FAIL): (test_[A-Za-z0-9_]{1,100}) \(", details)
            if name_match is None:
                valid = False
                break
            types = re.findall(r"(?m)^([A-Za-z][A-Za-z0-9_]{0,63}):", details)
            identifier = f"{name_match.group(1)} ({types[-1] if types else 'UNKNOWN'})"
            if identifier not in identifiers:
                identifiers.append(identifier)
        if valid:
            candidates.append((count, outcome, "; ".join(identifiers[:5])))
    if not candidates:
        return None
    largest = max(candidate[0] for candidate in candidates)
    outer = [candidate for candidate in candidates if candidate[0] == largest]
    if len(outer) != 1:
        return None
    _, outcome, details = outer[0]
    if outcome == "INFRASTRUCTURE_OR_UNKNOWN":
        return outcome, "Unittest traceback includes an infrastructure failure indicator"
    return outcome, details


def classify_automatic_check_failure(
    run_dir: Path, check: dict[str, Any], policy: dict[str, Any],
) -> tuple[str, str]:
    """Identify only allowlisted deterministic build failures from immutable check logs."""
    name = check.get("name")
    if name == "gitleaks":
        return "SECURITY_FAILURE", "Gitleaks must be reviewed by the developer"
    if name not in policy.get("repairExecution", {}).get("automaticCheckRepairNames", []):
        return "UNCLASSIFIED_CHECK_FAILURE", "Check is not approved for automatic repair"
    if check.get("status") != "FAIL" or type(check.get("exitCode")) is not int:
        return "INVALID_CHECK_EVIDENCE", "Check failure status or exit code is inconsistent"
    relative = check.get("log")
    if not isinstance(relative, str) or not re.fullmatch(r"logs/[A-Za-z0-9._-]+\.log", relative):
        return "INVALID_CHECK_EVIDENCE", "Required check log path is invalid"
    log_path = run_dir / relative
    if not log_path.is_file() or core_module.sha256_file(log_path) != check.get("logSha256"):
        return "INVALID_CHECK_EVIDENCE", "Required check log is missing or changed"
    with log_path.open("rb") as handle:
        handle.seek(max(0, log_path.stat().st_size - 512 * 1024))
        tail = handle.read().decode("utf-8", errors="replace")
    # Any infrastructure indication wins over an apparent test failure.
    blockers = (
        r"(?i)\b(?:testcontainers|docker|container runtime|connection refused|"
        r"could not connect|timed out|timeout|network unreachable|unavailable|"
        r"permission denied|could not resolve|dependency resolution|"
        r"daemon disappeared|failed to start|toolchain not found|out of memory)\b"
    )
    python_unittest = python_unittest_diagnostic(tail, blockers)
    if python_unittest is not None:
        return python_unittest
    if re.search(blockers, tail):
        return "INFRASTRUCTURE_OR_UNKNOWN", "Check log contains an infrastructure failure indicator"
    task = r"(?m)^> Task :[A-Za-z0-9:_-]+(?:test|Test) FAILED\s*$"
    compilation = r"(?m)^> Task :[A-Za-z0-9:_-]*compile(?:Test)?Java FAILED\s*$"
    if re.search(task, tail) and re.search(r"(?i)(there were failing tests|tests? failed|test failure)", tail):
        return "TEST_FAILURE", "Canonical check reported deterministic test failures"
    if re.search(compilation, tail) and re.search(r"(?i)(compilation failed|error:)", tail):
        return "COMPILATION_FAILURE", "Canonical check reported Java compilation errors"
    return "UNCLASSIFIED_CHECK_FAILURE", "No deterministic implementation failure was identified"
