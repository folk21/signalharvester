#!/bin/sh
set -eu

NAMESPACE=${SIGNALHARVESTER_K8S_NAMESPACE:-signalharvester}
TIMEOUT=${SIGNALHARVESTER_K8S_VERIFY_TIMEOUT:-240s}

command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl is required" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required" >&2; exit 1; }

check_cluster_nodes_ready() {
  node_status=$(kubectl get nodes -o custom-columns=NAME:.metadata.name,READY:'.status.conditions[?(@.type=="Ready")].status' --no-headers)
  not_ready=$(printf '%s\n' "$node_status" | awk 'NF >= 2 && $2 != "True" { print $1 }')
  [ -z "$not_ready" ] && return 0

  echo "ERROR: Kubernetes cluster has nodes that are not Ready; workload readiness would be unreliable." >&2
  kubectl get nodes -o wide >&2 || true
  echo "NotReady nodes:" >&2
  printf '%s\n' "$not_ready" >&2
  echo "Recent cluster events:" >&2
  kubectl get events -A --sort-by=.lastTimestamp 2>/dev/null | tail -n 60 >&2 || true
  return 1
}

echo "==> Checking Kubernetes node readiness"
check_cluster_nodes_ready

diagnose_workload() {
  workload=$1
  workload_name=${workload#*/}
  echo "ERROR: workload did not become ready: $workload" >&2
  kubectl -n "$NAMESPACE" get "$workload" -o wide >&2 || true
  kubectl -n "$NAMESPACE" get pods -l "app.kubernetes.io/name=$workload_name" -o wide >&2 || true
  kubectl -n "$NAMESPACE" describe "$workload" >&2 || true
  echo "Recent namespace events:" >&2
  kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp 2>/dev/null | tail -n 60 >&2 || true
}

wait_for_rollout() {
  workload=$1
  echo "==> Waiting for $workload"
  if ! kubectl -n "$NAMESPACE" rollout status "$workload" --timeout="$TIMEOUT"; then
    diagnose_workload "$workload"
    return 1
  fi
}

for workload in \
  statefulset/postgres \
  statefulset/redpanda \
  deployment/tempo \
  deployment/loki \
  deployment/prometheus \
  deployment/alloy \
  deployment/kube-state-metrics \
  deployment/grafana \
  deployment/signalharvester-backend; do
  wait_for_rollout "$workload"
done

echo "==> Waiting for job/redpanda-topic-provisioner"
if ! kubectl -n "$NAMESPACE" wait --for=condition=complete job/redpanda-topic-provisioner --timeout="$TIMEOUT"; then
  echo "ERROR: topic provisioner did not complete" >&2
  kubectl -n "$NAMESPACE" get job/redpanda-topic-provisioner -o wide >&2 || true
  kubectl -n "$NAMESPACE" get pods -l job-name=redpanda-topic-provisioner -o wide >&2 || true
  kubectl -n "$NAMESPACE" describe job/redpanda-topic-provisioner >&2 || true
  echo "Recent namespace events:" >&2
  kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp 2>/dev/null | tail -n 60 >&2 || true
  exit 1
fi

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/signalharvester-k8s-verify.XXXXXX")
BACKEND_LOG="$TMP_DIR/backend-port-forward.log"
PROM_LOG="$TMP_DIR/prometheus-port-forward.log"
GRAFANA_LOG="$TMP_DIR/grafana-port-forward.log"

cleanup() {
  for pid in ${BACKEND_PID:-} ${PROM_PID:-} ${GRAFANA_PID:-}; do
    [ -n "$pid" ] && kill "$pid" >/dev/null 2>&1 || true
  done
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

kubectl -n "$NAMESPACE" port-forward service/signalharvester-backend 18080:8080 >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
kubectl -n "$NAMESPACE" port-forward service/prometheus 19090:9090 >"$PROM_LOG" 2>&1 &
PROM_PID=$!
kubectl -n "$NAMESPACE" port-forward service/grafana 13000:3000 >"$GRAFANA_LOG" 2>&1 &
GRAFANA_PID=$!

wait_http() {
  url=$1
  attempts=0
  until curl -fsS "$url" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    [ "$attempts" -lt 30 ] || { echo "ERROR: endpoint did not become ready: $url" >&2; return 1; }
    sleep 1
  done
}

wait_http http://127.0.0.1:18080/health/readiness
wait_http http://127.0.0.1:19090/-/ready
wait_http http://127.0.0.1:13000/api/health

curl -fsS http://127.0.0.1:18080/prometheus | grep -q 'signalharvester_'

query_equals() {
  query=$1
  expected=$2
  curl -fsSG --data-urlencode "query=$query" http://127.0.0.1:19090/api/v1/query | grep -Fq "\"$expected\""
}

query_equals 'count(up{job="signalharvester-backend"} == 1)' 2
query_equals 'min(up{job="redpanda"})' 1
query_equals 'min(up{job="kube-state-metrics"})' 1

echo "Backend readiness, Prometheus scrape targets, and Grafana health are available."
echo "For traces/logs, open Grafana at http://127.0.0.1:13000 and inspect the provisioned Tempo/Loki data sources after generating application traffic."
