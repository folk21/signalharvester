#!/usr/bin/env python3
"""Evaluate structured assisted-investigation results against bounded operational scenario evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from statistics import fmean
from typing import Any, Iterable, Mapping, Sequence

SCENARIO_SCHEMA_VERSION = 1
SCENARIO_ARTIFACT_TYPE = "signalharvester-operational-scenario-evidence"
INVESTIGATION_SCHEMA_VERSION = 1
INVESTIGATION_ARTIFACT_TYPE = "signalharvester-assisted-investigation-evidence"
EVALUATION_SCHEMA_VERSION = 1
EVALUATION_ARTIFACT_TYPE = "signalharvester-assisted-investigation-evaluation"
TRIAL_STATUSES = {"COMPLETED", "FAILED", "BUDGET_EXCEEDED"}
CLAIM_CLASSIFICATIONS = {"SUPPORTED", "UNSUPPORTED", "NON_CAUSAL"}
MAX_INPUTS = 32
MAX_SCENARIOS = 512
MAX_TRIALS = 512
MAX_INPUT_BYTES = 8_000_000
MAX_REFERENCES = 128
MAX_CLAIM_ANNOTATIONS = 64
DEFAULT_OUTPUT = Path("build/reports/operational-intelligence/assisted-investigation-evaluation.json")


class AssistedEvaluationError(ValueError):
    """Raised when assisted-investigation evidence cannot be evaluated deterministically."""


def utc_now() -> str:
    """Returns one UTC timestamp in ISO-8601 form."""

    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    """Returns the SHA-256 digest for one evaluation input."""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_mapping(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise AssistedEvaluationError(f"{name} must be an object")
    return value


def _require_list(value: Any, name: str, *, maximum: int | None = None) -> list[Any]:
    if not isinstance(value, list):
        raise AssistedEvaluationError(f"{name} must be an array")
    if maximum is not None and len(value) > maximum:
        raise AssistedEvaluationError(f"{name} exceeds the maximum of {maximum} entries")
    return value


def _require_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise AssistedEvaluationError(f"{name} must be a non-empty string")
    return value.strip()


def _require_non_negative_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise AssistedEvaluationError(f"{name} must be a non-negative integer")
    return value


def _require_positive_int(value: Any, name: str) -> int:
    result = _require_non_negative_int(value, name)
    if result < 1:
        raise AssistedEvaluationError(f"{name} must be at least one")
    return result


def _require_number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AssistedEvaluationError(f"{name} must be numeric")
    return float(value)


def _ratio(numerator: int, denominator: int) -> float | None:
    return None if denominator == 0 else round(numerator / denominator, 6)


def _mean(values: Iterable[int]) -> float | None:
    materialized = list(values)
    return None if not materialized else round(fmean(materialized), 3)


def _normalize_subsystem(value: str) -> str:
    return "".join(character for character in value.upper() if character.isalnum())


def _load_json(path: Path, *, kind: str) -> Mapping[str, Any]:
    try:
        size = path.stat().st_size
        if size > MAX_INPUT_BYTES:
            raise AssistedEvaluationError(
                f"{kind} artifact {path} exceeds the bounded {MAX_INPUT_BYTES}-byte input limit"
            )
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise AssistedEvaluationError(f"failed to read {kind} artifact {path}: {failure}") from failure
    except json.JSONDecodeError as failure:
        raise AssistedEvaluationError(f"{kind} artifact is not valid JSON: {path}: {failure}") from failure
    return _require_mapping(payload, str(path))


def _load_scenario_dataset(path: Path) -> Mapping[str, Any]:
    root = _load_json(path, kind="scenario evidence")
    if root.get("schemaVersion") != SCENARIO_SCHEMA_VERSION:
        raise AssistedEvaluationError(
            f"unsupported scenario evidence schemaVersion in {path}: {root.get('schemaVersion')!r}"
        )
    if root.get("artifactType") != SCENARIO_ARTIFACT_TYPE:
        raise AssistedEvaluationError(
            f"unsupported scenario evidence artifactType in {path}: {root.get('artifactType')!r}"
        )
    _require_text(root.get("datasetRunId"), "datasetRunId")
    _require_mapping(root.get("source"), "source")
    _require_list(root.get("scenarios"), "scenarios")
    return root


def _load_investigation_dataset(path: Path) -> Mapping[str, Any]:
    root = _load_json(path, kind="assisted-investigation evidence")
    if root.get("schemaVersion") != INVESTIGATION_SCHEMA_VERSION:
        raise AssistedEvaluationError(
            f"unsupported assisted-investigation schemaVersion in {path}: {root.get('schemaVersion')!r}"
        )
    if root.get("artifactType") != INVESTIGATION_ARTIFACT_TYPE:
        raise AssistedEvaluationError(
            f"unsupported assisted-investigation artifactType in {path}: {root.get('artifactType')!r}"
        )
    source = _require_mapping(root.get("source"), "source")
    _require_text(source.get("runner"), "source.runner")
    _require_text(source.get("provider"), "source.provider")
    _require_text(source.get("model"), "source.model")
    budgets = _require_mapping(root.get("budgets"), "budgets")
    _require_non_negative_int(budgets.get("maxToolCalls"), "budgets.maxToolCalls")
    _require_positive_int(budgets.get("maxRounds"), "budgets.maxRounds")
    _require_positive_int(budgets.get("maxInvestigationDurationMs"), "budgets.maxInvestigationDurationMs")
    _require_list(root.get("trials"), "trials")
    return root


def _scenario_index(paths: Sequence[Path]) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    if not paths:
        raise AssistedEvaluationError("at least one scenario evidence artifact is required")
    if len(paths) > MAX_INPUTS:
        raise AssistedEvaluationError(f"at most {MAX_INPUTS} scenario evidence artifacts may be evaluated")

    index: dict[str, dict[str, Any]] = {}
    inputs: list[dict[str, Any]] = []
    scenario_count = 0
    for path in paths:
        dataset = _load_scenario_dataset(path)
        dataset_run_id = _require_text(dataset.get("datasetRunId"), "datasetRunId")
        source = _require_mapping(dataset.get("source"), "source")
        inputs.append(
            {
                "path": str(path),
                "sha256": sha256_file(path),
                "datasetRunId": dataset_run_id,
                "runner": _require_text(source.get("runner"), "source.runner"),
            }
        )
        for raw in _require_list(dataset.get("scenarios"), "scenarios"):
            scenario_count += 1
            if scenario_count > MAX_SCENARIOS:
                raise AssistedEvaluationError(
                    f"scenario evidence exceeds the bounded {MAX_SCENARIOS}-scenario total limit"
                )
            scenario = _require_mapping(raw, "scenario")
            scenario_run_id = _require_text(scenario.get("scenarioRunId"), "scenario.scenarioRunId")
            if scenario_run_id in index:
                raise AssistedEvaluationError(f"duplicate scenarioRunId across inputs: {scenario_run_id}")
            evidence = _require_mapping(scenario.get("evidence"), "scenario.evidence")
            snapshot_ids: set[str] = set()
            for item in _require_list(evidence.get("healthSnapshots"), "scenario.evidence.healthSnapshots"):
                entry = _require_mapping(item, "health snapshot evidence")
                snapshot = _require_mapping(entry.get("snapshot"), "health snapshot evidence.snapshot")
                snapshot_ids.add(_require_text(snapshot.get("id"), "health snapshot id"))
            index[scenario_run_id] = {
                "datasetRunId": dataset_run_id,
                "scenario": scenario,
                "snapshotIds": snapshot_ids,
            }
    return index, inputs


def _text_list(value: Any, name: str, *, maximum: int = MAX_REFERENCES) -> list[str]:
    return [_require_text(item, f"{name}[]") for item in _require_list(value, name, maximum=maximum)]


def _validate_assessment(value: Any, name: str) -> Mapping[str, Any]:
    assessment = _require_mapping(value, name)
    _require_text(assessment.get("snapshotId"), f"{name}.snapshotId")
    _require_text(assessment.get("source"), f"{name}.source")
    _require_text(assessment.get("provider"), f"{name}.provider")
    _require_text(assessment.get("model"), f"{name}.model")
    _require_text(assessment.get("summary"), f"{name}.summary")
    _text_list(assessment.get("suspectedSubsystems"), f"{name}.suspectedSubsystems", maximum=16)
    confidence = _require_number(assessment.get("confidence"), f"{name}.confidence")
    if confidence < 0 or confidence > 1:
        raise AssistedEvaluationError(f"{name}.confidence must be between zero and one")
    _text_list(assessment.get("observations"), f"{name}.observations", maximum=32)
    _text_list(assessment.get("hypotheses"), f"{name}.hypotheses", maximum=32)
    _text_list(assessment.get("evidenceReferences"), f"{name}.evidenceReferences", maximum=64)
    _text_list(assessment.get("recommendedChecks"), f"{name}.recommendedChecks", maximum=32)
    if not isinstance(assessment.get("humanAttentionSuggested"), bool):
        raise AssistedEvaluationError(f"{name}.humanAttentionSuggested must be boolean")
    return assessment


def _claim_annotations(value: Any, name: str) -> list[dict[str, Any]]:
    annotations: list[dict[str, Any]] = []
    for raw in _require_list(value, name, maximum=MAX_CLAIM_ANNOTATIONS):
        item = _require_mapping(raw, f"{name}[]")
        classification = _require_text(item.get("classification"), f"{name}[].classification").upper()
        if classification not in CLAIM_CLASSIFICATIONS:
            raise AssistedEvaluationError(
                f"{name}[].classification must be one of {sorted(CLAIM_CLASSIFICATIONS)}"
            )
        annotations.append(
            {
                "claim": _require_text(item.get("claim"), f"{name}[].claim"),
                "classification": classification,
                "evidenceReferences": _text_list(
                    item.get("evidenceReferences", []), f"{name}[].evidenceReferences", maximum=32
                ),
            }
        )
    return annotations


def _evaluate_trial(
    trial: Mapping[str, Any],
    scenario_record: Mapping[str, Any],
    source: Mapping[str, Any],
    budgets: Mapping[str, Any],
) -> dict[str, Any]:
    trial_id = _require_text(trial.get("trialId"), "trial.trialId")
    scenario_run_id = _require_text(trial.get("scenarioRunId"), "trial.scenarioRunId")
    snapshot_id = _require_text(trial.get("snapshotId"), "trial.snapshotId")
    status = _require_text(trial.get("status"), "trial.status").upper()
    if status not in TRIAL_STATUSES:
        raise AssistedEvaluationError(f"trial.status must be one of {sorted(TRIAL_STATUSES)}")

    scenario = _require_mapping(scenario_record.get("scenario"), "indexed scenario")
    if snapshot_id not in scenario_record.get("snapshotIds", set()):
        raise AssistedEvaluationError(
            f"trial {trial_id} snapshotId {snapshot_id} is not a captured Health Snapshot of scenario {scenario_run_id}"
        )
    outcome = _require_text(scenario.get("outcome"), "scenario.outcome")
    ground_truth = _require_mapping(scenario.get("groundTruth"), "scenario.groundTruth")
    fault_injected = bool(ground_truth.get("faultInjected"))
    expected_raw = _text_list(ground_truth.get("affectedSubsystems", []), "groundTruth.affectedSubsystems", maximum=16)
    expected = {_normalize_subsystem(item): item for item in expected_raw}

    execution = _require_mapping(trial.get("execution"), "trial.execution")
    tool_calls = _require_non_negative_int(execution.get("toolCallCount"), "trial.execution.toolCallCount")
    rounds = _require_non_negative_int(execution.get("roundCount"), "trial.execution.roundCount")
    duration_ms = _require_non_negative_int(execution.get("durationMs"), "trial.execution.durationMs")
    if status == "COMPLETED" and rounds < 1:
        raise AssistedEvaluationError("completed trial.execution.roundCount must be at least one")
    max_tool_calls = _require_non_negative_int(budgets.get("maxToolCalls"), "budgets.maxToolCalls")
    max_rounds = _require_positive_int(budgets.get("maxRounds"), "budgets.maxRounds")
    max_duration_ms = _require_positive_int(
        budgets.get("maxInvestigationDurationMs"), "budgets.maxInvestigationDurationMs"
    )
    budget_checks = {
        "toolCallsWithinBudget": tool_calls <= max_tool_calls,
        "roundsWithinBudget": rounds <= max_rounds,
        "durationWithinBudget": duration_ms <= max_duration_ms,
    }
    budget_compliant = all(budget_checks.values())

    identity = {
        "trialId": trial_id,
        "datasetRunId": scenario_record["datasetRunId"],
        "scenarioId": _require_text(scenario.get("scenarioId"), "scenario.scenarioId"),
        "scenarioRunId": scenario_run_id,
        "label": _require_text(scenario.get("label"), "scenario.label"),
        "scenarioOutcome": outcome,
        "faultInjected": fault_injected,
        "snapshotId": snapshot_id,
        "provider": _require_text(source.get("provider"), "source.provider"),
        "model": _require_text(source.get("model"), "source.model"),
        "status": status,
        "execution": {
            "toolCallCount": tool_calls,
            "roundCount": rounds,
            "durationMs": duration_ms,
            **budget_checks,
            "budgetCompliant": budget_compliant,
        },
    }

    if outcome != "PASSED":
        return {**identity, "evaluable": False, "excludedReason": "SCENARIO_NOT_PASSED"}
    if status != "COMPLETED":
        return {**identity, "evaluable": False, "excludedReason": f"INVESTIGATION_{status}"}

    assessment = _validate_assessment(trial.get("assessment"), "trial.assessment")
    if _require_text(assessment.get("snapshotId"), "trial.assessment.snapshotId") != snapshot_id:
        raise AssistedEvaluationError(f"trial {trial_id} assessment snapshotId does not match trial snapshotId")
    if _require_text(assessment.get("provider"), "trial.assessment.provider") != identity["provider"]:
        raise AssistedEvaluationError(f"trial {trial_id} assessment provider does not match artifact source.provider")
    if _require_text(assessment.get("model"), "trial.assessment.model") != identity["model"]:
        raise AssistedEvaluationError(f"trial {trial_id} assessment model does not match artifact source.model")

    suspected_raw = _text_list(assessment.get("suspectedSubsystems"), "assessment.suspectedSubsystems", maximum=16)
    suspected = {_normalize_subsystem(item): item for item in suspected_raw}
    matched_keys = sorted(set(expected) & set(suspected))
    missed_keys = sorted(set(expected) - set(suspected))
    unexpected_keys = sorted(set(suspected) - set(expected))

    allowed = set(_text_list(trial.get("allowedEvidenceReferences", []), "trial.allowedEvidenceReferences"))
    discovered = set(
        _text_list(trial.get("discoveredEvidenceReferences", []), "trial.discoveredEvidenceReferences")
    )
    allowed.update(discovered)
    citations = _text_list(assessment.get("evidenceReferences"), "assessment.evidenceReferences", maximum=64)
    valid_citations = [reference for reference in citations if reference in allowed]
    invalid_citations = [reference for reference in citations if reference not in allowed]

    annotations = _claim_annotations(trial.get("claimAnnotations", []), "trial.claimAnnotations")
    causal_annotations = [item for item in annotations if item["classification"] != "NON_CAUSAL"]
    unsupported_annotations = [item for item in causal_annotations if item["classification"] == "UNSUPPORTED"]
    supported_annotations = [item for item in causal_annotations if item["classification"] == "SUPPORTED"]
    assessment_citations = set(citations)
    supported_with_valid_citations = 0
    for annotation in supported_annotations:
        references = annotation["evidenceReferences"]
        if references and all(reference in allowed and reference in assessment_citations for reference in references):
            supported_with_valid_citations += 1

    return {
        **identity,
        "evaluable": True,
        "assessment": {
            "source": _require_text(assessment.get("source"), "assessment.source"),
            "confidence": _require_number(assessment.get("confidence"), "assessment.confidence"),
            "humanAttentionSuggested": bool(assessment.get("humanAttentionSuggested")),
        },
        "subsystemIdentification": {
            "groundTruthAffectedSubsystems": expected_raw,
            "suspectedSubsystems": suspected_raw,
            "matchedAffectedSubsystems": [expected[key] for key in matched_keys],
            "missedAffectedSubsystems": [expected[key] for key in missed_keys],
            "unexpectedSuspectedSubsystems": [suspected[key] for key in unexpected_keys],
            "affectedSubsystemCoverage": _ratio(len(matched_keys), len(expected)),
            "suspectedSubsystemPrecision": _ratio(len(matched_keys), len(suspected)),
            "normalOperationUnexpectedSubsystemClaim": (not fault_injected and bool(suspected)),
        },
        "evidenceCitations": {
            "citationCount": len(citations),
            "validCitationCount": len(valid_citations),
            "invalidEvidenceReferences": invalid_citations,
            "citationValidityRate": _ratio(len(valid_citations), len(citations)),
            "hasEvidenceCitations": bool(citations),
        },
        "claimAssessment": {
            "annotationAvailable": bool(annotations),
            "annotatedClaimCount": len(annotations),
            "causalClaimCount": len(causal_annotations),
            "supportedCausalClaimCount": len(supported_annotations),
            "unsupportedCausalClaimCount": len(unsupported_annotations),
            "unsupportedCausalClaimRate": _ratio(len(unsupported_annotations), len(causal_annotations)),
            "supportedClaimsWithValidCitations": supported_with_valid_citations,
            "supportedClaimCitationCoverage": _ratio(
                supported_with_valid_citations, len(supported_annotations)
            ),
        },
    }


def _aggregate(results: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    evaluable = [result for result in results if result.get("evaluable")]
    fault = [result for result in evaluable if result["faultInjected"]]
    normal = [result for result in evaluable if not result["faultInjected"]]

    expected = sum(
        len(result["subsystemIdentification"]["groundTruthAffectedSubsystems"]) for result in fault
    )
    matched = sum(
        len(result["subsystemIdentification"]["matchedAffectedSubsystems"]) for result in fault
    )
    suspected = sum(len(result["subsystemIdentification"]["suspectedSubsystems"]) for result in fault)
    normal_claims = sum(
        result["subsystemIdentification"]["normalOperationUnexpectedSubsystemClaim"] is True
        for result in normal
    )

    citation_total = sum(result["evidenceCitations"]["citationCount"] for result in evaluable)
    valid_citation_total = sum(result["evidenceCitations"]["validCitationCount"] for result in evaluable)
    with_citations = sum(result["evidenceCitations"]["hasEvidenceCitations"] is True for result in evaluable)

    annotated = [result for result in evaluable if result["claimAssessment"]["annotationAvailable"]]
    causal_claims = sum(result["claimAssessment"]["causalClaimCount"] for result in annotated)
    unsupported_claims = sum(result["claimAssessment"]["unsupportedCausalClaimCount"] for result in annotated)
    supported_claims = sum(result["claimAssessment"]["supportedCausalClaimCount"] for result in annotated)
    supported_cited = sum(result["claimAssessment"]["supportedClaimsWithValidCitations"] for result in annotated)

    completed = [result for result in results if result["status"] == "COMPLETED"]
    budget_compliant = sum(result["execution"]["budgetCompliant"] is True for result in completed)
    status_counts = {status: sum(result["status"] == status for result in results) for status in sorted(TRIAL_STATUSES)}

    attention_fault = sum(result["assessment"]["humanAttentionSuggested"] is True for result in fault)
    attention_normal = sum(result["assessment"]["humanAttentionSuggested"] is True for result in normal)

    return {
        "trials": {
            "total": len(results),
            "evaluable": len(evaluable),
            "excluded": len(results) - len(evaluable),
            "statusCounts": status_counts,
        },
        "subsystemIdentification": {
            "faultTrialsEvaluated": len(fault),
            "groundTruthSubsystemsEvaluated": expected,
            "groundTruthSubsystemsMatched": matched,
            "affectedSubsystemCoverage": _ratio(matched, expected),
            "suspectedSubsystemsReported": suspected,
            "suspectedSubsystemPrecision": _ratio(matched, suspected),
            "normalTrialsEvaluated": len(normal),
            "normalTrialsWithUnexpectedSubsystemClaim": normal_claims,
            "normalUnexpectedSubsystemClaimRate": _ratio(normal_claims, len(normal)),
        },
        "evidenceCitations": {
            "trialsEvaluated": len(evaluable),
            "trialsWithEvidenceCitations": with_citations,
            "citationPresenceRate": _ratio(with_citations, len(evaluable)),
            "evidenceReferencesEvaluated": citation_total,
            "validEvidenceReferences": valid_citation_total,
            "citationValidityRate": _ratio(valid_citation_total, citation_total),
        },
        "causalClaims": {
            "trialsWithClaimAnnotations": len(annotated),
            "causalClaimsEvaluated": causal_claims,
            "unsupportedCausalClaims": unsupported_claims,
            "unsupportedCausalClaimRate": _ratio(unsupported_claims, causal_claims),
            "supportedCausalClaims": supported_claims,
            "supportedClaimsWithValidCitations": supported_cited,
            "supportedClaimCitationCoverage": _ratio(supported_cited, supported_claims),
        },
        "execution": {
            "completedTrials": len(completed),
            "meanToolCallCount": _mean(result["execution"]["toolCallCount"] for result in completed),
            "maxToolCallCount": max((result["execution"]["toolCallCount"] for result in completed), default=None),
            "meanRoundCount": _mean(result["execution"]["roundCount"] for result in completed),
            "maxRoundCount": max((result["execution"]["roundCount"] for result in completed), default=None),
            "meanInvestigationDurationMs": _mean(result["execution"]["durationMs"] for result in completed),
            "maxInvestigationDurationMs": max(
                (result["execution"]["durationMs"] for result in completed), default=None
            ),
            "budgetCompliantTrials": budget_compliant,
            "budgetComplianceRate": _ratio(budget_compliant, len(completed)),
        },
        "advisoryAttention": {
            "faultTrialsEvaluated": len(fault),
            "faultTrialsSuggestingAttention": attention_fault,
            "normalTrialsEvaluated": len(normal),
            "normalTrialsSuggestingAttention": attention_normal,
            "note": "humanAttentionSuggested is descriptive advisory output and is not alert authority",
        },
    }


def _model_summaries(results: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str], list[Mapping[str, Any]]] = defaultdict(list)
    for result in results:
        grouped[(result["provider"], result["model"])].append(result)
    return [
        {"provider": provider, "model": model, "summary": _aggregate(grouped[(provider, model)])}
        for provider, model in sorted(grouped)
    ]


def build_evaluation(
    scenario_paths: Sequence[Path], investigation_paths: Sequence[Path]
) -> dict[str, Any]:
    """Builds one descriptive assisted-investigation quality report over shared scenario ground truth."""

    scenario_index, scenario_inputs = _scenario_index(scenario_paths)
    if not investigation_paths:
        raise AssistedEvaluationError("at least one assisted-investigation evidence artifact is required")
    if len(investigation_paths) > MAX_INPUTS:
        raise AssistedEvaluationError(
            f"at most {MAX_INPUTS} assisted-investigation evidence artifacts may be evaluated"
        )

    normalized = [path.resolve() for path in investigation_paths]
    if len(set(normalized)) != len(normalized):
        raise AssistedEvaluationError("duplicate assisted-investigation evidence paths are not allowed")

    investigation_inputs: list[dict[str, Any]] = []
    results: list[dict[str, Any]] = []
    trial_ids: set[str] = set()
    for path in investigation_paths:
        artifact = _load_investigation_dataset(path)
        source = _require_mapping(artifact.get("source"), "source")
        budgets = _require_mapping(artifact.get("budgets"), "budgets")
        investigation_inputs.append(
            {
                "path": str(path),
                "sha256": sha256_file(path),
                "runner": _require_text(source.get("runner"), "source.runner"),
                "provider": _require_text(source.get("provider"), "source.provider"),
                "model": _require_text(source.get("model"), "source.model"),
                "budgets": {
                    "maxToolCalls": _require_non_negative_int(budgets.get("maxToolCalls"), "budgets.maxToolCalls"),
                    "maxRounds": _require_positive_int(budgets.get("maxRounds"), "budgets.maxRounds"),
                    "maxInvestigationDurationMs": _require_positive_int(
                        budgets.get("maxInvestigationDurationMs"), "budgets.maxInvestigationDurationMs"
                    ),
                },
            }
        )
        for raw_trial in _require_list(artifact.get("trials"), "trials"):
            if len(results) >= MAX_TRIALS:
                raise AssistedEvaluationError(f"evaluation exceeds the bounded {MAX_TRIALS}-trial total limit")
            trial = _require_mapping(raw_trial, "trial")
            trial_id = _require_text(trial.get("trialId"), "trial.trialId")
            if trial_id in trial_ids:
                raise AssistedEvaluationError(f"duplicate trialId across inputs: {trial_id}")
            trial_ids.add(trial_id)
            scenario_run_id = _require_text(trial.get("scenarioRunId"), "trial.scenarioRunId")
            scenario_record = scenario_index.get(scenario_run_id)
            if scenario_record is None:
                raise AssistedEvaluationError(
                    f"trial {trial_id} references unknown scenarioRunId {scenario_run_id}"
                )
            results.append(_evaluate_trial(trial, scenario_record, source, budgets))

    return {
        "schemaVersion": EVALUATION_SCHEMA_VERSION,
        "artifactType": EVALUATION_ARTIFACT_TYPE,
        "generatedAt": utc_now(),
        "inputs": {
            "scenarioEvidence": scenario_inputs,
            "assistedInvestigationEvidence": investigation_inputs,
        },
        "evaluation": {
            "subsystemMatching": "CASE_INSENSITIVE_ALPHANUMERIC_EXACT",
            "claimAnnotationMode": "EXPLICIT_ANNOTATION_ONLY",
            "notes": [
                "Scenario ground truth remains independent from model output and does not prescribe Health or alert severity.",
                "Unsupported causal-claim metrics are computed only from explicit SUPPORTED/UNSUPPORTED annotations; the evaluator does not ask another model to judge free-form hypotheses.",
                "Evidence-reference validity measures membership in the captured analysis-package/tool allowlist; it does not prove that a cited reference semantically supports a claim.",
                "humanAttentionSuggested is reported only as advisory model output and never as alert authority.",
            ],
        },
        "summary": _aggregate(results),
        "models": _model_summaries(results),
        "trials": results,
    }


def write_evaluation(path: Path, report: Mapping[str, Any]) -> None:
    """Writes one stable formatted assisted-investigation evaluation artifact."""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--scenario-evidence",
        nargs="+",
        type=Path,
        required=True,
        help="Version-1 operational scenario evidence artifact(s)",
    )
    parser.add_argument(
        "--investigation-evidence",
        nargs="+",
        type=Path,
        required=True,
        help="Version-1 assisted-investigation evidence artifact(s)",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        report = build_evaluation(args.scenario_evidence, args.investigation_evidence)
        write_evaluation(args.output, report)
        print(args.output)
        return 0
    except AssistedEvaluationError as failure:
        print(f"ERROR: {failure}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
