#!/usr/bin/env python3
"""Run controlled resilience acceptance against the local SignalHarvester Kubernetes stack."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import contextlib
import http.cookiejar
import importlib.util
import ipaddress
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen


ROOT = Path(__file__).resolve().parents[3]
FIXTURE_MANIFEST = ROOT / "infra" / "kubernetes" / "resilience" / "fixture.yaml"
VERIFY_LOCAL = ROOT / "infra" / "kubernetes" / "verify-local.sh"
BACKEND_DEPLOYMENT = "signalharvester-backend"
EXPECTED_POSTGRES_REPLICAS = 1
RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1"
ANALYSIS_DLQ_TOPIC = "signalharvester.analysis.raw-item-dead-letter.v1"
ANALYSIS_GROUP = "signalharvester-analysis-v1"
RESULTS_GROUP = "signalharvester-results-v1"
EVENT_OBSERVATION_GROUP = "signalharvester-event-observation-v1"
KAFKA_RECOVERY_GROUPS = (ANALYSIS_GROUP, RESULTS_GROUP, EVENT_OBSERVATION_GROUP)
ANALYSIS_RETRY_LOG_PATTERN = re.compile(r"Analysis Kafka processing attempt \d+ failed for ")
ANALYSIS_DLQ_LOG_PATTERN = re.compile(r"Analysis Kafka record .* moved to dead letter after 3 attempt\(s\)")
DEFAULT_EVIDENCE_REPORT = (
    ROOT / "build" / "reports" / "operational-intelligence" / "resilience-scenario-evidence.json"
)
EVIDENCE_MODULE = ROOT / "infra" / "kubernetes" / "evaluation" / "scenario_evidence.py"

evidence_spec = importlib.util.spec_from_file_location("signalharvester_scenario_evidence", EVIDENCE_MODULE)
scenario_evidence = importlib.util.module_from_spec(evidence_spec)
assert evidence_spec.loader is not None
sys.modules[evidence_spec.name] = scenario_evidence
evidence_spec.loader.exec_module(scenario_evidence)


class AcceptanceError(RuntimeError):
    """Raised when the live Kubernetes system violates an acceptance guarantee."""


@dataclass(frozen=True)
class CommandResult:
    stdout: str
    stderr: str
    returncode: int


class CommandRunner:
    def __init__(self, namespace: str):
        self.namespace = namespace

    def run(
        self,
        args: list[str],
        *,
        input_text: str | None = None,
        check: bool = True,
        timeout: float | None = None,
    ) -> CommandResult:
        completed = subprocess.run(
            args,
            cwd=ROOT,
            input=input_text,
            text=True,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        result = CommandResult(completed.stdout, completed.stderr, completed.returncode)
        if check and completed.returncode != 0:
            command = " ".join(args)
            raise AcceptanceError(
                f"command failed ({completed.returncode}): {command}\n"
                f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
            )
        return result

    def kubectl(self, *args: str, **kwargs: Any) -> CommandResult:
        return self.run(["kubectl", *args], **kwargs)

    def namespaced(self, *args: str, **kwargs: Any) -> CommandResult:
        return self.kubectl("-n", self.namespace, *args, **kwargs)

    def rpk(self, *args: str, input_text: str | None = None, check: bool = True) -> CommandResult:
        return self.namespaced(
            "exec",
            "-i",
            "redpanda-0",
            "--",
            "rpk",
            *args,
            "-X",
            "brokers=localhost:9092",
            input_text=input_text,
            check=check,
        )


class ApiSession:
    def __init__(self, base_url: str, timeout: float):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.cookies = http.cookiejar.CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))

    def for_base_url(self, base_url: str) -> "ApiSession":
        """Create a session for another local tunnel while preserving issued cookies."""

        session = ApiSession(base_url, self.timeout)
        for cookie in self.cookies:
            session.cookies.set_cookie(cookie)
        return session

    def login(self, username: str, password: str) -> None:
        status, _ = self.request("POST", "/api/v1/auth/login", {"username": username, "password": password})
        if status != 200:
            raise AcceptanceError(f"login failed for {username}: HTTP {status}")
        if self.cookie_value("SIGNALHARVESTER_AUTH") is None:
            raise AcceptanceError("login did not set SIGNALHARVESTER_AUTH")
        if self.cookie_value("XSRF-TOKEN") is None:
            raise AcceptanceError("login did not set XSRF-TOKEN")

    def get_json(self, path: str, query: dict[str, Any] | None = None) -> Any:
        suffix = ""
        if query:
            suffix = "?" + urlencode({key: value for key, value in query.items() if value is not None})
        status, body = self.request("GET", path + suffix)
        if status != 200:
            raise AcceptanceError(f"GET {path} returned HTTP {status}: {body!r}")
        return body

    def post_json(
        self,
        path: str,
        payload: dict[str, Any],
        expected: int = 201,
        timeout: float | None = None,
    ) -> Any:
        status, body = self.request("POST", path, payload, timeout=timeout)
        if status != expected:
            raise AcceptanceError(f"POST {path} returned HTTP {status}, expected {expected}: {body!r}")
        return body

    def put_json(self, path: str, payload: dict[str, Any], expected: int = 200) -> Any:
        status, body = self.request("PUT", path, payload)
        if status != expected:
            raise AcceptanceError(f"PUT {path} returned HTTP {status}, expected {expected}: {body!r}")
        return body

    def delete(self, path: str, expected: int = 204) -> None:
        status, body = self.request("DELETE", path)
        if status != expected:
            raise AcceptanceError(f"DELETE {path} returned HTTP {status}, expected {expected}: {body!r}")

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        *,
        timeout: float | None = None,
    ) -> tuple[int, Any]:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if method in {"POST", "PUT", "DELETE"}:
            csrf = self.cookie_value("XSRF-TOKEN")
            if csrf is not None and path != "/api/v1/auth/login":
                headers["X-CSRF-TOKEN"] = csrf
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        request_timeout = self.timeout if timeout is None else timeout
        try:
            with self.opener.open(request, timeout=request_timeout) as response:
                status = response.status
                raw = response.read()
        except HTTPError as failure:
            status = failure.code
            raw = failure.read()
        except URLError as failure:
            raise AcceptanceError(f"{method} {path} failed: {failure.reason}") from failure

        if not raw:
            return status, None
        text = raw.decode("utf-8", errors="replace")
        try:
            return status, json.loads(text)
        except json.JSONDecodeError:
            return status, text

    def cookie_value(self, name: str) -> str | None:
        for cookie in self.cookies:
            if cookie.name == name:
                return cookie.value
        return None


class BackendEnvironmentGuard:
    def __init__(self, runner: CommandRunner, rollout_timeout: str):
        self.runner = runner
        self.rollout_timeout = rollout_timeout
        deployment = json.loads(
            runner.namespaced("get", "deployment", BACKEND_DEPLOYMENT, "-o", "json").stdout
        )
        container = deployment["spec"]["template"]["spec"]["containers"][0]
        self.original = {entry["name"]: entry.get("value") for entry in container.get("env", [])}
        self.changed: set[str] = set()
        self.post_rollout_probe: Callable[[], None] | None = None

    def set_post_rollout_probe(self, probe: Callable[[], None] | None) -> None:
        """Set the application-level probe run after deliberate backend environment rollouts."""

        self.post_rollout_probe = probe

    def effective_value(self, name: str) -> str | None:
        pod = first_ready_backend_pod(self.runner)
        result = self.runner.namespaced("exec", pod, "--", "printenv", name, check=False)
        return result.stdout.strip() if result.returncode == 0 else None

    def set(self, name: str, value: str | None) -> None:
        self.set_many({name: value})

    def set_many(self, values: dict[str, str | None]) -> None:
        arguments = []
        for name, value in values.items():
            arguments.append(f"{name}-" if value is None else f"{name}={value}")
            self.changed.add(name)
        self.runner.namespaced("set", "env", f"deployment/{BACKEND_DEPLOYMENT}", *arguments)
        rollout_backend(self.runner, self.rollout_timeout)
        if self.post_rollout_probe is not None:
            self.post_rollout_probe()

    def restore(self) -> None:
        if not self.changed:
            return
        arguments = []
        for name in sorted(self.changed):
            if name in self.original and self.original[name] is not None:
                arguments.append(f"{name}={self.original[name]}")
            else:
                arguments.append(f"{name}-")
        self.runner.namespaced("set", "env", f"deployment/{BACKEND_DEPLOYMENT}", *arguments, check=False)
        try:
            rollout_backend(self.runner, self.rollout_timeout)
        except AcceptanceError as failure:
            print(f"WARNING: backend environment restoration rollout failed: {failure}", file=sys.stderr)


class ResourceTracker:
    def __init__(self, admin: ApiSession):
        self.admin = admin
        self.profile_ids: list[str] = []
        self.source_ids: list[str] = []
        self.viewer: tuple[str, list[str]] | None = None

    def source(self, path: str, label: str) -> str:
        source = self.admin.post_json(
            "/api/v1/sources",
            {
                "name": f"Resilience {label} {uuid.uuid4().hex[:8]}",
                "type": "RSS",
                "location": f"http://resilience-fixture:8080{path}",
                "enabled": True,
                "settings": {},
            },
        )
        source_id = require_string(source, "id")
        self.source_ids.append(source_id)
        return source_id

    def profile(self, source_id: str, label: str, *, enabled: bool = False, interval: int = 60) -> str:
        profile = self.admin.post_json(
            "/api/v1/monitoring-profiles",
            {
                "name": f"Resilience {label} {uuid.uuid4().hex[:8]}",
                "informationCategory": "RESILIENCE",
                "enabled": enabled,
                "collectionIntervalMinutes": interval,
                "sourceIds": [source_id],
                "criteria": {},
            },
        )
        profile_id = require_string(profile, "id")
        self.profile_ids.append(profile_id)
        return profile_id

    def cleanup(self) -> None:
        for profile_id in reversed(self.profile_ids):
            try:
                self.admin.delete(f"/api/v1/monitoring-profiles/{profile_id}")
            except Exception as failure:  # noqa: BLE001 - cleanup should continue
                print(f"WARNING: failed to delete profile {profile_id}: {failure}", file=sys.stderr)
        for source_id in reversed(self.source_ids):
            try:
                self.admin.delete(f"/api/v1/sources/{source_id}")
            except Exception as failure:  # noqa: BLE001 - cleanup should continue
                print(f"WARNING: failed to delete source {source_id}: {failure}", file=sys.stderr)
        if self.viewer is not None:
            user_id, roles = self.viewer
            try:
                self.admin.put_json(
                    f"/api/v1/admin/users/{user_id}",
                    {"enabled": False, "roles": roles},
                )
            except Exception as failure:  # noqa: BLE001 - cleanup should continue
                print(f"WARNING: failed to disable resilience viewer {user_id}: {failure}", file=sys.stderr)


def require_string(value: dict[str, Any], key: str) -> str:
    candidate = value.get(key)
    if not isinstance(candidate, str) or not candidate:
        raise AcceptanceError(f"response is missing non-empty string field {key!r}: {value!r}")
    return candidate


def capture_health_snapshot(admin: ApiSession) -> dict[str, Any]:
    """Captures one explicit persisted Health Snapshot through the Operations HTTP boundary."""

    status, body = admin.request("POST", "/api/v1/admin/operations/health/snapshots")
    if status != 201 or not isinstance(body, dict):
        raise AcceptanceError(f"Health Snapshot capture returned HTTP {status}: {body!r}")
    return body


def record_scenario_marker(
    admin: ApiSession,
    scenario: Any,
    phase: str,
) -> dict[str, Any]:
    """Persists one sanitized TEST_SCENARIO marker that can be correlated with Health Snapshots."""

    return admin.post_json(
        "/api/v1/admin/operations/changes/markers",
        {
            "category": "TEST_SCENARIO",
            "targetType": "SCENARIO",
            "targetId": scenario.scenario_run_id,
            "details": {
                "scenarioId": scenario.scenario_id,
                "label": scenario.label,
                "phase": phase,
            },
        },
    )


def record_scenario_start(admin: ApiSession, scenario: Any) -> None:
    """Records the durable start marker and a pre-fault Health Snapshot."""

    marker = record_scenario_marker(admin, scenario, "START")
    scenario.add_change_marker("START", marker)
    scenario.add_health_snapshot("BEFORE", capture_health_snapshot(admin))


def record_scenario_fault_boundary(admin: ApiSession, scenario: Any, phase: str) -> None:
    """Records one durable fault-window boundary used by offline detection timing."""

    if phase not in {"FAULT_START", "FAULT_END"}:
        raise AcceptanceError(f"unsupported scenario fault boundary phase: {phase}")
    marker = record_scenario_marker(admin, scenario, phase)
    scenario.add_change_marker(phase, marker)


def record_scenario_end(admin: ApiSession, scenario: Any) -> None:
    """Records a post-recovery Health Snapshot followed by the durable end marker."""

    scenario.add_health_snapshot("AFTER", capture_health_snapshot(admin))
    marker = record_scenario_marker(admin, scenario, "END")
    scenario.add_change_marker("END", marker)


def repository_relative(path: Path) -> str:
    """Returns a stable repository-relative artifact path when possible."""

    try:
        return str(path.resolve().relative_to(ROOT.resolve()))
    except ValueError:
        return str(path.resolve())


def run_preflight(runner: CommandRunner, timeout: str | None = None) -> None:
    print("==> Kubernetes deployment preflight")
    command = ["env", f"SIGNALHARVESTER_K8S_NAMESPACE={runner.namespace}"]
    if timeout is not None:
        command.append(f"SIGNALHARVESTER_K8S_VERIFY_TIMEOUT={timeout}")
    command.extend(["sh", str(VERIFY_LOCAL)])
    completed = runner.run(command, check=False)
    if completed.returncode != 0:
        raise AcceptanceError(
            f"verify-local.sh failed before resilience acceptance\n{completed.stdout}\n{completed.stderr}"
        )
    print(completed.stdout.strip())


def rollout_backend(runner: CommandRunner, timeout: str) -> None:
    runner.namespaced("rollout", "status", f"deployment/{BACKEND_DEPLOYMENT}", f"--timeout={timeout}")


def rollout_statefulset(runner: CommandRunner, name: str, timeout: str) -> None:
    runner.namespaced("rollout", "status", f"statefulset/{name}", f"--timeout={timeout}")


def statefulset_replicas(runner: CommandRunner, name: str) -> int:
    """Returns the desired replica count for one StatefulSet."""

    payload = json.loads(runner.namespaced("get", "statefulset", name, "-o", "json").stdout)
    replicas = payload.get("spec", {}).get("replicas")
    if not isinstance(replicas, int) or replicas < 0:
        raise AcceptanceError(f"statefulset/{name} has invalid spec.replicas: {replicas!r}")
    return replicas


def restore_postgres_acceptance_baseline(runner: CommandRunner, timeout: str) -> bool:
    """Restores the PostgreSQL replica count used by resilience acceptance.

    The harness is allowed to repair only its known outage state (zero replicas), whether reached
    during controlled cleanup or left by an interrupted run. Any other unexpected topology is
    rejected instead of being silently mutated.
    """

    replicas = statefulset_replicas(runner, "postgres")
    if replicas not in {0, EXPECTED_POSTGRES_REPLICAS}:
        raise AcceptanceError(
            "resilience acceptance requires statefulset/postgres replicas=1; "
            f"found replicas={replicas} and will not rewrite an unexpected topology"
        )
    repaired = replicas == 0
    if not repaired:
        return False
    print(
        "WARNING: restoring statefulset/postgres from replicas=0 to the one-replica "
        "resilience acceptance baseline",
        file=sys.stderr,
    )
    runner.namespaced(
        "scale",
        "statefulset/postgres",
        f"--replicas={EXPECTED_POSTGRES_REPLICAS}",
    )
    rollout_statefulset(runner, "postgres", timeout)
    return repaired


def ready_backend_pods(runner: CommandRunner) -> list[str]:
    """Return ready backend pod names in stable order."""

    pods = json.loads(
        runner.namespaced(
            "get",
            "pods",
            "-l",
            "app.kubernetes.io/name=signalharvester-backend",
            "-o",
            "json",
        ).stdout
    )
    ready = []
    for item in pods.get("items", []):
        conditions = item.get("status", {}).get("conditions", [])
        if any(condition.get("type") == "Ready" and condition.get("status") == "True" for condition in conditions):
            ready.append(item["metadata"]["name"])
    return sorted(ready)


def first_ready_backend_pod(runner: CommandRunner) -> str:
    pods = ready_backend_pods(runner)
    if not pods:
        raise AcceptanceError("no ready backend pod found")
    return pods[0]


def backend_replicas(runner: CommandRunner) -> int:
    deployment = json.loads(
        runner.namespaced("get", "deployment", BACKEND_DEPLOYMENT, "-o", "json").stdout
    )
    return int(deployment["spec"].get("replicas", 1))


def wait_until(description: str, timeout: float, interval: float, predicate: Callable[[], bool]) -> None:
    deadline = time.monotonic() + timeout
    last_failure: Exception | None = None
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except Exception as failure:  # noqa: BLE001 - transient dependency state is expected
            last_failure = failure
        time.sleep(interval)
    detail = f"; last error: {last_failure}" if last_failure else ""
    raise AcceptanceError(f"timed out waiting for {description}{detail}")


def wait_for_port_forward_ready(
    process: subprocess.Popen[str],
    log_path: Path,
    log_offset: int,
    resource: str,
    timeout: float = 20.0,
    stop: threading.Event | None = None,
) -> None:
    """Wait until one specific kubectl port-forward generation reports its forwarding socket."""

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if stop is not None and stop.is_set():
            raise AcceptanceError(f"port-forward {resource} readiness wait was cancelled")
        if process.poll() is not None:
            with log_path.open() as log_reader:
                log_reader.seek(log_offset)
                generation_log = log_reader.read()
            raise AcceptanceError(
                f"port-forward {resource} exited before becoming ready: {generation_log}"
            )
        with log_path.open() as log_reader:
            log_reader.seek(log_offset)
            generation_log = log_reader.read()
        if "Forwarding from" in generation_log:
            return
        time.sleep(0.2)
    with log_path.open() as log_reader:
        log_reader.seek(log_offset)
        generation_log = log_reader.read()
    raise AcceptanceError(
        f"port-forward {resource} did not become ready within {timeout:.0f}s: {generation_log}"
    )


@contextlib.contextmanager
def port_forward(runner: CommandRunner, resource: str, local_port: int, remote_port: int):
    """Keep one local kubectl tunnel alive across pod/container replacement."""

    log = tempfile.NamedTemporaryFile(prefix="signalharvester-port-forward-", suffix=".log", delete=False)
    log_path = Path(log.name)
    log.close()
    stream = log_path.open("a")
    stop = threading.Event()
    lock = threading.Lock()
    process_holder: dict[str, subprocess.Popen[str] | None] = {"process": None}

    def launch() -> tuple[subprocess.Popen[str], int]:
        log_offset = log_path.stat().st_size
        process = subprocess.Popen(
            [
                "kubectl",
                "-n",
                runner.namespace,
                "port-forward",
                resource,
                f"{local_port}:{remote_port}",
            ],
            cwd=ROOT,
            stdout=stream,
            stderr=subprocess.STDOUT,
            text=True,
        )
        return process, log_offset

    initial, initial_log_offset = launch()
    process_holder["process"] = initial
    supervisor: threading.Thread | None = None
    try:
        wait_for_port_forward_ready(initial, log_path, initial_log_offset, resource, stop=stop)

        def supervise() -> None:
            while not stop.wait(0.2):
                with lock:
                    current = process_holder["process"]
                if current is None or current.poll() is None:
                    continue
                if stop.is_set():
                    return
                print(
                    f"WARNING: restarting port-forward {resource} after exit code {current.returncode}",
                    file=sys.stderr,
                )
                try:
                    replacement, replacement_log_offset = launch()
                except OSError as failure:
                    print(f"WARNING: failed to restart port-forward {resource}: {failure}", file=sys.stderr)
                    continue
                with lock:
                    if stop.is_set():
                        replacement.terminate()
                        return
                    process_holder["process"] = replacement
                try:
                    wait_for_port_forward_ready(
                        replacement,
                        log_path,
                        replacement_log_offset,
                        resource,
                        stop=stop,
                    )
                except AcceptanceError as failure:
                    if stop.is_set():
                        if replacement.poll() is None:
                            replacement.terminate()
                        return
                    print(
                        f"WARNING: replacement port-forward {resource} did not become ready: {failure}",
                        file=sys.stderr,
                    )
                    if replacement.poll() is None:
                        replacement.terminate()
                    continue
                print(f"Port-forward {resource} recovered after selected pod replacement.", file=sys.stderr)

        supervisor = threading.Thread(
            target=supervise,
            name=f"port-forward-{local_port}",
            daemon=True,
        )
        supervisor.start()
        yield
    finally:
        stop.set()
        with lock:
            process = process_holder["process"]
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if supervisor is not None:
            supervisor.join(timeout=5)
        stream.close()
        log_path.unlink(missing_ok=True)


def public_get(url: str, timeout: float = 5.0) -> tuple[int, str]:
    request = Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as failure:
        return failure.code, failure.read().decode("utf-8", errors="replace")
    except URLError as failure:
        raise AcceptanceError(f"GET {url} failed: {failure.reason}") from failure


def read_runtime_secret(runner: CommandRunner, key: str) -> str:
    secret = json.loads(
        runner.namespaced("get", "secret", "signalharvester-runtime-secrets", "-o", "json").stdout
    )
    encoded = secret.get("data", {}).get(key)
    if not encoded:
        raise AcceptanceError(f"runtime Secret is missing {key}")
    return base64.b64decode(encoded).decode("utf-8")


def deploy_fixture(runner: CommandRunner, timeout: str) -> str:
    runner.kubectl("apply", "-f", str(FIXTURE_MANIFEST))
    runner.namespaced(
        "rollout",
        "status",
        "deployment/signalharvester-resilience-fixture",
        f"--timeout={timeout}",
    )
    service = json.loads(runner.namespaced("get", "service", "resilience-fixture", "-o", "json").stdout)
    cluster_ip = service["spec"]["clusterIP"]
    if not cluster_ip or cluster_ip == "None":
        raise AcceptanceError("resilience fixture Service has no ClusterIP")
    return cluster_ip


def fixture_cidr(cluster_ip: str) -> str:
    address = ipaddress.ip_address(cluster_ip)
    return f"{cluster_ip}/{32 if address.version == 4 else 128}"


def create_viewer(admin: ApiSession, tracker: ResourceTracker, base_url: str, timeout: float) -> ApiSession:
    username = f"resilience-viewer-{uuid.uuid4().hex[:8]}"
    password = secrets.token_urlsafe(24)
    user = admin.post_json(
        "/api/v1/admin/users",
        {
            "username": username,
            "password": password,
            "identityType": "HUMAN",
            "enabled": True,
            "roles": ["VIEWER"],
        },
    )
    user_id = require_string(user, "id")
    roles = [str(role) for role in user.get("roles", ["VIEWER"])]
    tracker.viewer = (user_id, roles)
    viewer = ApiSession(base_url, timeout)
    viewer.login(username, password)
    return viewer


def run_collection(
    admin: ApiSession,
    profile_id: str,
    *,
    timeout: float | None = None,
) -> dict[str, Any]:
    run = admin.post_json(
        "/api/v1/admin/collection-runs",
        {"monitoringProfileId": profile_id},
        timeout=timeout,
    )
    if run.get("status") not in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}:
        raise AcceptanceError(f"collection run failed: {run!r}")
    return run


def wait_results(
    client: ApiSession,
    profile_id: str,
    source_id: str,
    expected: int,
    timeout: float,
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []

    def loaded() -> bool:
        nonlocal results
        value = client.get_json(
            "/api/v1/results",
            {"monitoringProfileId": profile_id, "sourceId": source_id, "limit": 200},
        )
        results = value if isinstance(value, list) else []
        return len(results) >= expected

    wait_until(f"{expected} Results for profile {profile_id}", timeout, 1.0, loaded)
    return results


def result_count(client: ApiSession, profile_id: str, source_id: str) -> int:
    value = client.get_json(
        "/api/v1/results",
        {"monitoringProfileId": profile_id, "sourceId": source_id, "limit": 200},
    )
    return len(value) if isinstance(value, list) else 0


def analysis_count(admin: ApiSession, profile_id: str, source_id: str) -> int:
    value = admin.get_json(
        "/api/v1/admin/analysis/items",
        {"monitoringProfileId": profile_id, "sourceId": source_id, "limit": 200},
    )
    return len(value) if isinstance(value, list) else 0


def backend_pod_restart_count(runner: CommandRunner, pod: str) -> int:
    data = json.loads(runner.namespaced("get", "pod", pod, "-o", "json").stdout)
    statuses = data.get("status", {}).get("containerStatuses", [])
    if not statuses:
        return 0
    return int(statuses[0].get("restartCount", 0))


def backend_pod_ready(runner: CommandRunner, pod: str) -> bool:
    data = json.loads(runner.namespaced("get", "pod", pod, "-o", "json").stdout)
    conditions = data.get("status", {}).get("conditions", [])
    return any(condition.get("type") == "Ready" and condition.get("status") == "True" for condition in conditions)


def restart_backend_container(
    runner: CommandRunner,
    viewer: ApiSession,
    timeout: float,
    surviving_backend_port: int,
) -> dict[str, int]:
    """Restart one backend container while proving a distinct replica remains usable."""

    print("==> Stateless JWT and backend container restart")
    pods = ready_backend_pods(runner)
    if len(pods) < 2:
        raise AcceptanceError("backend restart acceptance requires two ready backend pods")
    pod, survivor = pods[0], pods[1]
    before = backend_pod_restart_count(runner, pod)
    survivor_base_url = f"http://127.0.0.1:{surviving_backend_port}"

    with port_forward(runner, f"pod/{survivor}", surviving_backend_port, 8080):
        survivor_viewer = viewer.for_base_url(survivor_base_url)
        wait_until(
            f"existing VIEWER JWT access through surviving backend pod {survivor}",
            20,
            0.5,
            lambda: survivor_viewer.request("GET", "/api/v1/results?limit=1")[0] == 200,
        )
        runner.namespaced("exec", pod, "--", "kill", "1", check=False)

        wait_until(
            f"backend pod {pod} restart count to increase",
            timeout,
            1.0,
            lambda: backend_pod_restart_count(runner, pod) > before,
        )
        wait_until(
            f"authenticated Results access through surviving backend pod {survivor}",
            timeout,
            0.5,
            lambda: survivor_viewer.request("GET", "/api/v1/results?limit=1")[0] == 200,
        )
        wait_until(
            f"backend pod {pod} to become ready after restart",
            timeout,
            1.0,
            lambda: backend_pod_ready(runner, pod),
        )

    with port_forward(runner, f"pod/{pod}", surviving_backend_port, 8080):
        restarted_viewer = viewer.for_base_url(survivor_base_url)
        wait_until(
            f"existing VIEWER JWT access through restarted backend pod {pod}",
            timeout,
            0.5,
            lambda: restarted_viewer.request("GET", "/api/v1/results?limit=1")[0] == 200,
        )

    return {
        "restartCountBefore": before,
        "restartCountAfter": backend_pod_restart_count(runner, pod),
        "survivingReplicaHttpStatus": 200,
        "restartedReplicaHttpStatus": 200,
    }


def verify_authorization(viewer: ApiSession) -> None:
    print("==> Live authorization boundaries")
    status, _ = viewer.request("GET", "/api/v1/results?limit=1")
    if status != 200:
        raise AcceptanceError(f"VIEWER cannot access Results: HTTP {status}")
    status, _ = viewer.request("GET", "/api/v1/admin/users")
    if status != 403:
        raise AcceptanceError(f"VIEWER unexpectedly accessed ADMIN users API: HTTP {status}")
    anonymous_status, _ = public_get(viewer.base_url + "/api/v1/results?limit=1")
    if anonymous_status != 401:
        raise AcceptanceError(f"anonymous Results request returned HTTP {anonymous_status}, expected 401")


def verify_slow_source_availability(
    admin: ApiSession,
    viewer: ApiSession,
    tracker: ResourceTracker,
    timeout: float,
    scenario: Any | None = None,
) -> dict[str, int]:
    print("==> Slow source availability")
    source_id = tracker.source("/slow.xml", "slow")
    profile_id = tracker.profile(source_id, "slow")
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(run_collection, admin, profile_id)
        time.sleep(1.0)
        status, body = public_get(admin.base_url + "/health/readiness")
        if status != 200 or "UP" not in body:
            raise AcceptanceError(f"deployment readiness failed during slow source fetch: HTTP {status} {body}")
        viewer_status, _ = viewer.request("GET", "/api/v1/results?limit=1")
        if viewer_status != 200:
            raise AcceptanceError(f"VIEWER API became unavailable during slow source fetch: HTTP {viewer_status}")
        if scenario is not None:
            scenario.add_health_snapshot("FAULT", capture_health_snapshot(admin))
        future.result(timeout=timeout)
    wait_results(viewer, profile_id, source_id, 1, timeout)
    return {"readinessHttpStatus": status, "viewerHttpStatus": viewer_status}


def rpk_topic_record_count(runner: CommandRunner, topic: str) -> int:
    output = runner.rpk("topic", "describe", topic, "--print-partitions").stdout
    return parse_topic_record_count(output)


def parse_topic_record_count(output: str) -> int:
    total = 0
    matched = False
    pattern = re.compile(r"^\s*\d+\s+\S+\s+\S+\s+\[[^]]+\]\s+(\d+)\s+(\d+)\s*$")
    for line in output.splitlines():
        match = pattern.match(line)
        if not match:
            continue
        matched = True
        start = int(match.group(1))
        high = int(match.group(2))
        total += max(0, high - start)
    if not matched:
        raise AcceptanceError(f"could not parse rpk topic partition output:\n{output}")
    return total


def capture_latest_raw_record(runner: CommandRunner) -> tuple[str, str]:
    result = runner.rpk(
        "topic",
        "consume",
        RAW_TOPIC,
        "--offset",
        "-1",
        "--num",
        "1",
        "--format",
        "%k\\t%v{hex}\\n",
    )
    line = next((line for line in result.stdout.splitlines() if "\t" in line), "")
    key, separator, payload_hex = line.partition("\t")
    if not separator or not key or not payload_hex:
        raise AcceptanceError(f"could not capture a valid raw Kafka record: {result.stdout!r}")
    return key, payload_hex


def backend_logs(runner: CommandRunner) -> str:
    pods = json.loads(
        runner.namespaced(
            "get",
            "pods",
            "-l",
            "app.kubernetes.io/name=signalharvester-backend",
            "-o",
            "json",
        ).stdout
    )
    chunks = []
    for item in pods.get("items", []):
        pod = item["metadata"]["name"]
        result = runner.namespaced("logs", pod, "--since=10m", check=False)
        chunks.append(result.stdout)
    return "\n".join(chunks)


def analysis_retry_log_counts(logs: str) -> tuple[int, int]:
    """Return retry-warning and terminal-DLQ log counts from bounded backend logs."""

    return (
        len(ANALYSIS_RETRY_LOG_PATTERN.findall(logs)),
        len(ANALYSIS_DLQ_LOG_PATTERN.findall(logs)),
    )


def wait_for_authenticated_backend_read(
    admin: ApiSession,
    timeout: float,
    description: str,
) -> int:
    """Wait until the authenticated read-only backend path is reachable and database-backed."""

    started = time.monotonic()

    def backend_available() -> bool:
        status, body = admin.request("GET", "/api/v1/sources")
        if status == 200:
            return True
        raise AcceptanceError(
            f"database-backed GET /api/v1/sources returned HTTP {status}: {body!r}"
        )

    wait_until(description, timeout, 1.0, backend_available)
    return int((time.monotonic() - started) * 1000)


def wait_for_application_database_recovery(admin: ApiSession, timeout: float) -> int:
    """Wait until a read-only authenticated API call can use PostgreSQL again."""

    return wait_for_authenticated_backend_read(
        admin,
        timeout,
        "application database connectivity after PostgreSQL recovery",
    )


def wait_for_backend_rollout_api_recovery(admin: ApiSession, timeout: float) -> int:
    """Wait until the Service tunnel reaches a usable backend after Deployment replacement."""

    return wait_for_authenticated_backend_read(
        admin,
        timeout,
        "backend API connectivity after Deployment rollout",
    )


def verify_retry_dlq_and_postgres_recovery(
    runner: CommandRunner,
    admin: ApiSession,
    viewer: ApiSession,
    tracker: ResourceTracker,
    raw_record: tuple[str, str],
    timeout: float,
    rollout_timeout: str,
    scenario: Any | None = None,
) -> dict[str, int]:
    print("==> PostgreSQL outage, bounded Analysis retry, DLQ, and recovery")
    before = rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
    retry_logs_before, terminal_logs_before = analysis_retry_log_counts(backend_logs(runner))
    key, payload_hex = raw_record

    runner.namespaced("scale", "statefulset/postgres", "--replicas=0")
    wait_until(
        "PostgreSQL pod termination",
        timeout,
        1.0,
        lambda: runner.namespaced("get", "pods", "-l", "app.kubernetes.io/name=postgres", "-o", "name").stdout.strip() == "",
    )

    fault_started = time.monotonic()
    dlq_detection_ms = 0
    retry_log_count = 0
    terminal_log_count = 0
    try:
        runner.rpk(
            "topic",
            "produce",
            RAW_TOPIC,
            "--partition",
            "0",
            "--format",
            "%k\\t%v{hex}\\n",
            input_text=f"{key}\t{payload_hex}\n",
        )
        wait_until(
            "Analysis DLQ publication after retry exhaustion",
            timeout,
            1.0,
            lambda: rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC) > before,
        )
        dlq_detection_ms = int((time.monotonic() - fault_started) * 1000)
        wait_until(
            "Analysis retry exhaustion log",
            timeout,
            1.0,
            lambda: analysis_retry_log_counts(backend_logs(runner))[1] > terminal_logs_before,
        )
        after = rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
        logs_after = backend_logs(runner)
        retry_logs_after, terminal_logs_after = analysis_retry_log_counts(logs_after)
        retry_log_count = max(0, retry_logs_after - retry_logs_before)
        terminal_log_count = max(0, terminal_logs_after - terminal_logs_before)
        if retry_log_count < 2 or terminal_log_count < 1:
            raise AcceptanceError(
                "Analysis PostgreSQL outage did not expose the expected bounded 3-attempt retry/DLQ sequence: "
                f"retryLogs={retry_log_count}, terminalDlqLogs={terminal_log_count}"
            )
        if scenario is not None:
            scenario.add_measurement(
                "analysisDlqRecordsBefore", before, phase="BEFORE", unit="records", source="rpk"
            )
            scenario.add_measurement(
                "analysisDlqRecordsDuringFault", after, phase="FAULT", unit="records", source="rpk"
            )
            scenario.add_measurement(
                "analysisRetryLogCountDuringFault",
                retry_log_count,
                phase="FAULT",
                unit="attempt-failures",
                source="kubernetes-backend-logs",
            )
            scenario.add_measurement(
                "analysisTerminalDlqLogCountDuringFault",
                terminal_log_count,
                phase="FAULT",
                unit="records",
                source="kubernetes-backend-logs",
            )
            scenario.add_measurement(
                "analysisDlqDetectionMs",
                dlq_detection_ms,
                phase="FAULT",
                unit="ms",
                source="resilience-harness",
            )
            match = ANALYSIS_DLQ_LOG_PATTERN.search(logs_after)
            if match is not None:
                scenario.add_log("FAULT", "kubernetes-backend-logs", match.group(0))
    finally:
        restore_postgres_acceptance_baseline(runner, rollout_timeout)
        rollout_backend(runner, rollout_timeout)

    database_recovery_ms = wait_for_application_database_recovery(admin, timeout)
    if scenario is not None:
        scenario.add_measurement(
            "databaseRecoveryMs",
            database_recovery_ms,
            phase="AFTER",
            unit="ms",
            source="configuration-api",
        )

    source_id = tracker.source("/post-db-recovery.xml", "post-db-recovery")
    profile_id = tracker.profile(source_id, "post-db-recovery")
    run_collection(admin, profile_id)
    wait_results(viewer, profile_id, source_id, 1, timeout)
    final = rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
    return {
        "analysisDlqRecordsBefore": before,
        "analysisDlqRecordsAfter": final,
        "analysisRetryLogCountDuringFault": retry_log_count,
        "analysisTerminalDlqLogCountDuringFault": terminal_log_count,
        "analysisDlqDetectionMs": dlq_detection_ms,
        "databaseRecoveryMs": database_recovery_ms,
    }


def parse_consumer_group_recovery_status(payload: str, expected_group: str) -> dict[str, int | str]:
    """Parse the bounded rpk group status needed by broker-recovery acceptance."""

    data = json.loads(payload)
    records: list[dict[str, Any]] = []

    def collect(value: Any) -> None:
        if isinstance(value, dict):
            normalized = {str(key).lower().replace("-", "_"): child for key, child in value.items()}
            group_name = normalized.get("group_name", normalized.get("group"))
            if group_name == expected_group:
                records.append(value)
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(data)
    if not records:
        candidates = data if isinstance(data, list) else [data]
        for candidate in candidates:
            if not isinstance(candidate, dict):
                continue
            normalized_candidate = {
                str(key).lower().replace("-", "_"): value for key, value in candidate.items()
            }
            if "state" in normalized_candidate and "partitions" in normalized_candidate:
                records.append(candidate)
                break
    if not records:
        raise AcceptanceError(f"rpk group JSON did not contain {expected_group}: {payload}")
    record = records[0]
    normalized = {str(key).lower().replace("-", "_"): value for key, value in record.items()}
    state = normalized.get("state")
    members_value = normalized.get("members")
    partitions = normalized.get("partitions")
    total_lag_value = normalized.get("total_lag")
    if not isinstance(state, str) or not state.strip():
        raise AcceptanceError(f"consumer group {expected_group} JSON has no state: {payload}")
    if isinstance(members_value, int):
        members = members_value
    elif isinstance(members_value, list):
        members = len(members_value)
    else:
        members = 0
    assigned_partitions = 0
    member_ids: set[str] = set()
    lag_sum = 0
    if isinstance(partitions, list):
        for partition in partitions:
            if not isinstance(partition, dict):
                continue
            row = {str(key).lower().replace("-", "_"): value for key, value in partition.items()}
            member_id = row.get("member_id", row.get("memberid"))
            if isinstance(member_id, str) and member_id.strip() and member_id != "-":
                assigned_partitions += 1
                member_ids.add(member_id.strip())
            lag = row.get("lag")
            if isinstance(lag, (int, float)):
                lag_sum += max(0, int(lag))
    if members == 0 and member_ids:
        members = len(member_ids)
    total_lag = int(total_lag_value) if isinstance(total_lag_value, (int, float)) else lag_sum
    return {
        "state": state.strip(),
        "members": members,
        "assignedPartitions": assigned_partitions,
        "totalLag": total_lag,
    }


def consumer_group_recovery_status(runner: CommandRunner, group: str) -> dict[str, int | str]:
    """Read one application consumer group's coordination state from Redpanda."""

    result = runner.rpk("group", "describe", group, "--format", "json", check=False)
    if result.returncode != 0 or not result.stdout.strip():
        raise AcceptanceError(f"consumer group {group} is unavailable: {result.stderr.strip()}")
    return parse_consumer_group_recovery_status(result.stdout, group)


