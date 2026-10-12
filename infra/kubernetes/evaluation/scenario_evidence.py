#!/usr/bin/env python3
"""Build versioned bounded operational-scenario evidence artifacts for offline evaluation."""

from __future__ import annotations

import contextlib
import hashlib
import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, Mapping

SCHEMA_VERSION = 1
ARTIFACT_TYPE = "signalharvester-operational-scenario-evidence"
MAX_SCENARIOS = 64
MAX_ITEMS_PER_EVIDENCE_KIND = 64
MAX_AFFECTED_SUBSYSTEMS = 16
MAX_LIMITATIONS = 32
MAX_STRING_CHARS = 2048

HEALTH_EXPECTATION_DETECT = "DETECT"
HEALTH_EXPECTATION_REMAIN_HEALTHY = "REMAIN_HEALTHY"
HEALTH_EXPECTATION_DESCRIPTIVE_ONLY = "DESCRIPTIVE_ONLY"
ALERT_EXPECTATION_ATTENTION_REQUIRED = "ATTENTION_REQUIRED"
ALERT_EXPECTATION_NO_ATTENTION = "NO_ATTENTION"
ALERT_EXPECTATION_DESCRIPTIVE_ONLY = "DESCRIPTIVE_ONLY"
HEALTH_EXPECTATIONS = frozenset({
    HEALTH_EXPECTATION_DETECT,
    HEALTH_EXPECTATION_REMAIN_HEALTHY,
    HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
})
ALERT_EXPECTATIONS = frozenset({
    ALERT_EXPECTATION_ATTENTION_REQUIRED,
    ALERT_EXPECTATION_NO_ATTENTION,
    ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
})


class ScenarioEvidenceError(ValueError):
    """Raised when evidence would violate the bounded scenario-artifact contract."""


