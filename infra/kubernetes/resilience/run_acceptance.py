#!/usr/bin/env python3
"""Run controlled resilience acceptance against the local SignalHarvester Kubernetes stack."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import contextlib
import http.cookiejar
import ipaddress
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
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
RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1"
ANALYSIS_DLQ_TOPIC = "signalharvester.analysis.raw-item-dead-letter.v1"
ANALYSIS_GROUP = "signalharvester-analysis-v1"


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


def first_ready_backend_pod(runner: CommandRunner) -> str:
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
    for item in pods.get("items", []):
        conditions = item.get("status", {}).get("conditions", [])
        if any(condition.get("type") == "Ready" and condition.get("status") == "True" for condition in conditions):
            return item["metadata"]["name"]
    raise AcceptanceError("no ready backend pod found")


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


@contextlib.contextmanager
def port_forward(runner: CommandRunner, resource: str, local_port: int, remote_port: int):
    log = tempfile.NamedTemporaryFile(prefix="signalharvester-port-forward-", suffix=".log", delete=False)
    log_path = Path(log.name)
    log.close()
    stream = log_path.open("w")
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
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise AcceptanceError(f"port-forward {resource} exited early: {log_path.read_text()}")
            try:
                with urlopen(f"http://127.0.0.1:{local_port}/", timeout=0.5):
                    pass
            except Exception:  # noqa: BLE001 - endpoint may return HTTP error while tunnel is healthy
                pass
            text = log_path.read_text()
            if "Forwarding from" in text:
                break
            time.sleep(0.2)
        else:
            raise AcceptanceError(f"port-forward {resource} did not become ready: {log_path.read_text()}")
        yield
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
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


def restart_backend_container(runner: CommandRunner, viewer: ApiSession, timeout: float) -> None:
    print("==> Stateless JWT and backend container restart")
    pod = first_ready_backend_pod(runner)
    before = backend_pod_restart_count(runner, pod)
    runner.namespaced("exec", pod, "--", "kill", "1", check=False)

    wait_until(
        f"backend pod {pod} restart count to increase",
        timeout,
        1.0,
        lambda: backend_pod_restart_count(runner, pod) > before,
    )

    wait_until(
        "authenticated Results access through surviving backend replica",
        timeout,
        0.5,
        lambda: viewer.request("GET", "/api/v1/results?limit=1")[0] == 200,
    )
    wait_until(
        f"backend pod {pod} to become ready after restart",
        timeout,
        1.0,
        lambda: backend_pod_ready(runner, pod),
    )
    status, _ = viewer.request("GET", "/api/v1/results?limit=1")
    if status != 200:
        raise AcceptanceError(f"existing VIEWER JWT failed after backend restart: HTTP {status}")


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
) -> None:
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
        future.result(timeout=timeout)
    wait_results(viewer, profile_id, source_id, 1, timeout)


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


def verify_retry_dlq_and_postgres_recovery(
    runner: CommandRunner,
    admin: ApiSession,
    viewer: ApiSession,
    tracker: ResourceTracker,
    raw_record: tuple[str, str],
    timeout: float,
    rollout_timeout: str,
) -> None:
    print("==> PostgreSQL outage, bounded Analysis retry, DLQ, and recovery")
    before = rpk_topic_record_count(runner, ANALYSIS_DLQ_TOPIC)
    key, payload_hex = raw_record

    runner.namespaced("scale", "statefulset/postgres", "--replicas=0")
    wait_until(
        "PostgreSQL pod termination",
        timeout,
        1.0,
        lambda: runner.namespaced("get", "pods", "-l", "app.kubernetes.io/name=postgres", "-o", "name").stdout.strip() == "",
    )

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
        wait_until(
            "Analysis retry exhaustion log",
            timeout,
            1.0,
            lambda: re.search(
                r"Analysis Kafka record .* moved to dead letter after 3 attempt\(s\)",
                backend_logs(runner),
            ) is not None,
        )
    finally:
        runner.namespaced("scale", "statefulset/postgres", "--replicas=1", check=False)
        rollout_statefulset(runner, "postgres", rollout_timeout)
        rollout_backend(runner, rollout_timeout)

    source_id = tracker.source("/post-db-recovery.xml", "post-db-recovery")
    profile_id = tracker.profile(source_id, "post-db-recovery")
    run_collection(admin, profile_id)
    wait_results(viewer, profile_id, source_id, 1, timeout)


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
) -> None:
    print("==> Kafka lag generation and Analysis recovery")
    source_id = tracker.source("/lag.xml", "lag")
    profile_id = tracker.profile(source_id, "lag")

    env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", "false")
    run = run_collection(admin, profile_id)
    if int(run.get("publishedCount", 0)) < 4:
        raise AcceptanceError(f"lag fixture did not publish four raw items: {run!r}")
    wait_until("positive Analysis consumer lag", timeout, 1.0, lambda: analysis_group_lag(runner) > 0)
    if result_count(viewer, profile_id, source_id) != 0:
        raise AcceptanceError("Results appeared while Analysis consumer was disabled")

    env_guard.set("SIGNALHARVESTER_ANALYSIS_ENABLED", None)
    wait_until("Analysis consumer lag to drain", timeout, 1.0, lambda: analysis_group_lag(runner) == 0)
    wait_results(viewer, profile_id, source_id, 4, timeout)


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
) -> None:
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

    env_guard.set("SIGNALHARVESTER_ANALYSIS_OUTBOX_ENABLED", None)
    wait_results(viewer, profile_id, source_id, 3, timeout)
    wait_until(
        "outbox pending count to return to baseline",
        timeout,
        1.0,
        lambda: pending_outbox_count(runner) <= baseline_pending,
    )


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
) -> None:
    print("==> Redpanda restart recovery")
    runner.namespaced("delete", "pod", "redpanda-0", "--wait=false")
    rollout_statefulset(runner, "redpanda", rollout_timeout)
    source_id = tracker.source("/post-broker-restart.xml", "post-broker-restart")
    profile_id = tracker.profile(source_id, "post-broker-restart")
    run_collection(admin, profile_id)
    wait_results(viewer, profile_id, source_id, 1, timeout)


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
    parser.add_argument("--prometheus-port", type=int, default=19091)
    parser.add_argument("--loki-port", type=int, default=13101)
    parser.add_argument("--tempo-port", type=int, default=13201)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--scenario-timeout", type=float, default=120.0)
    parser.add_argument("--rollout-timeout", default="240s")
    parser.add_argument("--skip-preflight", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    runner = CommandRunner(args.namespace)
    env_guard: BackendEnvironmentGuard | None = None
    tracker: ResourceTracker | None = None
    fixture_applied = False
    try:
        ensure_commands()
        if not args.skip_preflight:
            run_preflight(runner, args.rollout_timeout)
        if backend_replicas(runner) < 2:
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
                tracker = ResourceTracker(admin)
                viewer = create_viewer(admin, tracker, base_url, args.http_timeout)
                verify_authorization(viewer)

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
                raw_record = capture_latest_raw_record(runner)

                restart_backend_container(runner, viewer, args.scenario_timeout)
                verify_slow_source_availability(admin, viewer, tracker, args.scenario_timeout)
                verify_retry_dlq_and_postgres_recovery(
                    runner,
                    admin,
                    viewer,
                    tracker,
                    raw_record,
                    args.scenario_timeout,
                    args.rollout_timeout,
                )
                verify_analysis_lag_recovery(
                    runner,
                    env_guard,
                    admin,
                    viewer,
                    tracker,
                    args.scenario_timeout,
                )
                verify_outbox_recovery(
                    runner,
                    env_guard,
                    admin,
                    viewer,
                    tracker,
                    args.scenario_timeout,
                )
                verify_scheduler_lease(runner, admin, tracker, args.scenario_timeout)
                restart_redpanda_and_verify(
                    runner,
                    admin,
                    viewer,
                    tracker,
                    args.scenario_timeout,
                    args.rollout_timeout,
                )
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

        print("All SignalHarvester Kubernetes resilience scenarios passed.")
        return 0
    except (AcceptanceError, subprocess.TimeoutExpired, OSError, ValueError, json.JSONDecodeError) as failure:
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