def wait_for_kafka_application_recovery(runner: CommandRunner, timeout: float) -> dict[str, int]:
    """Wait until all application Kafka consumer groups are stable again after broker recovery."""

    expected_members = backend_replicas(runner)
    latest: dict[str, dict[str, int | str]] = {}
    started = time.monotonic()

    def recovered() -> bool:
        nonlocal latest
        current: dict[str, dict[str, int | str]] = {}
        failures: list[str] = []
        for group in KAFKA_RECOVERY_GROUPS:
            status = consumer_group_recovery_status(runner, group)
            current[group] = status
            state = status["state"]
            members = int(status["members"])
            assignments = int(status["assignedPartitions"])
            if state != "Stable" or members < expected_members or assignments <= 0:
                failures.append(
                    f"{group}(state={state},members={members},assignments={assignments})"
                )
        latest = current
        if failures:
            raise AcceptanceError(
                "Kafka application consumers are not recovered: " + ", ".join(failures)
            )
        return True

    wait_until(
        "application Kafka consumer groups after Redpanda recovery",
        timeout,
        1.0,
        recovered,
    )
    elapsed_ms = int((time.monotonic() - started) * 1000)
    return {
        "kafkaApplicationRecoveryMs": elapsed_ms,
        "analysisConsumerMembersAfterBrokerRestart": int(latest[ANALYSIS_GROUP]["members"]),
        "resultsConsumerMembersAfterBrokerRestart": int(latest[RESULTS_GROUP]["members"]),
        "eventObservationConsumerMembersAfterBrokerRestart": int(
            latest[EVENT_OBSERVATION_GROUP]["members"]
        ),
        "analysisConsumerLagAfterBrokerRestart": int(latest[ANALYSIS_GROUP]["totalLag"]),
    }