def utc_now() -> str:
    """Returns one UTC timestamp in ISO-8601 form."""

    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    """Returns the SHA-256 digest for one evidence artifact file."""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_text(value: str, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ScenarioEvidenceError(f"{name} must not be blank")
    normalized = value.strip()
    if len(normalized) > MAX_STRING_CHARS:
        raise ScenarioEvidenceError(f"{name} exceeds {MAX_STRING_CHARS} characters")
    return normalized


def _require_choice(value: str, name: str, allowed: frozenset[str]) -> str:
    normalized = _require_text(value, name).upper()
    if normalized not in allowed:
        raise ScenarioEvidenceError(f"{name} must be one of {sorted(allowed)}")
    return normalized


def _json_copy(value: Any, name: str) -> Any:
    try:
        encoded = json.dumps(value, sort_keys=True)
    except (TypeError, ValueError) as failure:
        raise ScenarioEvidenceError(f"{name} must be JSON serializable") from failure
    if len(encoded) > 256_000:
        raise ScenarioEvidenceError(f"{name} exceeds the bounded 256000-character payload limit")
    return json.loads(encoded)


def _append_bounded(items: list[Any], value: Any, name: str) -> None:
    if len(items) >= MAX_ITEMS_PER_EVIDENCE_KIND:
        raise ScenarioEvidenceError(
            f"{name} exceeds the maximum of {MAX_ITEMS_PER_EVIDENCE_KIND} entries"
        )
    items.append(value)


@dataclass
class ScenarioEvidence:
    """Accumulates one labeled scenario run while keeping ground truth separate from observations."""

    scenario_id: str
    label: str
    fault_injected: bool
    affected_subsystems: tuple[str, ...]
    health_expectation: str = HEALTH_EXPECTATION_DESCRIPTIVE_ONLY
    alert_expectation: str = ALERT_EXPECTATION_DESCRIPTIVE_ONLY
    scenario_run_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    started_at: str = field(default_factory=utc_now)
    ended_at: str | None = None
    outcome: str = "RUNNING"
    failure: dict[str, str] | None = None
    change_markers: list[dict[str, Any]] = field(default_factory=list)
    health_snapshots: list[dict[str, Any]] = field(default_factory=list)
    measurements: list[dict[str, Any]] = field(default_factory=list)
    logs: list[dict[str, Any]] = field(default_factory=list)
    traces: list[dict[str, Any]] = field(default_factory=list)
    artifacts: list[dict[str, Any]] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.scenario_id = _require_text(self.scenario_id, "scenario_id")
        self.label = _require_text(self.label, "label")
        if len(self.affected_subsystems) > MAX_AFFECTED_SUBSYSTEMS:
            raise ScenarioEvidenceError(
                f"affected_subsystems exceeds the maximum of {MAX_AFFECTED_SUBSYSTEMS} entries"
            )
        self.affected_subsystems = tuple(
            _require_text(value, "affected_subsystem") for value in self.affected_subsystems
        )
        self.health_expectation = _require_choice(
            self.health_expectation, "health_expectation", HEALTH_EXPECTATIONS
        )
        self.alert_expectation = _require_choice(
            self.alert_expectation, "alert_expectation", ALERT_EXPECTATIONS
        )

    def add_change_marker(self, phase: str, change: Mapping[str, Any]) -> None:
        """Adds one durable Operations change marker returned by the backend."""

        _append_bounded(
            self.change_markers,
            {"phase": _require_text(phase, "phase"), "change": _json_copy(change, "change")},
            "change markers",
        )

    def add_health_snapshot(self, phase: str, snapshot: Mapping[str, Any]) -> None:
        """Adds one explicit deterministic/statistical Health Snapshot captured during the scenario."""

        _append_bounded(
            self.health_snapshots,
            {"phase": _require_text(phase, "phase"), "snapshot": _json_copy(snapshot, "snapshot")},
            "health snapshots",
        )

    def add_measurement(
        self,
        name: str,
        value: int | float | bool | str | None,
        *,
        phase: str,
        unit: str | None = None,
        source: str | None = None,
    ) -> None:
        """Adds one curated bounded measurement observed by the scenario harness."""

        if not isinstance(value, (int, float, bool, str)) and value is not None:
            raise ScenarioEvidenceError("measurement value must be a JSON scalar")
        item: dict[str, Any] = {
            "name": _require_text(name, "measurement name"),
            "phase": _require_text(phase, "phase"),
            "value": value if not isinstance(value, str) else _require_text(value, "measurement value"),
        }
        if unit is not None:
            item["unit"] = _require_text(unit, "unit")
        if source is not None:
            item["source"] = _require_text(source, "source")
        _append_bounded(self.measurements, item, "measurements")

    def add_log(self, phase: str, source: str, message: str) -> None:
        """Adds one explicitly selected bounded log line, never an unbounded log stream."""

        item = {
            "phase": _require_text(phase, "phase"),
            "source": _require_text(source, "source"),
            "message": _require_text(message, "message"),
        }
        _append_bounded(self.logs, item, "logs")

    def add_trace(self, phase: str, source: str, trace: Mapping[str, Any]) -> None:
        """Adds one bounded trace-search result or trace reference."""

        item = {
            "phase": _require_text(phase, "phase"),
            "source": _require_text(source, "source"),
            "trace": _json_copy(trace, "trace"),
        }
        _append_bounded(self.traces, item, "traces")

    def add_artifact(self, kind: str, path: str, *, sha256: str | None = None) -> None:
        """Adds one companion artifact reference such as a capacity report."""

        item = {
            "kind": _require_text(kind, "artifact kind"),
            "path": _require_text(path, "artifact path"),
        }
        if sha256 is not None:
            item["sha256"] = _require_text(sha256, "artifact sha256")
        _append_bounded(self.artifacts, item, "artifacts")

    def add_limitation(self, limitation: str) -> None:
        """Records a bounded evidence limitation without converting it into detector ground truth."""

        if len(self.limitations) >= MAX_LIMITATIONS:
            raise ScenarioEvidenceError(f"limitations exceeds the maximum of {MAX_LIMITATIONS} entries")
        self.limitations.append(_require_text(limitation, "limitation"))

    def complete(self) -> None:
        """Marks this scenario run as passed."""

        if self.outcome != "RUNNING":
            raise ScenarioEvidenceError(f"scenario is already terminal: {self.outcome}")
        self.outcome = "PASSED"
        self.ended_at = utc_now()

    def fail(self, failure: BaseException) -> None:
        """Marks this scenario run as failed while retaining already captured evidence."""

        if self.outcome != "RUNNING":
            return
        message = str(failure).strip() or failure.__class__.__name__
        self.outcome = "FAILED"
        self.ended_at = utc_now()
        self.failure = {
            "type": failure.__class__.__name__,
            "message": message[:MAX_STRING_CHARS],
        }

    def to_dict(self) -> dict[str, Any]:
        """Returns the versioned JSON-compatible scenario record."""

        return {
            "scenarioId": self.scenario_id,
            "scenarioRunId": self.scenario_run_id,
            "label": self.label,
            "groundTruth": {
                "faultInjected": self.fault_injected,
                "affectedSubsystems": list(self.affected_subsystems),
            },
            "evaluationExpectations": {
                "health": self.health_expectation,
                "alert": self.alert_expectation,
            },
            "window": {
                "startedAt": self.started_at,
                "endedAt": self.ended_at,
            },
            "outcome": self.outcome,
            "failure": self.failure,
            "evidence": {
                "changeMarkers": list(self.change_markers),
                "healthSnapshots": list(self.health_snapshots),
                "measurements": list(self.measurements),
                "logs": list(self.logs),
                "traces": list(self.traces),
                "artifacts": list(self.artifacts),
            },
            "limitations": list(self.limitations),
        }


class ScenarioEvidenceDataset:
    """Owns one bounded collection of labeled scenario records emitted by a live harness run."""

    def __init__(self, runner: str, environment: Mapping[str, Any]):
        self.dataset_run_id = str(uuid.uuid4())
        self.runner = _require_text(runner, "runner")
        self.environment = _json_copy(environment, "environment")
        self.scenarios: list[ScenarioEvidence] = []

    @contextlib.contextmanager
    def scenario(
        self,
        scenario_id: str,
        label: str,
        *,
        fault_injected: bool,
        affected_subsystems: tuple[str, ...] = (),
        health_expectation: str = HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
        alert_expectation: str = ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
    ) -> Iterator[ScenarioEvidence]:
        """Records a terminal PASSED/FAILED outcome while preserving partial evidence on failure."""

        if len(self.scenarios) >= MAX_SCENARIOS:
            raise ScenarioEvidenceError(f"dataset exceeds the maximum of {MAX_SCENARIOS} scenarios")
        scenario = ScenarioEvidence(
            scenario_id=scenario_id,
            label=label,
            fault_injected=fault_injected,
            affected_subsystems=affected_subsystems,
            health_expectation=health_expectation,
            alert_expectation=alert_expectation,
        )
        self.scenarios.append(scenario)
        try:
            yield scenario
        except BaseException as failure:
            scenario.fail(failure)
            raise
        else:
            scenario.complete()

    def to_dict(self) -> dict[str, Any]:
        """Returns the complete JSON-compatible dataset artifact."""

        return {
            "schemaVersion": SCHEMA_VERSION,
            "artifactType": ARTIFACT_TYPE,
            "datasetRunId": self.dataset_run_id,
            "generatedAt": utc_now(),
            "source": {
                "runner": self.runner,
                "environment": self.environment,
            },
            "scenarios": [scenario.to_dict() for scenario in self.scenarios],
        }

    def write(self, path: Path) -> None:
        """Writes this dataset artifact with stable formatting."""

        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
