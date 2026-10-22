"""Aider runtime subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

from . import core as core_module
from . import git_ops as git_ops_module

def parse_version_tuple(value: str) -> tuple[int, int, int] | None:
    """Parse the first semantic x.y.z version from bounded tool output."""
    match = re.search(r"(?<!\d)(\d+)\.(\d+)\.(\d+)(?!\d)", value)
    if match is None:
        return None
    major, minor, patch = match.groups()
    return int(major), int(minor), int(patch)


def prepare_aider_runtime_config(run_dir: Path) -> Path:
    """Create the empty run-local Aider config that excludes ambient YAML configuration."""
    config_path = run_dir / "aider.runtime.conf.yml"
    config_path.write_text("{}\n", encoding="utf-8")
    return config_path


def build_aider_environment() -> tuple[dict[str, str], list[str]]:
    """Preserve provider credentials while excluding ambient Aider and dotenv configuration."""
    removed = sorted(name for name in os.environ if name.startswith("AIDER_"))
    environment = {name: value for name, value in os.environ.items() if name not in removed}
    environment["PYTHON_DOTENV_DISABLED"] = "1"
    return environment, removed


def _aider_context_functions() -> dict[str, Any]:
    """Load the opt-in adapter without imports or bytecode writes in the repository."""
    source = Path(__file__).resolve().parent.parent / "aider_context.py"
    if not source.is_file() or source.is_symlink():
        raise ValueError("Required Aider context adapter source is missing or unsafe")
    namespace: dict[str, Any] = {"__name__": "_aider_context", "__file__": str(source)}
    exec(compile(source.read_bytes(), str(source), "exec"), namespace)
    return namespace


def configured_aider_flags(worker_policy: dict[str, Any]) -> list[str]:
    """Return the CLI switches whose presence is required by the repository-owned adapter."""
    flags = {argument for argument in worker_policy["arguments"] if argument.startswith("--")}
    flags.add("--read")
    if worker_policy.get("contextProfile") is not None:
        flags.update(("--map-tokens", "--aiderignore"))
    return sorted(flags)


def preflight_aider(
    worktree: Path,
    run_dir: Path,
    worker_policy: dict[str, Any],
    environment: dict[str, str],
    removed_environment_names: list[str],
) -> dict[str, Any]:
    """Verify Aider compatibility and deterministic invocation prerequisites before model access."""
    executable = worker_policy["executable"]
    resolved = shutil.which(executable, path=environment.get("PATH"))
    config_path = run_dir / "aider.runtime.conf.yml"
    result: dict[str, Any] = {
        "status": "FAIL",
        "configuredExecutable": executable,
        "resolvedExecutable": resolved,
        "supportedVersions": worker_policy.get("supportedVersions", []),
        "version": None,
        "versionCommandExitCode": None,
        "helpCommandExitCode": None,
        "missingOptions": [],
        "gitIdentityConfigured": False,
        "ambientModelConfigurationFiles": [],
        "dotenvDisabled": environment.get("PYTHON_DOTENV_DISABLED") == "1",
        "removedAiderEnvironmentVariables": removed_environment_names,
        "config": str(config_path.resolve()),
        "configSha256": core_module.sha256_file(config_path),
        "failureReason": None,
    }
    if resolved is None:
        result["failureReason"] = f"Worker executable is not available on PATH: {executable}"
        return result

    version = subprocess.run(
        [resolved, "--config", str(config_path.resolve()), "--version"],
        cwd=worktree,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    result["versionCommandExitCode"] = version.returncode
    version_text = version.stdout.strip()
    result["version"] = version_text or None
    parsed_version = parse_version_tuple(version_text)
    supported_versions = {
        parsed
        for configured in worker_policy.get("supportedVersions", [])
        if (parsed := parse_version_tuple(configured)) is not None
    }
    if version.returncode != 0 or parsed_version is None:
        result["failureReason"] = "Unable to determine a compatible Aider version"
        return result
    if parsed_version not in supported_versions:
        result["failureReason"] = (
            f"Aider {version_text} is not in repository-supported versions: "
            + ", ".join(worker_policy.get("supportedVersions", []))
        )
        return result

    help_result = subprocess.run(
        [resolved, "--config", str(config_path.resolve()), "--help"],
        cwd=worktree,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    result["helpCommandExitCode"] = help_result.returncode
    missing_options = [
        option for option in configured_aider_flags(worker_policy) if option not in help_result.stdout
    ]
    result["missingOptions"] = missing_options
    if help_result.returncode != 0 or missing_options:
        result["failureReason"] = "Installed Aider CLI does not support the repository-owned worker arguments"
        return result

    model_config_names = (".aider.model.settings.yml", ".aider.model.metadata.json")
    model_config_candidates = [
        *(Path.home() / name for name in model_config_names),
        *(worktree / name for name in model_config_names),
    ]
    ambient_model_files = sorted({str(path.resolve()) for path in model_config_candidates if path.is_file()})
    result["ambientModelConfigurationFiles"] = ambient_model_files
    if ambient_model_files:
        result["failureReason"] = (
            "Ambient Aider model settings/metadata are not allowed in pipeline execution: "
            + ", ".join(ambient_model_files)
        )
        return result

    git_name = git_ops_module.run_git(worktree, "config", "--get", "user.name")
    git_email = git_ops_module.run_git(worktree, "config", "--get", "user.email")
    result["gitIdentityConfigured"] = bool(git_name and git_email)
    if not result["gitIdentityConfigured"]:
        result["failureReason"] = (
            "Git user.name and user.email must be configured before Aider runs so it cannot mutate shared Git config"
        )
        return result
    if not result["dotenvDisabled"]:
        result["failureReason"] = "Aider dotenv loading must be disabled for pipeline execution"
        return result

    result["status"] = "PASS"
    return result