def analysis_group_lag(runner: CommandRunner) -> int:
    result = runner.rpk("group", "describe", ANALYSIS_GROUP, "--format", "json", check=False)
    if result.returncode != 0 or not result.stdout.strip():
        raise AcceptanceError(f"analysis consumer group is unavailable: {result.stderr.strip()}")
    data = json.loads(result.stdout)
    lag_values: list[int] = []

    def collect(value: Any, key: str = "") -> None:
        if isinstance(value, dict):
            for child_key, child in value.items():
                collect(child, child_key)
        elif isinstance(value, list):
            for child in value:
                collect(child, key)
        elif "lag" in key.lower() and isinstance(value, (int, float)):
            lag_values.append(int(value))

    collect(data)
    if not lag_values:
        raise AcceptanceError(f"rpk group JSON contained no lag fields: {result.stdout}")
    return max(lag_values)


def verify_analysis_lag_recovery(
    runner: CommandRunner,
    env_guard: BackendEnvironmentGuard,
    admin: ApiSession,
    viewer: ApiSession,
    tracker: ResourceTracker,
    timeout: float,
    scenario: Any | None = None,
) -> dict[str, int]:
    print("==> Kafka lag generation and Analysis recovery")
    source_id = tracker.source("/lag.xml", "lag")
    profile_id = tracker.profile(source_id, "lag")

    env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", "false")
    run = run_collection(admin, profile_id)
    if int(run.get("publishedCount", 0)) < 4:
        raise AcceptanceError(f"lag fixture did not publish four raw items: {run!r}")
    wait_until("positive Analysis consumer lag", timeout, 1.0, lambda: analysis_group_lag(runner) > 0)
    fault_lag = analysis_group_lag(runner)
    if result_count(viewer, profile_id, source_id) != 0:
        raise AcceptanceError("Results appeared while Analysis consumer was disabled")
    if scenario is not None:
        scenario.add_measurement("analysisConsumerLag", fault_lag, phase="FAULT", unit="records", source="rpk")
        scenario.add_health_snapshot("FAULT", capture_health_snapshot(admin))

    env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", None)
    wait_until("Analysis consumer lag to drain", timeout, 1.0, lambda: analysis_group_lag(runner) == 0)
    wait_results(viewer, profile_id, source_id, 4, timeout)
    final_lag = analysis_group_lag(runner)
    return {"faultLag": fault_lag, "finalLag": final_lag}


