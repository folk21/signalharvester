"""Checks subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

from . import aider_runtime as aider_runtime_module
from . import core as core_module
from . import workspaces as workspaces_module

def execute_check(repo_root: Path, run_dir: Path, name: str, command: list[str], required: bool) -> int:
    """Execute one command, stream output, and persist its exit status and log digest."""
    checks_path = run_dir / "checks.json"
    checks_data = core_module.read_json(checks_path)
    log_dir = run_dir / "logs"
    log_dir.mkdir(exist_ok=True)
    safe_name = re.sub(r"[^A-Za-z0-9._-]+", "-", name).strip("-") or "check"
    prior_attempts = [entry for entry in checks_data.get("checks", []) if entry.get("name") == name]
    attempt = len(prior_attempts) + 1
    worker_path = run_dir / "worker.json"
    worker_attempt = core_module.read_json(worker_path).get("attempt", 1) if worker_path.exists() else 1
    if type(worker_attempt) is not int or worker_attempt < 1:
        raise ValueError("Invalid worker attempt for command evidence")
    worker_suffix = f"-worker-{worker_attempt}" if worker_attempt > 1 else ""
    log_path = log_dir / f"{safe_name}{worker_suffix}-attempt-{attempt}.log"
    started_at = core_module.utc_now()
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            command,
            cwd=repo_root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=1,
        )
        assert process.stdout is not None
        try:
            for line in process.stdout:
                sys.stdout.write(line)
                log_handle.write(line)
        finally:
            process.stdout.close()
        return_code = process.wait()
    duration_ms = round((time.monotonic() - started) * 1000)
    result = {
        "name": name,
        "required": required,
        "command": command,
        "startedAt": started_at,
        "finishedAt": core_module.utc_now(),
        "durationMs": duration_ms,
        "exitCode": return_code,
        "status": "PASS" if return_code == 0 else "FAIL",
        "log": str(log_path.relative_to(run_dir)),
        "logSha256": core_module.sha256_file(log_path),
    }
    result["attempt"] = attempt
    checks_data.setdefault("checks", []).append(result)
    core_module.write_json(checks_path, checks_data)
    return return_code


def execute_gitleaks_check(
    worktree: Path,
    run_dir: Path,
    manifest: dict[str, Any],
    definition: dict[str, Any],
) -> int:
    """Scan the worker filesystem, retaining only secret-free structured evidence."""
    checks_path = run_dir / "checks.json"
    checks = core_module.read_json(checks_path)
    attempt = 1 + sum(entry.get("name") == "gitleaks" for entry in checks.get("checks", []))
    started_at = core_module.utc_now()
    started = time.monotonic()
    config_relative = definition["config"]
    config_path = worktree / config_relative
    base_commit = manifest.get("repository", {}).get("baseCommit")
    report_findings: list[dict[str, Any]] = []
    version: str | None = None
    config_digest: str | None = None
    scanner_exit = 127
    failure: str | None = None
    report_present = False
    executable = shutil.which(definition["executable"])
    if not isinstance(base_commit, str) or not base_commit:
        failure = "Gitleaks requires the immutable worker base commit"
    elif not config_path.is_file():
        failure = "Repository-owned Gitleaks config is missing"
    elif (worktree / ".gitleaksignore").exists():
        failure = "Worker-provided .gitleaksignore is not allowed to suppress scanner findings"
    else:
        original = subprocess.run(
            ["git", "show", f"{base_commit}:{config_relative}"],
            cwd=worktree, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
        )
        if original.returncode != 0 or original.stdout != config_path.read_bytes():
            failure = "Worker changed the repository-owned Gitleaks config; start a new reviewed run"
        else:
            config_digest = core_module.sha256_file(config_path)
    if failure is None and executable is None:
        failure = "Configured Gitleaks executable is not available on PATH"
    if failure is None:
        try:
            version_result = subprocess.run(
                [executable, "version"], cwd=worktree, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15, check=False,
            )
            version = version_result.stdout.strip()
            if version_result.returncode != 0 or aider_runtime_module.parse_version_tuple(version) != aider_runtime_module.parse_version_tuple(
                definition["supportedVersion"]
            ):
                failure = "Installed Gitleaks version does not match repository policy"
        except (OSError, subprocess.TimeoutExpired):
            failure = "Gitleaks version preflight failed"
    if failure is None:
        try:
            with tempfile.TemporaryDirectory(prefix="signalharvester-gitleaks-") as temp_dir:
                report_path = Path(temp_dir) / "report.json"
                completed = subprocess.run(
                    [
                        executable, "dir", ".", "--config", str(config_path.resolve()),
                        "--report-format", "json", "--report-path", str(report_path),
                        "--redact=100", "--no-banner", "--no-color",
                        "--ignore-gitleaks-allow", "--gitleaks-ignore-path", temp_dir,
                    ],
                    cwd=worktree, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=120, check=False,
                )
                scanner_exit = completed.returncode
                report_present = report_path.is_file()
                if report_present:
                    report_data = json.loads(report_path.read_text(encoding="utf-8"))
                    if report_data is None:
                        report_data = []
                    if not isinstance(report_data, list):
                        raise ValueError("Gitleaks report must contain a findings array")
                    for finding in report_data:
                        if not isinstance(finding, dict):
                            raise ValueError("Gitleaks report contains an invalid finding")
                        report_findings.append({
                            "ruleId": finding.get("RuleID"),
                            "file": finding.get("File"),
                            "startLine": finding.get("StartLine"),
                        })
                if scanner_exit not in (0, 1):
                    failure = "Gitleaks scan failed"
                elif scanner_exit == 1 and not report_present:
                    failure = "Gitleaks detected secrets but did not provide a JSON report"
                elif scanner_exit == 1 or report_findings:
                    failure = "Gitleaks detected potential secrets"
        except (OSError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired):
            failure = "Gitleaks execution or JSON report parsing failed"
    worker_path = run_dir / "worker.json"
    source_digest = core_module.read_json(worker_path).get("changeSha256") if worker_path.is_file() else None
    result = {
        "name": "gitleaks", "required": True, "kind": "gitleaks", "attempt": attempt,
        "startedAt": started_at, "finishedAt": core_module.utc_now(),
        "durationMs": round((time.monotonic() - started) * 1000),
        "status": "PASS" if failure is None else "FAIL",
        "exitCode": scanner_exit,
        "scanner": {
            "executable": definition["executable"],
            "version": version,
            "supportedVersion": definition["supportedVersion"],
            "config": config_relative,
            "configSha256": config_digest,
            "sourceSha256": source_digest,
            "scope": "worker-worktree-directory",
            "reportPresent": report_present,
            "findingCount": len(report_findings),
            "findings": report_findings,
            "error": failure,
        },
    }
    checks.setdefault("checks", []).append(result)
    core_module.write_json(checks_path, checks)
    print(f"Gitleaks: {result['status']} ({len(report_findings)} findings); raw secrets were not recorded")
    return 0 if failure is None else 1


def run_required_check(
    repo_root: Path,
    run_dir: Path,
    manifest: dict[str, Any],
    definition: dict[str, Any],
    policy: dict[str, Any],
) -> int:
    """Run one policy-defined check in the immutable worker source state."""
    if (run_dir / "review.json").exists() or (run_dir / "review-attempt.json").exists():
        raise ValueError("Verification after review requires a new repair round")
    if policy.get("workerExecution", {}).get("required", False):
        workspaces_module.ensure_worker_workspace_matches_result(repo_root, run_dir, manifest)
    worktree = workspaces_module.execution_root(repo_root, manifest)
    if policy.get("workerExecution", {}).get("required", False):
        protected_paths = core_module.PROTECTED_WORKER_CONTROL_PATHS
        changed = set(core_module.read_json(run_dir / "worker.json").get("changedFiles", []))
        if changed.intersection(protected_paths):
            raise ValueError("Worker modified required verification policy or the canonical gate")
    if definition.get("kind", "command") == "gitleaks":
        return execute_gitleaks_check(worktree, run_dir, manifest, definition)
    return execute_check(worktree, run_dir, definition["name"], definition["command"], True)


def validate_security_check_evidence(
    policy: dict[str, Any], checks_data: dict[str, Any], worker_result: dict[str, Any] | None,
) -> None:
    """Reject a claimed successful secret scan without tied structured evidence."""
    latest = {entry.get("name"): entry for entry in checks_data.get("checks", []) if isinstance(entry, dict)}
    for definition in policy["requiredChecks"]:
        if definition.get("kind") != "gitleaks":
            continue
        entry = latest.get(definition["name"])
        if entry is None or entry.get("status") != "PASS":
            continue
        scan = entry.get("scanner")
        if not isinstance(scan, dict) or (
            entry.get("kind") != "gitleaks"
            or entry.get("exitCode") != 0
            or scan.get("version") is None
            or aider_runtime_module.parse_version_tuple(scan["version"]) != aider_runtime_module.parse_version_tuple(definition["supportedVersion"])
            or scan.get("scope") != "worker-worktree-directory"
            or not isinstance(scan.get("reportPresent"), bool)
            or scan.get("findingCount") != 0
            or scan.get("findings") != []
            or not scan.get("configSha256")
            or scan.get("sourceSha256") != (worker_result or {}).get("changeSha256")
        ):
            raise ValueError("Required Gitleaks PASS evidence is missing or inconsistent")
