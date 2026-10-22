"""Policy subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import aider_runtime as aider_runtime_module
from . import core as core_module
from . import reviewer_policy as reviewer_policy_module

def validate_policy(policy: dict[str, Any]) -> None:
    """Validate the small policy surface consumed by the foundation tooling."""
    if policy.get("schemaVersion") != core_module.SCHEMA_VERSION:
        raise ValueError(f"Unsupported policy schemaVersion: {policy.get('schemaVersion')}")
    required_checks = policy.get("requiredChecks")
    if not isinstance(required_checks, list) or not required_checks:
        raise ValueError("Policy must define at least one required check")
    seen: set[str] = set()
    for check in required_checks:
        if not isinstance(check, dict):
            raise ValueError("Each required check must be an object")
        name = check.get("name")
        if not isinstance(name, str) or not name or name in seen:
            raise ValueError("Required check names must be unique non-empty strings")
        kind = check.get("kind", "command")
        if kind == "command":
            command = check.get("command")
            if not isinstance(command, list) or not command or not all(
                isinstance(part, str) and part for part in command
            ):
                raise ValueError(f"Required check {name} must define a non-empty string command list")
        elif kind == "gitleaks":
            if name != "gitleaks":
                raise ValueError("The Gitleaks required check must be named 'gitleaks'")
            config = check.get("config")
            if (
                not isinstance(config, str)
                or not config
                or Path(config).is_absolute()
                or ".." in Path(config).parts
            ):
                raise ValueError("Gitleaks config must be a repository-relative path")
            if not isinstance(check.get("executable"), str) or not check["executable"]:
                raise ValueError("Gitleaks executable must be configured")
            if not isinstance(check.get("supportedVersion"), str) or not re.fullmatch(
                r"\d+\.\d+\.\d+", check["supportedVersion"]
            ):
                raise ValueError("Gitleaks supportedVersion must be an exact x.y.z version")
        else:
            raise ValueError(f"Unsupported required check kind: {kind}")
        seen.add(name)
    if any(entry.get("kind") == "gitleaks" for entry in required_checks):
        if required_checks[0]["name"] != "gitleaks":
            raise ValueError("Gitleaks must precede other required checks")

    repair = policy.get("repairExecution", {"maxRepairAttempts": 3})
    if (not isinstance(repair, dict) or type(repair.get("maxRepairAttempts")) is not int
            or not 0 <= repair["maxRepairAttempts"] <= 3):
        raise ValueError("repairExecution.maxRepairAttempts must be an integer from 0 to 3")
    automatic_checks = repair.get("automaticCheckRepairNames", [])
    if (not isinstance(automatic_checks, list)
            or not all(isinstance(name, str) and name for name in automatic_checks)
            or len(set(automatic_checks)) != len(automatic_checks)
            or any(name not in seen or name == "gitleaks" for name in automatic_checks)):
        raise ValueError("repairExecution.automaticCheckRepairNames must reference unique non-security required checks")

    reviewer = policy.get("independentReview", {})
    adapter = reviewer.get("adapter")
    if adapter is not None:
        if not isinstance(adapter, dict) or not all(isinstance(adapter.get(k), str) and adapter[k]
            for k in ("endpoint", "model", "apiKeyEnvironment")):
            raise ValueError("Independent review adapter is invalid")
        endpoints = adapter.get("allowedEndpoints")
        if (not isinstance(endpoints, list) or not 1 <= len(endpoints) <= 16
                or len(set(str(item) for item in endpoints)) != len(endpoints)
                or any(not isinstance(item, str) or not reviewer_policy_module._safe_reviewer_endpoint(item)
                       for item in endpoints)):
            raise ValueError("Reviewer allowedEndpoints must be a bounded list of exact public HTTPS URLs")
        if adapter["endpoint"] not in endpoints:
            raise ValueError("Reviewer endpoint is not in the policy endpoint allowlist")
        # The independent registry selects transport behavior; runtime policy may only narrow it.
        profiles = reviewer_policy_module._reviewer_endpoint_registry()
        if any(url not in profiles for url in endpoints):
            raise ValueError("Reviewer endpoint is not in the independently reviewed registry")
        if profiles[adapter["endpoint"]] == "openrouter-private":
            reviewer_policy_module._validated_openrouter_routing(adapter.get("providerRouting"))
        elif "providerRouting" in adapter:
            raise ValueError("providerRouting is only supported for the privacy-routed reviewer transport")
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", adapter["apiKeyEnvironment"]):
            raise ValueError("Reviewer key must be obtained from a named environment variable")
        if not isinstance(adapter.get("timeoutSeconds"), int) or not 1 <= adapter["timeoutSeconds"] <= 300:
            raise ValueError("Reviewer timeoutSeconds must be bounded")
        if not isinstance(adapter.get("maxDiffBytes"), int) or not 1024 <= adapter["maxDiffBytes"] <= 200000:
            raise ValueError("Reviewer maxDiffBytes must be bounded")

    worker = policy.get("workerExecution")
    if worker is not None:
        if not isinstance(worker, dict):
            raise ValueError("workerExecution must be an object")
        if worker.get("tool") != "aider":
            raise ValueError("workerExecution.tool must be 'aider' in A2.1")
        executable = worker.get("executable")
        if not isinstance(executable, str) or not executable.strip():
            raise ValueError("workerExecution.executable must be a non-empty string")
        default_model = worker.get("defaultModel")
        if not isinstance(default_model, str) or not default_model.strip():
            raise ValueError("workerExecution.defaultModel must be a non-empty string")
        allowed_models = worker.get("allowedModels", [default_model])
        if (not isinstance(allowed_models, list) or not 1 <= len(allowed_models) <= 16
                or any(not isinstance(value, str) or not value.strip() for value in allowed_models)
                or len(set(allowed_models)) != len(allowed_models) or default_model not in allowed_models):
            raise ValueError("workerExecution.allowedModels must be a bounded set including defaultModel")
        if worker.get("contextProfile") is not None:
            aider_runtime_module._aider_context_functions()["validate_context_profile"](worker["contextProfile"])
        supported_versions = worker.get("supportedVersions")
        versions_valid = isinstance(supported_versions, list) and bool(supported_versions)
        if versions_valid:
            versions_valid = all(
                isinstance(version, str) and aider_runtime_module.parse_version_tuple(version) is not None
                for version in supported_versions
            )
        if not versions_valid:
            raise ValueError("workerExecution.supportedVersions must be a non-empty list of x.y.z version strings")
        branch_prefix = worker.get("branchPrefix")
        if not isinstance(branch_prefix, str) or not branch_prefix:
            raise ValueError("workerExecution.branchPrefix must be a non-empty string")
        arguments = worker.get("arguments")
        if (
            not isinstance(arguments, list)
            or not arguments
            or not all(isinstance(part, str) and part for part in arguments)
        ):
            raise ValueError("workerExecution.arguments must be a non-empty string list")
        allowed_placeholders = {
            "{model}",
            "{task_file}",
            "{input_history_file}",
            "{chat_history_file}",
            "{config_file}",
        }
        if "--message-file" not in arguments or "{task_file}" not in arguments:
            raise ValueError("Aider worker arguments must contain --message-file {task_file}")
        for argument in arguments:
            placeholders = set(re.findall(r"\{[^{}]+\}", argument))
            if not placeholders.issubset(allowed_placeholders):
                raise ValueError(f"Unsupported worker argument placeholder in: {argument}")
            if "api-key" in argument.lower():
                raise ValueError("Worker API keys must come from the environment, not repository policy arguments")


def authorized_worker_model(policy: dict[str, Any], worker: str, model: str) -> None:
    """Reject legacy init overrides outside the separately reviewed executor/model policy."""
    execution = policy.get("workerExecution", {})
    if execution.get("defaultModel") is None and worker == "aider" and model == "unspecified":
        # Older evidence-only stage fixtures may prepare a packet without a configured
        # executable worker. This cannot authorize an actual coding-model call.
        return
    if worker != execution.get("tool", "aider"):
        raise ValueError("Unapproved worker executor override")
    models = execution.get("allowedModels", [execution.get("defaultModel")])
    if model not in models:
        raise ValueError("Unapproved worker model override")


def validate_repository(repo_root: Path, policy: dict[str, Any]) -> None:
    """Fail closed when required repository context is missing."""
    missing = [relative for relative in policy.get("requiredRepositoryFiles", []) if not (repo_root / relative).is_file()]
    if missing:
        raise ValueError(f"Missing required repository files: {', '.join(missing)}")


def validate_active_spec(repo_root: Path, spec_path: str) -> None:
    """Require a declared product specification to point at the active specification tree."""
    if spec_path == "none":
        return
    normalized = Path(spec_path)
    if normalized.is_absolute() or ".." in normalized.parts:
        raise ValueError("Specification path must be repository-relative")
    if normalized.parts[:3] != ("docs", "specs", "active"):
        raise ValueError("Specification path must be under docs/specs/active or be 'none'")
    full_path = repo_root / normalized
    if not full_path.is_file():
        raise ValueError(f"Active specification does not exist: {spec_path}")
    head = "\n".join(full_path.read_text(encoding="utf-8").splitlines()[:20])
    if "spec_status: active" not in head:
        raise ValueError(f"Specification is not marked active: {spec_path}")


def required_check_map(policy: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Index required check definitions by stable name."""
    return {entry["name"]: entry for entry in policy["requiredChecks"]}