def psql_scalar(runner: CommandRunner, sql: str) -> str:
    db_user = read_runtime_secret(runner, "SIGNALHARVESTER_DB_USERNAME")
    result = runner.namespaced(
        "exec",
        "postgres-0",
        "--",
        "psql",
        "-U",
        db_user,
        "-d",
        "signalharvester",
        "-Atc",
        sql,
    )
    return result.stdout.strip()


def pending_outbox_count(runner: CommandRunner) -> int:
    value = psql_scalar(runner, "SELECT count(*) FROM analysis.event_outbox WHERE published_at IS NULL;")
    return int(value)


def verify_outbox_recovery(
    runner: CommandRunner,
    env_guard: BackendEnvironmentGuard,
    admin: ApiSession,
    viewer: ApiSession,
    tracker: ResourceTracker,
    timeout: float,
    scenario: Any | None = None,
) -> dict[str, int]:
    print("==> Analysis outbox durability across rollout")
    baseline_pending = pending_outbox_count(runner)
    source_id = tracker.source("/outbox.xml", "outbox")
    profile_id = tracker.profile(source_id, "outbox")

    env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_ENABLED", "false")
    run_collection(admin, profile_id)
    wait_until(
        "durable Analysis state while outbox dispatcher is disabled",
        timeout,
        1.0,
        lambda: analysis_count(admin, profile_id, source_id) >= 3,
    )
    wait_until(
        "pending outbox rows",
        timeout,
        1.0,
        lambda: pending_outbox_count(runner) >= baseline_pending + 3,
    )
    if result_count(viewer, profile_id, source_id) != 0:
        raise AcceptanceError("Results appeared while Analysis outbox dispatcher was disabled")
    fault_pending = pending_outbox_count(runner)
    if scenario is not None:
        scenario.add_measurement(
            "pendingAnalysisOutboxRows", fault_pending, phase="FAULT", unit="rows", source="postgresql"
        )
        scenario.add_health_snapshot("FAULT", capture_health_snapshot(admin))

    env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_ENABLED", None)
    wait_results(viewer, profile_id, source_id, 3, timeout)
    wait_until(
        "outbox pending count to return to baseline",
        timeout,
        1.0,
        lambda: pending_outbox_count(runner) <= baseline_pending,
    )
    final_pending = pending_outbox_count(runner)
    return {"baselinePending": baseline_pending, "faultPending": fault_pending, "finalPending": final_pending}


