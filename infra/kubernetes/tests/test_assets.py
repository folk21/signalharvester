import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
K8S = ROOT / "infra" / "kubernetes"


class KubernetesAssetsTest(unittest.TestCase):
    def test_root_kustomization_references_existing_resources(self):
        text = (K8S / "kustomization.yaml").read_text()
        resources = []
        in_resources = False
        for line in text.splitlines():
            if line == "resources:":
                in_resources = True
                continue
            if in_resources and line.startswith("  - "):
                resources.append(line[4:])
                continue
            if in_resources and line and not line.startswith(" "):
                break
        self.assertTrue(resources)
        for resource in resources:
            self.assertTrue((K8S / resource).is_file(), resource)

    def test_backend_uses_security_environment_probes_resources_and_external_secrets(self):
        deployment = (K8S / "backend.yaml").read_text()
        kustomization = (K8S / "kustomization.yaml").read_text()
        self.assertIn("MICRONAUT_ENVIRONMENTS=security", kustomization)
        self.assertIn("SIGNALHARVESTER_OTEL_TRACES_EXPORTER=otlp", kustomization)
        self.assertIn("http://tempo:4317", kustomization)
        self.assertIn("signalharvester-runtime-secrets", deployment)
        self.assertIn("/health/liveness", deployment)
        self.assertIn("/health/readiness", deployment)
        self.assertIn("resources:", deployment)
        self.assertNotRegex(deployment, r"SIGNALHARVESTER_(JWT|CSRF)_SECRET\s*:")

    def test_images_are_versioned_and_not_latest(self):
        manifests = "\n".join(path.read_text() for path in K8S.rglob("*.yaml"))
        images = re.findall(r"^\s*image:\s*([^\s]+)", manifests, flags=re.MULTILINE)
        self.assertTrue(images)
        for image in images:
            self.assertNotIn(":latest", image)
            self.assertIn(":", image, image)

    def test_observability_stack_is_wired(self):
        prometheus = (K8S / "observability" / "prometheus.yml").read_text()
        tempo = (K8S / "observability" / "tempo-config.yaml").read_text()
        alloy = (K8S / "observability" / "alloy-config.alloy").read_text()
        datasources = (K8S / "observability" / "grafana-datasources.yaml").read_text()
        self.assertIn("signalharvester-backend-metrics.signalharvester.svc.cluster.local", prometheus)
        self.assertIn("redpanda:9644", prometheus)
        self.assertIn("kube-state-metrics:8080", prometheus)
        redpanda = (K8S / "redpanda.yaml").read_text()
        self.assertIn("redpanda.auto_create_topics_enabled=false", redpanda)
        self.assertIn("endpoint: 0.0.0.0:4317", tempo)
        self.assertIn("span-metrics", tempo)
        self.assertIn("http://prometheus:9090/api/v1/write", tempo)
        self.assertIn('loki.source.kubernetes "signalharvester_pods"', alloy)
        self.assertIn("http://loki:3100/loki/api/v1/push", alloy)
        for name in ("Prometheus", "Loki", "Tempo"):
            self.assertIn(f"name: {name}", datasources)

    def test_dashboard_is_valid_and_covers_required_operational_dimensions(self):
        dashboard = json.loads((K8S / "observability" / "signalharvester-overview.json").read_text())
        titles = {panel["title"] for panel in dashboard["panels"]}
        expected = {
            "Backend replicas available",
            "Backend container restarts",
            "Kafka consumer lag",
            "JVM heap used",
            "HTTP request rate",
            "HTTP average latency",
            "Collection runs",
            "External source fetches",
            "Analysis items",
            "Analysis average duration",
            "PostgreSQL span average latency",
            "Backend logs",
        }
        self.assertTrue(expected.issubset(titles))

    def test_secret_generation_is_external_to_manifests(self):
        manifests = "\n".join(path.read_text() for path in K8S.rglob("*.yaml"))
        self.assertNotIn("kind: Secret", manifests)
        script = (K8S / "create-local-secrets.sh").read_text()
        self.assertIn("secrets.token_urlsafe", script)
        self.assertIn("get secret signalharvester-runtime-secrets", script)
        self.assertIn("get secret signalharvester-observability-secrets", script)
        self.assertNotIn("kubectl apply -f -", script)

    def test_frontend_boundary_remains_separate_from_backend_artifact(self):
        frontend = (K8S / "frontend" / "frontend.yaml").read_text()
        root = (K8S / "kustomization.yaml").read_text()
        self.assertIn("signalharvester-web:local", frontend)
        self.assertIn("containerPort: 8080", frontend)
        self.assertNotIn("frontend/frontend.yaml", root)

    def test_observability_persistent_workloads_define_volume_group_access(self):
        expected = {
            "prometheus.yaml": "fsGroup: 65534",
            "tempo.yaml": "fsGroup: 10001",
            "loki.yaml": "fsGroup: 10001",
        }
        for filename, fs_group in expected.items():
            manifest = (K8S / "observability" / filename).read_text()
            self.assertIn(fs_group, manifest, filename)

    def test_spec_lifecycle_points_to_system_resilience_stage(self):
        active = ROOT / "docs" / "specs" / "active" / "subspecs"
        archive = ROOT / "docs" / "specs" / "archive" / "subspecs"
        umbrella = (ROOT / "docs" / "specs" / "active" / "spec-signal-harvester-platform.md").read_text()
        self.assertFalse((active / "backend-external-source-access-security.md").exists())
        self.assertTrue((archive / "backend-external-source-access-security.md").exists())
        self.assertTrue((active / "backend-kubernetes-observability-deployment.md").exists())
        self.assertTrue((active / "backend-system-resilience-acceptance.md").exists())
        self.assertIn("current_focus: subspecs/backend-system-resilience-acceptance.md", umbrella)

    def test_backend_dockerfile_builds_distribution_and_runs_non_root(self):
        dockerfile = (ROOT / "app" / "Dockerfile").read_text()
        dockerignore = (ROOT / ".dockerignore").read_text()
        self.assertIn(":app:installDist", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)
        self.assertIn('ENTRYPOINT ["bin/app"]', dockerfile)
        self.assertIn("!gradle/wrapper/gradle-wrapper.jar", dockerignore)


if __name__ == "__main__":
    unittest.main()
