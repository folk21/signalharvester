#!/bin/sh
set -eu

NAMESPACE=${SIGNALHARVESTER_K8S_NAMESPACE:-signalharvester}
BOOTSTRAP_USERNAME=${SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME:-admin}

command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl is required" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required" >&2; exit 1; }

random_secret() {
  python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
}

kubectl apply -f infra/kubernetes/namespace.yaml >/dev/null

RUNTIME_CREATED=false
if kubectl -n "$NAMESPACE" get secret signalharvester-runtime-secrets >/dev/null 2>&1; then
  echo "Runtime Secret already exists; leaving database and application credentials unchanged."
else
  DB_USERNAME=${SIGNALHARVESTER_DB_USERNAME:-signalharvester}
  DB_PASSWORD=${SIGNALHARVESTER_DB_PASSWORD:-$(random_secret)}
  JWT_SECRET=${SIGNALHARVESTER_JWT_SECRET:-$(random_secret)}
  CSRF_SECRET=${SIGNALHARVESTER_CSRF_SECRET:-$(random_secret)}
  BOOTSTRAP_PASSWORD=${SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD:-$(random_secret)}

  kubectl -n "$NAMESPACE" create secret generic signalharvester-runtime-secrets \
    --from-literal=SIGNALHARVESTER_DB_USERNAME="$DB_USERNAME" \
    --from-literal=SIGNALHARVESTER_DB_PASSWORD="$DB_PASSWORD" \
    --from-literal=SIGNALHARVESTER_JWT_SECRET="$JWT_SECRET" \
    --from-literal=SIGNALHARVESTER_CSRF_SECRET="$CSRF_SECRET" \
    --from-literal=SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME="$BOOTSTRAP_USERNAME" \
    --from-literal=SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD="$BOOTSTRAP_PASSWORD" >/dev/null
  RUNTIME_CREATED=true
fi

OBSERVABILITY_CREATED=false
if kubectl -n "$NAMESPACE" get secret signalharvester-observability-secrets >/dev/null 2>&1; then
  echo "Observability Secret already exists; leaving Grafana credentials unchanged."
else
  GRAFANA_USERNAME=${GRAFANA_ADMIN_USER:-admin}
  GRAFANA_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-$(random_secret)}
  kubectl -n "$NAMESPACE" create secret generic signalharvester-observability-secrets \
    --from-literal=GRAFANA_ADMIN_USER="$GRAFANA_USERNAME" \
    --from-literal=GRAFANA_ADMIN_PASSWORD="$GRAFANA_PASSWORD" >/dev/null
  OBSERVABILITY_CREATED=true
fi

if [ "$RUNTIME_CREATED" = true ]; then
  cat <<EOF_OUT
Created runtime credentials in namespace $NAMESPACE.
Bootstrap ADMIN username: $BOOTSTRAP_USERNAME
Bootstrap ADMIN password: $BOOTSTRAP_PASSWORD
Store these generated local credentials securely; they are not written to repository files.
EOF_OUT
fi

if [ "$OBSERVABILITY_CREATED" = true ]; then
  cat <<EOF_OUT
Created Grafana credentials in namespace $NAMESPACE.
Grafana username: $GRAFANA_USERNAME
Grafana password: $GRAFANA_PASSWORD
Store these generated local credentials securely; they are not written to repository files.
EOF_OUT
fi

if [ "$RUNTIME_CREATED" = false ] && [ "$OBSERVABILITY_CREATED" = false ]; then
  echo "No Secrets changed. Delete/recreate the local namespace or rotate credentials explicitly when a coordinated credential reset is intended."
fi