def runs_for_profile(admin: ApiSession, profile_id: str) -> list[dict[str, Any]]:
    runs = admin.get_json("/api/v1/admin/collection-runs", {"limit": 200})
    return [run for run in runs if run.get("monitoringProfileId") == profile_id]


def verify_scheduler_lease(
    runner: CommandRunner,
    admin: ApiSession,
    tracker: ResourceTracker,
    timeout: float,
) -> None:
    print("==> Multi-replica scheduler lease")
    source_id = tracker.source("/scheduler.xml", "scheduler")
    profile_id = tracker.profile(source_id, "scheduler", enabled=True, interval=1)

    wait_until(
        "scheduler state initialization",
        timeout,
        1.0,
        lambda: psql_scalar(
            runner,
            "SELECT count(*) FROM collection.monitoring_profile_schedule_state "
            f"WHERE monitoring_profile_id = '{profile_id}'::uuid;",
        )
        == "1",
    )
    psql_scalar(
        runner,
        "UPDATE collection.monitoring_profile_schedule_state SET next_due_at = now(), lease_until = NULL, "
        f"lease_token = NULL WHERE monitoring_profile_id = '{profile_id}'::uuid; SELECT 1;",
    )
    wait_until(
        "one scheduled run",
        timeout,
        1.0,
        lambda: len(runs_for_profile(admin, profile_id)) >= 1,
    )
    time.sleep(12)
    count = len(runs_for_profile(admin, profile_id))
    if count != 1:
        raise AcceptanceError(f"expected exactly one scheduled run under two replicas, observed {count}")


def restart_redpanda_and_verify(
    runner: CommandRunner,
    admin: ApiSession,
    viewer: ApiSession,
    tracker: ResourceTracker,
    timeout: float,
    rollout_timeout: str,
) -> dict[str, int]:
    print("==> Redpanda restart recovery")
    runner.namespaced("delete", "pod", "redpanda-0", "--wait=false")
    rollout_statefulset(runner, "redpanda", rollout_timeout)
    observations = wait_for_kafka_application_recovery(runner, timeout)

    source_id = tracker.source("/post-broker-restart.xml", "post-broker-restart")
    profile_id = tracker.profile(source_id, "post-broker-restart")
    collection_started = time.monotonic()
    run_collection(admin, profile_id, timeout=timeout)
    observations["postBrokerCollectionMs"] = int((time.monotonic() - collection_started) * 1000)
    wait_results(viewer, profile_id, source_id, 1, timeout)
    observations["recoveredResults"] = result_count(viewer, profile_id, source_id)
    return observations


def prometheus_query(port: int, query: str) -> list[dict[str, Any]]:
    url = f"http://127.0.0.1:{port}/api/v1/query?" + urlencode({"query": query})
    with urlopen(url, timeout=5) as response:
        payload = json.loads(response.read())
    if payload.get("status") != "success":
        raise AcceptanceError(f"Prometheus query failed: {payload!r}")
    return payload.get("data", {}).get("result", [])


def verify_observability(prom_port: int, loki_port: int, tempo_port: int, timeout: float) -> None:
    print("==> Resilience observability evidence")

    wait_until(
        "backend restart metric",
        timeout,
        2.0,
        lambda: any(float(item["value"][1]) >= 1 for item in prometheus_query(
            prom_port,
            'max(max_over_time(kube_pod_container_status_restarts_total{namespace="signalharvester",container="signalharvester-backend"}[30m]))',
        )),
    )

    wait_until(
        "backend logs in Loki",
        timeout,
        2.0,
        lambda: loki_has_backend_logs(loki_port),
    )
    wait_until(
        "backend traces in Tempo",
        timeout,
        2.0,
        lambda: tempo_has_backend_traces(tempo_port),
    )
    wait_until(
        "PostgreSQL/JDBC span metrics",
        timeout,
        2.0,
        lambda: any(
            float(item["value"][1]) > 0
            for item in prometheus_query(prom_port, 'sum(traces_spanmetrics_latency_count{db_system="postgresql"})')
        ),
    )


def loki_has_backend_logs(port: int) -> bool:
    now = int(time.time() * 1_000_000_000)
    start = now - 15 * 60 * 1_000_000_000
    url = f"http://127.0.0.1:{port}/loki/api/v1/query_range?" + urlencode(
        {
            "query": '{container="signalharvester-backend"}',
            "start": str(start),
            "end": str(now),
            "limit": "20",
        }
    )
    with urlopen(url, timeout=5) as response:
        payload = json.loads(response.read())
    return bool(payload.get("data", {}).get("result"))


def tempo_has_backend_traces(port: int) -> bool:
    url = f"http://127.0.0.1:{port}/api/search?" + urlencode(
        {"tags": "service.name=signalharvester", "limit": "20"}
    )
    with urlopen(url, timeout=5) as response:
        payload = json.loads(response.read())
    return bool(payload.get("traces"))


def ensure_commands() -> None:
    missing = [name for name in ("kubectl",) if not shutil_which(name)]
    if missing:
        raise AcceptanceError("missing required commands: " + ", ".join(missing))


def shutil_which(name: str) -> str | None:
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        candidate = Path(directory) / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("SIGNALHARVESTER_K8S_NAMESPACE", "signalharvester"))
    parser.add_argument("--backend-port", type=int, default=18081)
    parser.add_argument("--surviving-backend-port", type=int, default=18082)
    parser.add_argument("--prometheus-port", type=int, default=19091)
    parser.add_argument("--loki-port", type=int, default=13101)
    parser.add_argument("--tempo-port", type=int, default=13201)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=120.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--evidence-output", type=Path, default=DEFAULT_EVIDENCE_REPORT)
    parser.add_argument("--skip-preflight", action="store_true")
    parser.add_argument(
        "--repair-baseline-only",
        action="store_true",
        help="restore the known PostgreSQL acceptance baseline and exit without running scenarios",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    runner = CommandRunner(args.namespace)
    if args.repair_baseline_only:
        try:
            ensure_commands()
            repaired = restore_postgres_acceptance_baseline(runner, args.rollout_timeout)
            state = "repaired" if repaired else "already configured"
            print(f"PostgreSQL resilience acceptance baseline is {state}.")
            return 0
        except (AcceptanceError, subprocess.TimeoutExpired, OSError, ValueError, json.JSONDecodeError) as failure:
            print(f"ERROR: failed to restore resilience acceptance baseline: {failure}", file=sys.stderr)
            return 1

    evidence_output = args.evidence_output if args.evidence_output.is_absolute() else ROOT / args.evidence_output
    evidence_dataset = scenario_evidence.ScenarioEvidenceDataset(
        "kubernetes-resilience-acceptance",
        {
            "namespace": args.namespace,
            "explicitHealthSnapshotsPersisted": True,
            "sharedHealthHistoryAcrossScenarios": True,
        },
    )
    env_guard: BackendEnvironmentGuard | None = None
    tracker: ResourceTracker | None = None
    fixture_applied = False
    try:
        ensure_commands()
        restore_postgres_acceptance_baseline(runner, args.rollout_timeout)
        if not args.skip_preflight:
            run_preflight(runner, args.rollout_timeout)
        replica_count = backend_replicas(runner)
        if replica_count < 2:
            raise AcceptanceError("resilience acceptance requires at least two backend replicas")

        print("==> Deploy deterministic in-cluster source fixture")
        cluster_ip = deploy_fixture(runner, args.rollout_timeout)
        fixture_applied = True
        env_guard = BackendEnvironmentGuard(runner, args.rollout_timeout)
        existing_allowed = env_guard.effective_value("SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS") or ""
        cidr = fixture_cidr(cluster_ip)
        combined = ",".join(value for value in (existing_allowed, cidr) if value)
        env_guard.set_many({
            "SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS": combined,
            "SIGNALHARVESTER_JWT_TTL_SECONDS": "3600",
            "SIGNALHARVESTER_AUTH_COOKIE_MAX_AGE": "60m",
            "SIGNALHARVESTER_DB_CONNECTION_TIMEOUT_MS": "5000",
            "SIGNALHARVESTER_DB_CONNECT_TIMEOUT_SECONDS": "3",
            "SIGNALHARVESTER_DB_SOCKET_TIMEOUT_SECONDS": "10",
            "SIGNALHARVESTER_ANALYSIS_KAFKA_MAX_ATTEMPTS": "3",
            "SIGNALHARVESTER_ANALYSIS_KAFKA_RETRY_BACKOFF": "250ms",
        })

        with (
            port_forward(runner, "service/signalharvester-backend", args.backend_port, 8080),
            port_forward(runner, "service/prometheus", args.prometheus_port, 9090),
            port_forward(runner, "service/loki", args.loki_port, 3100),
            port_forward(runner, "service/tempo", args.tempo_port, 3200),
        ):
            try:
                base_url = f"http://127.0.0.1:{args.backend_port}"
                wait_until(
                    "backend readiness through port-forward",
                    30,
                    0.5,
                    lambda: public_get(base_url + "/health/readiness")[0] == 200,
                )

                bootstrap_username = read_runtime_secret(runner, "SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME")
                bootstrap_password = read_runtime_secret(runner, "SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD")
                admin = ApiSession(base_url, args.http_timeout)
                admin.login(bootstrap_username, bootstrap_password)
                env_guard.set_post_rollout_probe(
                    lambda: wait_for_backend_rollout_api_recovery(admin, args.scenario_timeout)
                )
                tracker = ResourceTracker(admin)
                viewer = create_viewer(admin, tracker, base_url, args.http_timeout)
                verify_authorization(viewer)

                with evidence_dataset.scenario(
                    "resilience-normal-operation",
                    "NORMAL_OPERATION",
                    fault_injected=False,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_REMAIN_HEALTHY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_NO_ATTENTION,
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    print("==> Baseline collection and consumer-group establishment")
                    baseline_source = tracker.source("/baseline.xml", "baseline")
                    baseline_profile = tracker.profile(baseline_source, "baseline")
                    run_collection(admin, baseline_profile)
                    wait_results(viewer, baseline_profile, baseline_source, 1, args.scenario_timeout)
                    wait_until(
                        "Analysis consumer group",
                        args.scenario_timeout,
                        1.0,
                        lambda: analysis_group_lag(runner) == 0,
                    )
                    scenario.add_measurement(
                        "analysisConsumerLag", 0, phase="AFTER", unit="records", source="rpk"
                    )
                    record_scenario_end(admin, scenario)
                    raw_record = capture_latest_raw_record(runner)

                with evidence_dataset.scenario(
                    "resilience-backend-pod-restart",
                    "POD_RESTART",
                    fault_injected=True,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
                    affected_subsystems=("BACKEND",),
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    record_scenario_fault_boundary(admin, scenario, "FAULT_START")
                    restart_observations = restart_backend_container(
                        runner, viewer, args.scenario_timeout, args.surviving_backend_port
                    )
                    record_scenario_fault_boundary(admin, scenario, "FAULT_END")
                    scenario.add_measurement(
                        "backendPodRestartCountBefore",
                        restart_observations["restartCountBefore"],
                        phase="BEFORE",
                        unit="restarts",
                        source="kubernetes",
                    )
                    scenario.add_measurement(
                        "backendPodRestartCountAfter",
                        restart_observations["restartCountAfter"],
                        phase="AFTER",
                        unit="restarts",
                        source="kubernetes",
                    )
                    scenario.add_measurement(
                        "survivingReplicaHttpStatus",
                        restart_observations["survivingReplicaHttpStatus"],
                        phase="FAULT",
                        source="http",
                    )
                    scenario.add_measurement(
                        "restartedReplicaHttpStatus",
                        restart_observations["restartedReplicaHttpStatus"],
                        phase="AFTER",
                        source="http",
                    )
                    record_scenario_end(admin, scenario)

                with evidence_dataset.scenario(
                    "resilience-slow-external-source",
                    "SLOW_EXTERNAL_SOURCE",
                    fault_injected=True,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
                    affected_subsystems=("COLLECTION", "EXTERNAL_SOURCE"),
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    record_scenario_fault_boundary(admin, scenario, "FAULT_START")
                    slow_observations = verify_slow_source_availability(
                        admin, viewer, tracker, args.scenario_timeout, scenario
                    )
                    record_scenario_fault_boundary(admin, scenario, "FAULT_END")
                    scenario.add_measurement(
                        "readinessHttpStatus",
                        slow_observations["readinessHttpStatus"],
                        phase="FAULT",
                        source="http",
                    )
                    scenario.add_measurement(
                        "viewerHttpStatus",
                        slow_observations["viewerHttpStatus"],
                        phase="FAULT",
                        source="http",
                    )
                    record_scenario_end(admin, scenario)

                with evidence_dataset.scenario(
                    "resilience-postgresql-outage",
                    "POSTGRESQL_OUTAGE",
                    fault_injected=True,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
                    affected_subsystems=("POSTGRESQL", "ANALYSIS"),
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    record_scenario_fault_boundary(admin, scenario, "FAULT_START")
                    verify_retry_dlq_and_postgres_recovery(
                        runner,
                        admin,
                        viewer,
                        tracker,
                        raw_record,
                        args.scenario_timeout,
                        args.rollout_timeout,
                        scenario,
                    )
                    record_scenario_fault_boundary(admin, scenario, "FAULT_END")
                    scenario.add_limitation(
                        "No Health Snapshot is persisted while PostgreSQL is intentionally unavailable; "
                        "the durable start marker and post-recovery snapshot bound the outage window."
                    )
                    record_scenario_end(admin, scenario)

                with evidence_dataset.scenario(
                    "resilience-kafka-lag",
                    "KAFKA_LAG",
                    fault_injected=True,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
                    affected_subsystems=("KAFKA", "ANALYSIS"),
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    record_scenario_fault_boundary(admin, scenario, "FAULT_START")
                    verify_analysis_lag_recovery(
                        runner, env_guard, admin, viewer, tracker, args.scenario_timeout, scenario
                    )
                    record_scenario_fault_boundary(admin, scenario, "FAULT_END")
                    record_scenario_end(admin, scenario)

                with evidence_dataset.scenario(
                    "resilience-analysis-outbox-backlog",
                    "OUTBOX_BACKLOG",
                    fault_injected=True,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
                    affected_subsystems=("ANALYSIS_OUTBOX", "RESULTS"),
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    record_scenario_fault_boundary(admin, scenario, "FAULT_START")
                    verify_outbox_recovery(
                        runner, env_guard, admin, viewer, tracker, args.scenario_timeout, scenario
                    )
                    record_scenario_fault_boundary(admin, scenario, "FAULT_END")
                    record_scenario_end(admin, scenario)

                verify_scheduler_lease(runner, admin, tracker, args.scenario_timeout)

                with evidence_dataset.scenario(
                    "resilience-kafka-broker-restart",
                    "KAFKA_BROKER_RESTART",
                    fault_injected=True,
                    health_expectation=scenario_evidence.HEALTH_EXPECTATION_DESCRIPTIVE_ONLY,
                    alert_expectation=scenario_evidence.ALERT_EXPECTATION_DESCRIPTIVE_ONLY,
                    affected_subsystems=("KAFKA",),
                ) as scenario:
                    record_scenario_start(admin, scenario)
                    record_scenario_fault_boundary(admin, scenario, "FAULT_START")
                    broker_observations = restart_redpanda_and_verify(
                        runner,
                        admin,
                        viewer,
                        tracker,
                        args.scenario_timeout,
                        args.rollout_timeout,
                    )
                    record_scenario_fault_boundary(admin, scenario, "FAULT_END")
                    scenario.add_measurement(
                        "kafkaApplicationRecoveryMs",
                        broker_observations["kafkaApplicationRecoveryMs"],
                        phase="AFTER",
                        unit="ms",
                        source="rpk-consumer-groups",
                    )
                    scenario.add_measurement(
                        "postBrokerCollectionMs",
                        broker_observations["postBrokerCollectionMs"],
                        phase="AFTER",
                        unit="ms",
                        source="collection-api",
                    )
                    for measurement_name in (
                        "analysisConsumerMembersAfterBrokerRestart",
                        "resultsConsumerMembersAfterBrokerRestart",
                        "eventObservationConsumerMembersAfterBrokerRestart",
                    ):
                        scenario.add_measurement(
                            measurement_name,
                            broker_observations[measurement_name],
                            phase="AFTER",
                            unit="members",
                            source="rpk-consumer-groups",
                        )
                    scenario.add_measurement(
                        "analysisConsumerLagAfterBrokerRestart",
                        broker_observations["analysisConsumerLagAfterBrokerRestart"],
                        phase="AFTER",
                        unit="records",
                        source="rpk-consumer-groups",
                    )
                    scenario.add_measurement(
                        "recoveredResults",
                        broker_observations["recoveredResults"],
                        phase="AFTER",
                        unit="results",
                        source="results-api",
                    )
                    record_scenario_end(admin, scenario)

                verify_observability(
                    args.prometheus_port,
                    args.loki_port,
                    args.tempo_port,
                    args.scenario_timeout,
                )
            finally:
                if tracker is not None:
                    tracker.cleanup()
                    tracker = None

        evidence_dataset.write(evidence_output)
        restore_postgres_acceptance_baseline(runner, args.rollout_timeout)
        print(f"    operational scenario evidence: {evidence_output}")
        print("All SignalHarvester Kubernetes resilience scenarios passed.")
        return 0
    except (
        AcceptanceError,
        subprocess.TimeoutExpired,
        OSError,
        ValueError,
        json.JSONDecodeError,
        scenario_evidence.ScenarioEvidenceError,
    ) as failure:
        try:
            evidence_dataset.write(evidence_output)
            print(f"    partial operational scenario evidence: {evidence_output}", file=sys.stderr)
        except OSError as write_failure:
            print(f"WARNING: failed to write partial scenario evidence: {write_failure}", file=sys.stderr)
        try:
            restore_postgres_acceptance_baseline(runner, args.rollout_timeout)
        except (AcceptanceError, subprocess.TimeoutExpired, OSError, ValueError, json.JSONDecodeError) as cleanup_failure:
            print(
                f"ERROR: failed to restore PostgreSQL resilience acceptance baseline: {cleanup_failure}",
                file=sys.stderr,
            )
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    finally:
        if tracker is not None:
            tracker.cleanup()
        if env_guard is not None:
            env_guard.restore()
        if fixture_applied:
            runner.kubectl("delete", "-f", str(FIXTURE_MANIFEST), "--ignore-not-found=true", check=False)


if __name__ == "__main__":
    raise SystemExit(main())
