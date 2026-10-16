"""Deterministic regression coverage for the development-agent pipeline foundation."""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "agent_pipeline.py"
SPEC = importlib.util.spec_from_file_location("signalharvester_agent_pipeline", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
agent_pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent_pipeline)


class AgentPipelinePolicyTest(unittest.TestCase):
    """Protects fail-closed policy, review, and risk-state semantics."""

    def setUp(self) -> None:
        self.policy = {
            "schemaVersion": 1,
            "requiredRepositoryFiles": ["README.md", "AGENTS.md", "docs/specs/README.md"],
            "requiredChecks": [{"name": "canonical", "command": [sys.executable, "-c", "print('ok')"]}],
            "independentReview": {"required": True, "blockingSeverities": ["HIGH", "CRITICAL"]},
            "mainReview": {"requiredBeforeAcceptance": True},
            "riskRules": [
                {"level": "HIGH", "patterns": ["^modules/security/", "/db/migration/"]},
                {"level": "MODERATE", "patterns": ["^modules/", "^run_checks\\.sh$"]},
                {"level": "LOW", "patterns": ["^docs/", "^tools/"]},
            ],
        }

    # Protects the transition from verification through independent review to main-model review.
    def test_state_machine_requires_checks_and_independent_review(self) -> None:
        checks = {"schemaVersion": 1, "checks": []}
        state, _ = agent_pipeline.derive_state(self.policy, checks, None)
        self.assertEqual(agent_pipeline.STATE_VERIFYING, state)

        checks["checks"] = [{"name": "canonical", "status": "PASS"}]
        state, _ = agent_pipeline.derive_state(self.policy, checks, None)
        self.assertEqual(agent_pipeline.STATE_REVIEW_REQUIRED, state)

        checks["checks"].append({"name": "focused", "status": "FAIL"})
        state, _ = agent_pipeline.derive_state(self.policy, checks, None)
        self.assertEqual(agent_pipeline.STATE_BLOCKED, state)
        checks["checks"].append({"name": "focused", "status": "PASS"})

        review = {"verdict": "PASS", "findings": []}
        state, _ = agent_pipeline.derive_state(self.policy, checks, review)
        self.assertEqual(agent_pipeline.STATE_READY_FOR_MAIN_REVIEW, state)

    # Protects fail-closed blocking when review finds unresolved high-severity defects.
    def test_open_high_review_finding_blocks_run(self) -> None:
        checks = {"checks": [{"name": "canonical", "status": "PASS"}]}
        review = {
            "verdict": "PASS",
            "findings": [{"id": "REV-1", "severity": "HIGH", "status": "OPEN"}],
        }
        state, reasons = agent_pipeline.derive_state(self.policy, checks, review)
        self.assertEqual(agent_pipeline.STATE_BLOCKED, state)
        self.assertIn("unresolved blocking findings", reasons[0])

    # Protects explicit escalation categories even when the finding severity is below a blocking threshold.
    def test_open_escalation_category_requires_main_review(self) -> None:
        self.policy["escalationCategories"] = ["SECURITY"]
        checks = {"checks": [{"name": "canonical", "status": "PASS"}]}
        review = {
            "verdict": "PASS",
            "findings": [
                {"id": "REV-2", "severity": "MEDIUM", "category": "SECURITY", "status": "OPEN"}
            ],
        }
        state, reasons = agent_pipeline.derive_state(self.policy, checks, review)
        self.assertEqual(agent_pipeline.STATE_NEEDS_MAIN_REVIEW, state)
        self.assertIn("escalation-category", reasons[0])

    # Protects fail-closed ordering when direct worker execution is required.
    def test_state_machine_requires_worker_before_verification(self) -> None:
        self.policy["workerExecution"] = {"required": True}
        checks = {"schemaVersion": 1, "checks": [{"name": "canonical", "status": "PASS"}]}
        state, reasons = agent_pipeline.derive_state(self.policy, checks, {"verdict": "PASS", "findings": []})
        self.assertEqual(agent_pipeline.STATE_WORKER_REQUIRED, state)
        self.assertIn("worker execution missing", reasons[0])

        worker = {"status": "FAIL"}
        state, _ = agent_pipeline.derive_state(self.policy, checks, None, worker)
        self.assertEqual(agent_pipeline.STATE_BLOCKED, state)

        worker["status"] = "PASS"
        state, _ = agent_pipeline.derive_state(self.policy, checks, {"verdict": "PASS", "findings": []}, worker)
        self.assertEqual(agent_pipeline.STATE_READY_FOR_MAIN_REVIEW, state)

    # Protects conservative path-based risk escalation without claiming semantic completeness.
    def test_risk_classification_escalates_sensitive_paths(self) -> None:
        risk = agent_pipeline.classify_risk(
            ["docs/TESTS.md", "modules/security/src/main/java/example/Auth.java"], self.policy, True
        )
        self.assertEqual("HIGH", risk["level"])
        unmatched = agent_pipeline.classify_risk(["archive.sh"], self.policy, True)
        self.assertEqual("MODERATE", unmatched["level"])
        self.assertEqual(["archive.sh"], unmatched["unmatchedChangedFiles"])
        self.assertEqual("UNKNOWN", agent_pipeline.classify_risk([], self.policy, False)["level"])

    # Protects task/policy immutability after a run has been initialized.
    def test_run_contract_rejects_task_or_policy_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            run_dir = root / "run"
            run_dir.mkdir()
            policy = root / "policy.json"
            task = run_dir / "task.md"
            policy.write_text("{}\n", encoding="utf-8")
            task.write_text("original\n", encoding="utf-8")
            manifest = {
                "policy": {"sha256": agent_pipeline.sha256_file(policy)},
                "taskSha256": agent_pipeline.sha256_file(task),
            }
            agent_pipeline.ensure_run_contract_matches(policy, run_dir, manifest)
            task.write_text("changed\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                agent_pipeline.ensure_run_contract_matches(policy, run_dir, manifest)

    # Protects the active-spec boundary used when generating bounded worker context.
    def test_active_spec_validation_rejects_archived_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            active = root / "docs/specs/active/example.md"
            active.parent.mkdir(parents=True)
            active.write_text("---\nspec_status: active\n---\n", encoding="utf-8")
            agent_pipeline.validate_active_spec(root, "docs/specs/active/example.md")
            with self.assertRaises(ValueError):
                agent_pipeline.validate_active_spec(root, "docs/specs/archive/example.md")


class AgentPipelineCliTest(unittest.TestCase):
    """Protects persisted run evidence and the minimal CLI handoff workflow."""

    def create_repository(self, root: Path) -> tuple[Path, Path]:
        (root / "README.md").write_text("# Test\n", encoding="utf-8")
        (root / "AGENTS.md").write_text("# Rules\n", encoding="utf-8")
        (root / ".gitignore").write_text("build/\n", encoding="utf-8")
        (root / "docs/specs").mkdir(parents=True)
        (root / "docs/specs/README.md").write_text("# Specs\n", encoding="utf-8")
        (root / "docs/TESTS.md").write_text("# Tests\n", encoding="utf-8")
        (root / "docs/QUALITY.md").write_text("# Quality\n", encoding="utf-8")
        active_spec = root / "docs/specs/active/example.md"
        active_spec.parent.mkdir(parents=True)
        active_spec.write_text("---\nspec_status: active\n---\n# Example\n", encoding="utf-8")
        task = root / "task.md"
        task.write_text("Implement one bounded test change.\n", encoding="utf-8")
        policy = root / "policy.json"
        policy.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "requiredRepositoryFiles": [
                        "README.md",
                        "AGENTS.md",
                        "docs/specs/README.md",
                        "docs/TESTS.md",
                        "docs/QUALITY.md",
                    ],
                    "requiredChecks": [
                        {"name": "canonical", "command": [sys.executable, "-c", "print('canonical pass')"]}
                    ],
                    "independentReview": {"required": True, "blockingSeverities": ["HIGH", "CRITICAL"]},
                    "mainReview": {"requiredBeforeAcceptance": True},
                    "escalationCategories": ["SECURITY"],
                    "riskRules": [{"level": "LOW", "patterns": ["^docs/"]}],
                }
            ),
            encoding="utf-8",
        )
        return policy, task

    def run_cli(
        self, root: Path, policy: Path, *args: str, env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(MODULE_PATH),
                "--repo-root",
                str(root),
                "--policy",
                str(policy),
                "--runs-root",
                "build/reports/agent-pipeline",
                "--worktrees-root",
                "build/agent-worktrees",
                *args,
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            env=env,
        )

    # Protects initialization, command evidence, review ingestion, and ready-for-review status.
    def test_cli_persists_evidence_until_main_review_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy, task = self.create_repository(root)
            initialized = self.run_cli(
                root,
                policy,
                "init",
                "--stage",
                "TEST-1",
                "--spec",
                "docs/specs/active/example.md",
                "--task-file",
                str(task),
                "--run-id",
                "test-run",
                "--model",
                "deepseek-test",
            )
            self.assertEqual(0, initialized.returncode, initialized.stderr)
            run_dir = root / "build/reports/agent-pipeline/test-run"
            self.assertTrue((run_dir / "manifest.json").is_file())
            self.assertIn("Do not declare a stage accepted", (run_dir / "task.md").read_text(encoding="utf-8"))

            checked = self.run_cli(root, policy, "run-check", "--run-id", "test-run", "--name", "canonical")
            self.assertEqual(0, checked.returncode, checked.stderr)
            self.assertTrue((run_dir / "logs/canonical-attempt-1.log").is_file())

            status_before_review = self.run_cli(root, policy, "status", "--run-id", "test-run")
            self.assertEqual(0, status_before_review.returncode)
            self.assertEqual("REVIEW_REQUIRED", json.loads(status_before_review.stdout)["state"])

            review_input = root / "review-input.json"
            review_input.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "reviewer": "fresh-deepseek-review",
                        "verdict": "PASS",
                        "findings": [],
                    }
                ),
                encoding="utf-8",
            )
            reviewed = self.run_cli(
                root,
                policy,
                "record-review",
                "--run-id",
                "test-run",
                "--input",
                str(review_input),
            )
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            result = json.loads(reviewed.stdout)
            self.assertEqual("READY_FOR_MAIN_REVIEW", result["state"])
            self.assertEqual("PASS", result["checks"]["canonical"]["status"])
            self.assertEqual("PASS", result["review"]["verdict"])
            self.assertTrue(result["mainReviewRequiredBeforeAcceptance"])
            reviews = json.loads((run_dir / "reviews.json").read_text(encoding="utf-8"))
            self.assertEqual(1, len(reviews["reviews"]))


    def init_git_repository(self, root: Path) -> str:
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "agent-test@example.invalid"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.name", "Agent Pipeline Test"], cwd=root, check=True)
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        subprocess.run(["git", "commit", "-qm", "test base"], cwd=root, check=True)
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True, stdout=subprocess.PIPE, check=True
        ).stdout.strip()

    # Protects fail-closed version pinning before any model-backed worker invocation can start.
    def test_aider_preflight_rejects_unsupported_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "repo"
            root.mkdir()
            (root / "placeholder.txt").write_text("base\n", encoding="utf-8")
            self.init_git_repository(root)
            run_dir = root / "build/reports/agent-pipeline/preflight"
            run_dir.mkdir(parents=True)
            agent_pipeline.prepare_aider_runtime_config(run_dir)

            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            fake_aider = fake_bin / "aider"
            fake_aider.write_text(
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if '--version' in sys.argv:\n"
                "    print('aider 0.86.1')\n"
                "    raise SystemExit(0)\n"
                "print('--model --config --message-file --read')\n",
                encoding="utf-8",
            )
            fake_aider.chmod(0o755)
            environment = dict(os.environ)
            environment["PATH"] = f"{fake_bin}{os.pathsep}{environment.get('PATH', '')}"
            environment["PYTHON_DOTENV_DISABLED"] = "1"
            worker_policy = {
                "executable": "aider",
                "supportedVersions": ["0.86.2"],
                "arguments": [
                    "--model",
                    "{model}",
                    "--config",
                    "{config_file}",
                    "--message-file",
                    "{task_file}",
                ],
            }

            preflight = agent_pipeline.preflight_aider(
                root, run_dir, worker_policy, environment, []
            )

            self.assertEqual("FAIL", preflight["status"])
            self.assertIn("not in repository-supported versions", preflight["failureReason"])
            self.assertIsNone(preflight["helpCommandExitCode"])

    # Protects fail-closed rejection of model configuration that Aider loads outside its main YAML config.
    def test_aider_preflight_rejects_ambient_model_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "repo"
            root.mkdir()
            (root / "placeholder.txt").write_text("base\n", encoding="utf-8")
            self.init_git_repository(root)
            (root / ".aider.model.settings.yml").write_text("[]\n", encoding="utf-8")
            run_dir = root / "build/reports/agent-pipeline/preflight"
            run_dir.mkdir(parents=True)
            agent_pipeline.prepare_aider_runtime_config(run_dir)

            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            fake_aider = fake_bin / "aider"
            fake_aider.write_text(
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if '--version' in sys.argv:\n"
                "    print('aider 0.86.2')\n"
                "    raise SystemExit(0)\n"
                "print('--model --config --message-file --read')\n",
                encoding="utf-8",
            )
            fake_aider.chmod(0o755)
            environment = dict(os.environ)
            environment["PATH"] = f"{fake_bin}{os.pathsep}{environment.get('PATH', '')}"
            environment["PYTHON_DOTENV_DISABLED"] = "1"
            worker_policy = {
                "executable": "aider",
                "supportedVersions": ["0.86.2"],
                "arguments": [
                    "--model",
                    "{model}",
                    "--config",
                    "{config_file}",
                    "--message-file",
                    "{task_file}",
                ],
            }

            preflight = agent_pipeline.preflight_aider(
                root, run_dir, worker_policy, environment, []
            )

            self.assertEqual("FAIL", preflight["status"])
            self.assertIn("Ambient Aider model settings/metadata", preflight["failureReason"])
            self.assertEqual(
                [str((root / ".aider.model.settings.yml").resolve())],
                preflight["ambientModelConfigurationFiles"],
            )

    # Protects isolated worktree preparation, one-shot Aider execution, and verification in the worker tree.
    def test_cli_runs_fake_aider_in_isolated_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "repo"
            root.mkdir()
            policy, task = self.create_repository(root)
            policy_data = json.loads(policy.read_text(encoding="utf-8"))
            policy_data["workerExecution"] = {
                "required": True,
                "tool": "aider",
                "executable": "aider",
                "defaultModel": "deepseek/deepseek-chat",
                "supportedVersions": ["0.86.2"],
                "branchPrefix": "agent/",
                "arguments": [
                    "--model",
                    "{model}",
                    "--config",
                    "{config_file}",
                    "--message-file",
                    "{task_file}",
                    "--yes-always",
                    "--no-auto-commits",
                    "--no-suggest-shell-commands",
                    "--no-show-release-notes",
                ],
            }
            policy.write_text(json.dumps(policy_data), encoding="utf-8")
            base_commit = self.init_git_repository(root)

            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            fake_aider = fake_bin / "aider"
            fake_aider.write_text(
                "#!/usr/bin/env python3\n"
                "import os\n"
                "import sys\n"
                "from pathlib import Path\n"
                "if '--version' in sys.argv:\n"
                "    print('aider 0.86.2')\n"
                "    raise SystemExit(0)\n"
                "if '--help' in sys.argv:\n"
                "    print('--model --config --message-file --yes-always --no-auto-commits ')\n"
                "    print('--no-suggest-shell-commands --no-show-release-notes --read')\n"
                "    raise SystemExit(0)\n"
                "assert 'AIDER_MODEL' not in os.environ\n"
                "assert os.environ.get('PYTHON_DOTENV_DISABLED') == '1'\n"
                "config = Path(sys.argv[sys.argv.index('--config') + 1])\n"
                "assert config.read_text(encoding='utf-8') == '{}\\n'\n"
                "Path('worker-output.txt').write_text('changed by fake aider\\n', encoding='utf-8')\n"
                "print('fake aider completed')\n",
                encoding="utf-8",
            )
            fake_aider.chmod(0o755)
            env = dict(os.environ)
            env["PATH"] = f"{fake_bin}{os.pathsep}{env.get('PATH', '')}"
            env["AIDER_MODEL"] = "ambient-model-must-not-leak"

            initialized = self.run_cli(
                root,
                policy,
                "init",
                "--stage",
                "A2.1-TEST",
                "--spec",
                "docs/specs/active/example.md",
                "--task-file",
                str(task),
                "--run-id",
                "worker-test",
                env=env,
            )
            self.assertEqual(0, initialized.returncode, initialized.stderr)
            status = self.run_cli(root, policy, "status", "--run-id", "worker-test", env=env)
            self.assertEqual("WORKER_REQUIRED", json.loads(status.stdout)["state"])

            prepared = self.run_cli(root, policy, "prepare-worker", "--run-id", "worker-test", env=env)
            self.assertEqual(0, prepared.returncode, prepared.stderr)
            workspace = json.loads(prepared.stdout)
            worktree = root / workspace["path"]
            self.assertEqual(base_commit, subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=worktree, text=True, stdout=subprocess.PIPE, check=True
            ).stdout.strip())
            self.assertFalse((root / "worker-output.txt").exists())

            worker = self.run_cli(root, policy, "run-worker", "--run-id", "worker-test", env=env)
            self.assertEqual(0, worker.returncode, worker.stderr)
            self.assertEqual("changed by fake aider\n", (worktree / "worker-output.txt").read_text(encoding="utf-8"))
            worker_result = json.loads(
                (root / "build/reports/agent-pipeline/worker-test/worker.json").read_text(encoding="utf-8")
            )
            self.assertEqual("PASS", worker_result["status"])
            self.assertEqual("PASS", worker_result["preflight"]["status"])
            self.assertEqual("aider 0.86.2", worker_result["preflight"]["version"])
            self.assertEqual(["AIDER_MODEL"], worker_result["preflight"]["removedAiderEnvironmentVariables"])
            self.assertTrue(worker_result["preflight"]["dotenvDisabled"])
            self.assertTrue(worker_result["preflight"]["gitIdentityConfigured"])
            self.assertEqual([], worker_result["preflight"]["missingOptions"])
            self.assertEqual(["worker-output.txt"], worker_result["changedFiles"])
            self.assertFalse(worker_result["unexpectedCommit"])
            self.assertEqual(base_commit, worker_result["headCommit"])

            second_worker = self.run_cli(root, policy, "run-worker", "--run-id", "worker-test", env=env)
            self.assertEqual(2, second_worker.returncode)
            self.assertIn("already recorded", second_worker.stderr)

            checked = self.run_cli(root, policy, "run-check", "--run-id", "worker-test", "--name", "canonical", env=env)
            self.assertEqual(0, checked.returncode, checked.stderr)
            status_after = self.run_cli(root, policy, "status", "--run-id", "worker-test", env=env)
            self.assertEqual("REVIEW_REQUIRED", json.loads(status_after.stdout)["state"])
            self.assertEqual("", subprocess.run(
                ["git", "status", "--porcelain"], cwd=root, text=True, stdout=subprocess.PIPE, check=True
            ).stdout.strip())

            (worktree / "worker-output.txt").write_text("tampered after worker\n", encoding="utf-8")
            drifted = self.run_cli(root, policy, "status", "--run-id", "worker-test", env=env)
            self.assertEqual(2, drifted.returncode)
            self.assertIn("source changed after worker execution", drifted.stderr)

    # A no-op worker must not reach security verification or a reviewer.
    def test_no_diff_worker_fails_and_bounded_repair_preserves_attempt_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "repo"
            root.mkdir()
            policy, task = self.create_repository(root)
            content = json.loads(policy.read_text(encoding="utf-8"))
            content["workerExecution"] = {
                "required": True, "tool": "aider", "executable": "aider",
                "defaultModel": "deepseek/deepseek-chat", "supportedVersions": ["0.86.2"],
                "branchPrefix": "agent/",
                "arguments": ["--model", "{model}", "--config", "{config_file}",
                              "--message-file", "{task_file}", "--no-auto-commits"],
            }
            content["repairExecution"] = {"maxRepairAttempts": 3}
            policy.write_text(json.dumps(content), encoding="utf-8")
            self.init_git_repository(root)
            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            fake_aider = fake_bin / "aider"
            fake_aider.write_text(
                "#!/usr/bin/env python3\n"
                "import pathlib,sys\n"
                "if '--version' in sys.argv: print('aider 0.86.2'); quit()\n"
                "if '--help' in sys.argv: print('--model --config --message-file --no-auto-commits --read'); quit()\n"
                "task = pathlib.Path(sys.argv[sys.argv.index('--message-file') + 1]).read_text()\n"
                "if 'Bounded repair round' in task:\n"
                "    pathlib.Path('docs/ROADMAP.md').parent.mkdir(exist_ok=True)\n"
                "    pathlib.Path('docs/ROADMAP.md').write_text('fixed\\n')\n"
                "print('fake model completed')\n",
                encoding="utf-8",
            )
            fake_aider.chmod(0o755)
            env = dict(os.environ, PATH=f"{fake_bin}{os.pathsep}{os.environ.get('PATH', '')}")
            run_id = "no-diff-repair"
            self.assertEqual(0, self.run_cli(root, policy, "init", "--stage", "A3-test",
                "--spec", "docs/specs/active/example.md", "--task-file", str(task),
                "--run-id", run_id, env=env).returncode)
            self.assertEqual(0, self.run_cli(root, policy, "prepare-worker", "--run-id", run_id,
                env=env).returncode)
            first = self.run_cli(root, policy, "run-worker", "--run-id", run_id, env=env)
            self.assertEqual(1, first.returncode, first.stderr)
            evidence = root / "build/reports/agent-pipeline" / run_id
            worker_one = json.loads((evidence / "worker.json").read_text())
            self.assertEqual("FAIL", worker_one["status"])
            self.assertEqual("worker produced no source changes", worker_one["failureReason"])
            self.assertEqual([], worker_one["changedFiles"])
            self.assertEqual(worker_one, json.loads((evidence / "worker-attempt-1.json").read_text()))
            denied = self.run_cli(root, policy, "run-check", "--run-id", run_id,
                                  "--name", "canonical", env=env)
            self.assertEqual(2, denied.returncode)
            self.assertIn("did not pass", denied.stderr)
            self.assertEqual("BLOCKED", json.loads(self.run_cli(root, policy, "status",
                                  "--run-id", run_id, env=env).stdout)["state"])

            repaired = self.run_cli(root, policy, "repair", "--run-id", run_id, env=env)
            self.assertEqual(0, repaired.returncode, repaired.stderr)
            worker_two = json.loads((evidence / "worker.json").read_text())
            self.assertEqual("PASS", worker_two["status"])
            self.assertEqual(2, worker_two["attempt"])
            self.assertEqual(["docs/ROADMAP.md"], worker_two["changedFiles"])
            self.assertTrue((evidence / "worker-attempt-1.json").exists())
            self.assertEqual("FAIL", json.loads((evidence / "worker-attempt-1.json").read_text())["status"])
            self.assertTrue((evidence / "attempt-1-checks.json").exists())
            self.assertEqual([], json.loads((evidence / "checks.json").read_text())["checks"])
            self.assertIn("no source diff", (evidence / "repair-task-2.md").read_text())
            self.assertEqual(0, self.run_cli(root, policy, "run-check", "--run-id", run_id,
                            "--name", "canonical", env=env).returncode)
            status = json.loads(self.run_cli(root, policy, "status", "--run-id", run_id,
                                           env=env).stdout)
            self.assertEqual("REVIEW_REQUIRED", status["state"])

            # Refuse another repair when there is no failing check or review to fix.
            denied_again = self.run_cli(root, policy, "repair", "--run-id", run_id, env=env)
            self.assertEqual(2, denied_again.returncode)
            self.assertIn("No qualifying", denied_again.stderr)

    # A review-requested repair must archive that review and invalidate all old passes.
    def test_review_repair_invalidates_prior_success_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "README.md").write_text("base\n", encoding="utf-8")
            (root / ".gitignore").write_text("evidence/\n", encoding="utf-8")
            self.init_git_repository(root)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            (root / "docs").mkdir(exist_ok=True)
            (root / "docs/ROADMAP.md").write_text("initial implementation\n", encoding="utf-8")
            run_dir = root / "evidence"
            run_dir.mkdir()
            (run_dir / "task.md").write_text("Implement the requirement.\n")
            worker = {"status": "PASS", "attempt": 1, "exitCode": 0,
                      "changedFiles": ["docs/ROADMAP.md"], "headCommit": base,
                      "changeSha256": agent_pipeline.workspace_change_digest(root, ["docs/ROADMAP.md"])}
            agent_pipeline.write_json(run_dir / "worker.json", worker)
            agent_pipeline.write_json(run_dir / "checks.json", {"checks": [{"name": "canonical", "status": "PASS"}]})
            agent_pipeline.write_json(run_dir / "review.json", {"verdict": "CHANGES_REQUIRED", "findings": [
                {"id": "REV-1", "status": "OPEN", "category": "CORRECTNESS", "severity": "MEDIUM"}]})
            agent_pipeline.write_json(run_dir / "handoff.json", {"state": "BLOCKED"})
            agent_pipeline.write_json(run_dir / "status.json", {"state": "BLOCKED"})
            policy = {"repairExecution": {"maxRepairAttempts": 3},
                      "requiredChecks": [{"name": "canonical", "command": ["true"]}],
                      "riskRules": [{"level": "LOW", "patterns": ["^docs/"]}],
                      "escalationCategories": ["SECURITY"]}
            manifest = {"repository": {"baseCommit": base}, "workspace": {"path": str(root)}}
            attempt, task = agent_pipeline.prepare_repair(root, run_dir, manifest, policy)
            self.assertEqual(2, attempt)
            self.assertIn("REV-1", task.read_text())
            self.assertFalse((run_dir / "review.json").exists())
            self.assertFalse((run_dir / "handoff.json").exists())
            self.assertFalse((run_dir / "status.json").exists())
            self.assertFalse((run_dir / "worker.json").exists())
            self.assertEqual([], agent_pipeline.read_json(run_dir / "checks.json")["checks"])
            for filename in ("checks.json", "review.json", "handoff.json", "status.json"):
                self.assertTrue((run_dir / f"attempt-1-{filename}").exists())

    # Source drift and exhausted retry budgets are terminal without replaying Aider.
    def test_repair_denies_tampering_and_exhaustion(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            (root / "README.md").write_text("base\n")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=root, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            run_dir = root / "evidence"
            run_dir.mkdir()
            worker = {"status": "FAIL", "attempt": 4, "failureReason": "worker produced no source changes",
                      "exitCode": 0, "headCommit": base, "changedFiles": [],
                      "changeSha256": agent_pipeline.workspace_change_digest(root, [])}
            agent_pipeline.write_json(run_dir / "worker.json", worker)
            manifest = {"repository": {"baseCommit": base}, "workspace": {"path": str(root)}}
            policy = {"repairExecution": {"maxRepairAttempts": 3}, "riskRules": []}
            with self.assertRaisesRegex(ValueError, "budget exhausted"):
                agent_pipeline.prepare_repair(root, run_dir, manifest, policy)
            worker["attempt"] = 1
            agent_pipeline.write_json(run_dir / "worker.json", worker)
            (root / "README.md").write_text("tampered\n")
            with self.assertRaisesRegex(ValueError, "source changed"):
                agent_pipeline.prepare_repair(root, run_dir, manifest, policy)

    def test_repair_denies_escalation_findings_and_security_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            run_dir = root / "evidence"
            run_dir.mkdir()
            worker = {"status": "PASS", "attempt": 1, "changedFiles": ["docs/a.md"]}
            agent_pipeline.write_json(run_dir / "worker.json", worker)
            agent_pipeline.write_json(run_dir / "checks.json", {"checks": [{"name": "gitleaks", "status": "FAIL"}]})
            policy = {"repairExecution": {"maxRepairAttempts": 3},
                      "requiredChecks": [{"name": "gitleaks", "kind": "gitleaks"}],
                      "riskRules": [{"level": "LOW", "patterns": ["^docs/"]}],
                      "escalationCategories": ["SECURITY"]}
            with mock.patch.object(agent_pipeline, "ensure_source_matches_worker_result"):
                with self.assertRaisesRegex(ValueError, "Security scan"):
                    agent_pipeline.prepare_repair(root, run_dir, {}, policy)
            agent_pipeline.write_json(run_dir / "checks.json", {"checks": [{"name": "canonical", "status": "PASS"}]})
            agent_pipeline.write_json(run_dir / "review.json", {"verdict": "CHANGES_REQUIRED", "findings": [
                {"status": "OPEN", "category": "SECURITY", "severity": "LOW", "id": "REV-1"}]})
            with mock.patch.object(agent_pipeline, "ensure_source_matches_worker_result"):
                with self.assertRaisesRegex(ValueError, "escalation findings"):
                    agent_pipeline.prepare_repair(root, run_dir, {}, policy)

    # Protects fail-closed Gitleaks ordering and redacted evidence for uncommitted worker files.
    def test_verify_scans_uncommitted_worker_files_before_canonical(self) -> None:
        for secret_present in (False, True):
            with self.subTest(secret_present=secret_present), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir) / "repo"
                root.mkdir()
                policy_path, task = self.create_repository(root)
                scanner_config = root / "tools/agent-pipeline/gitleaks.toml"
                scanner_config.parent.mkdir(parents=True)
                scanner_config.write_text("[extend]\nuseDefault = true\n", encoding="utf-8")
                policy = json.loads(policy_path.read_text(encoding="utf-8"))
                policy["requiredChecks"].insert(0, {
                    "name": "gitleaks", "kind": "gitleaks", "executable": "gitleaks",
                    "supportedVersion": "8.30.1", "config": "tools/agent-pipeline/gitleaks.toml",
                })
                policy["workerExecution"] = {
                    "required": True, "tool": "aider", "executable": "aider",
                    "defaultModel": "deepseek/deepseek-chat", "supportedVersions": ["0.86.2"],
                    "branchPrefix": "agent/", "arguments": ["--model", "{model}", "--message-file", "{task_file}"],
                }
                policy_path.write_text(json.dumps(policy), encoding="utf-8")
                base_commit = self.init_git_repository(root)
                fake_bin = Path(temp_dir) / "bin"
                fake_bin.mkdir()
                fake_scanner = fake_bin / "gitleaks"
                fake_scanner.write_text(
                    "#!/usr/bin/env python3\n"
                    "import json, sys\n"
                    "from pathlib import Path\n"
                    "if 'version' in sys.argv:\n"
                    "    print('gitleaks version 8.30.1')\n"
                    "    raise SystemExit(0)\n"
                    "assert 'dir' in sys.argv and '.' in sys.argv\n"
                    "assert '--ignore-gitleaks-allow' in sys.argv\n"
                    "assert '--redact=100' in sys.argv\n"
                    "assert '--config' in sys.argv\n"
                    "assert Path(sys.argv[sys.argv.index('--config')+1]).is_file()\n"
                    "findings = []\n"
                    "if Path('data.txt').read_text() == 'SECRET-VALUE\\n':\n"
                    "    findings = [{'RuleID':'generic-api-key','File':'data.txt','StartLine':1,"
                    "'Secret':'SECRET-VALUE','Line':'SECRET-VALUE','Match':'SECRET-VALUE'}]\n"
                    "report = Path(sys.argv[sys.argv.index('--report-path')+1])\n"
                    "if findings: report.write_text(json.dumps(findings))\n"
                    "print('SECRET-VALUE scanner output that must not be persisted')\n"
                    "raise SystemExit(1 if findings else 0)\n",
                    encoding="utf-8",
                )
                fake_scanner.chmod(0o755)
                env = dict(os.environ)
                env["PATH"] = f"{fake_bin}{os.pathsep}{env.get('PATH','')}"
                initialized = self.run_cli(root, policy_path, "init", "--stage", "A2.2-TEST",
                    "--spec", "docs/specs/active/example.md", "--task-file", str(task),
                    "--run-id", "security-test", env=env)
                self.assertEqual(0, initialized.returncode, initialized.stderr)
                prepared = self.run_cli(root, policy_path, "prepare-worker", "--run-id", "security-test", env=env)
                self.assertEqual(0, prepared.returncode, prepared.stderr)
                worktree = root / json.loads(prepared.stdout)["path"]
                (worktree / "data.txt").write_text("SECRET-VALUE\n" if secret_present else "safe\n")
                run_dir = root / "build/reports/agent-pipeline/security-test"
                files = ["data.txt"]
                worker_result = {
                    "status": "PASS", "headCommit": base_commit, "changedFiles": files,
                    "changeSha256": agent_pipeline.workspace_change_digest(worktree, files),
                }
                agent_pipeline.write_json(run_dir / "worker.json", worker_result)
                verified = self.run_cli(root, policy_path, "verify", "--run-id", "security-test", env=env)
                self.assertEqual(1 if secret_present else 0, verified.returncode, verified.stderr)
                evidence_text = (run_dir / "checks.json").read_text(encoding="utf-8")
                self.assertNotIn("SECRET-VALUE", evidence_text)
                self.assertNotIn("SECRET-VALUE", verified.stdout)
                checks = json.loads(evidence_text)["checks"]
                self.assertEqual(["gitleaks"] if secret_present else ["gitleaks", "canonical"],
                                 [item["name"] for item in checks])
                scan = checks[0]["scanner"]
                self.assertEqual("worker-worktree-directory", scan["scope"])
                self.assertEqual(1 if secret_present else 0, scan["findingCount"])
                self.assertEqual("FAIL" if secret_present else "PASS", checks[0]["status"])
                status = json.loads((run_dir / "status.json").read_text(encoding="utf-8"))
                self.assertEqual("BLOCKED" if secret_present else "REVIEW_REQUIRED", status["state"])
                if not secret_present:
                    checks[0]["scanner"]["sourceSha256"] = "tampered"
                    agent_pipeline.write_json(run_dir / "checks.json", {"schemaVersion": 1, "checks": checks})
                    tampered = self.run_cli(root, policy_path, "status", "--run-id", "security-test", env=env)
                    self.assertEqual(2, tampered.returncode)
                    self.assertIn("Gitleaks PASS evidence", tampered.stderr)

    # Protects scanner config, local ignore files, and fail-closed unavailable-version behavior.
    def test_gitleaks_rejects_worker_supplied_ignore_or_changed_config(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "repo"
            root.mkdir()
            scanner_config = root / "tools/agent-pipeline/gitleaks.toml"
            scanner_config.parent.mkdir(parents=True)
            scanner_config.write_text("[extend]\nuseDefault = true\n", encoding="utf-8")
            base_commit = self.init_git_repository(root)
            run_dir = root / "build/run"
            run_dir.mkdir(parents=True)
            agent_pipeline.write_json(run_dir / "checks.json", {"checks": []})
            manifest = {"repository": {"baseCommit": base_commit}}
            definition = {
                "name": "gitleaks", "kind": "gitleaks", "executable": "gitleaks-missing",
                "supportedVersion": "8.30.1", "config": "tools/agent-pipeline/gitleaks.toml",
            }
            agent_pipeline.write_json(run_dir / "worker.json", {"changeSha256": "a" * 64})
            self.assertEqual(1, agent_pipeline.execute_gitleaks_check(root, run_dir, manifest, definition))
            checks = agent_pipeline.read_json(run_dir / "checks.json")["checks"]
            self.assertIn("not available", checks[-1]["scanner"]["error"])
            (root / ".gitleaksignore").write_text("ignore\n", encoding="utf-8")
            self.assertEqual(1, agent_pipeline.execute_gitleaks_check(root, run_dir, manifest, definition))
            checks = agent_pipeline.read_json(run_dir / "checks.json")["checks"]
            self.assertIn(".gitleaksignore", checks[-1]["scanner"]["error"])
            (root / ".gitleaksignore").unlink()
            scanner_config.write_text("[extend]\nuseDefault = false\n", encoding="utf-8")
            self.assertEqual(1, agent_pipeline.execute_gitleaks_check(root, run_dir, manifest, definition))
            checks = agent_pipeline.read_json(run_dir / "checks.json")["checks"]
            self.assertIn("Worker changed", checks[-1]["scanner"]["error"])


if __name__ == "__main__":
    unittest.main()



class AutomaticReviewTest(unittest.TestCase):
    """Protects independent reviewer policy bounds and fail-closed evidence."""

    def test_reviewer_adapter_rejects_unreviewed_endpoint_and_unbounded_context(self):
        policy = {
            "schemaVersion": 1,
            "requiredChecks": [{"name": "canonical", "command": ["true"]}],
            "independentReview": {"adapter": {
                "endpoint": "http://localhost:80/api", "model": "reviewer",
                "apiKeyEnvironment": "REVIEW_API_KEY", "timeoutSeconds": 120,
                "maxDiffBytes": 100000,
            }},
        }
        with self.assertRaisesRegex(ValueError, "endpoint"):
            agent_pipeline.validate_policy(policy)
        policy["independentReview"]["adapter"]["endpoint"] = "https://api.deepseek.com/chat/completions"
        policy["independentReview"]["adapter"]["maxDiffBytes"] = 1000000
        with self.assertRaisesRegex(ValueError, "maxDiffBytes"):
            agent_pipeline.validate_policy(policy)

    def test_review_payload_requires_schema_and_structured_findings(self):
        review = {"schemaVersion": 1, "reviewer": "fresh-context", "verdict": "PASS", "findings": []}
        self.assertEqual("PASS", agent_pipeline.validate_review(review)["verdict"])
        review["findings"] = [{"id": "F1", "severity": "INVALID", "category": "SECURITY",
                               "summary": "Invalid finding", "status": "OPEN"}]
        with self.assertRaisesRegex(ValueError, "severity"):
            agent_pipeline.validate_review(review)

    def test_reviewer_requires_verified_worker_before_external_request(self):
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            (run_dir / "checks.json").write_text(json.dumps({"checks": []}))
            manifest = {"runId": "check", "repository": {"baseCommit": "abc"}}
            policy = {"independentReview": {"adapter": {"model": "deepseek-chat"}}}
            with mock.patch.object(agent_pipeline, "ensure_worker_workspace_matches_result",
                                   return_value={"status": "FAIL"}):
                with self.assertRaisesRegex(ValueError, "Successful worker"):
                    agent_pipeline.run_independent_review(run_dir, run_dir, manifest, policy)
            self.assertFalse((run_dir / "review.json").exists())
            self.assertFalse((run_dir / "review-attempt.json").exists())

    def test_reviewer_failure_codes_do_not_expose_error_messages(self):
        import urllib.error
        cases = [
            (urllib.error.HTTPError("https://example.invalid", 429, "private", {}, None),
             "request", "HTTP_STATUS_FAILURE"),
            (TimeoutError("credential or endpoint"), "request", "REQUEST_TIMEOUT"),
            (urllib.error.URLError("private provider response"), "request", "TRANSPORT_FAILURE"),
            (ValueError("private content"), "envelope", "INVALID_RESPONSE_ENVELOPE"),
            (ValueError("private content"), "content", "INVALID_REVIEW_JSON"),
            (ValueError("private content"), "schema", "INVALID_REVIEW_SCHEMA"),
        ]
        for error, phase, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(expected, agent_pipeline._review_failure_reason_code(error, phase))

    def test_reviewer_records_safe_http_status_and_invalid_envelope(self):
        import urllib.error
        for response, expected, expected_status in (
            (urllib.error.HTTPError("https://example.invalid", 429, "secret", {}, None),
             "HTTP_STATUS_FAILURE", 429),
            (b"{invalid JSON", "INVALID_RESPONSE_ENVELOPE", None),
        ):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as location:
                root = Path(location)
                run_dir = root / "evidence"
                run_dir.mkdir()
                (run_dir / "task.md").write_text("task", encoding="utf-8")
                agent_pipeline.write_json(run_dir / "checks.json", {"checks": []})
                manifest = {"stage": "A4", "specPath": "docs/specs/active/test.md",
                            "repository": {"baseCommit": "abc"}}
                policy = {"requiredChecks": [], "independentReview": {"adapter": {
                    "endpoint": "https://example.invalid/chat/completions", "model": "reviewer",
                    "apiKeyEnvironment": "REVIEWER_FAKE_TEST_KEY", "timeoutSeconds": 1,
                    "maxDiffBytes": 100000}}}
                worker = {"status": "PASS", "changedFiles": [], "changeSha256": "a" * 64}
                completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="diff --git a/foo b/foo\n+code")

                class Response:
                    def __enter__(self):
                        return self
                    def __exit__(self, *args):
                        return False
                    def read(self, *_):
                        return response

                def transport(*args, **kwargs):
                    if isinstance(response, Exception):
                        raise response
                    return Response()

                with mock.patch.object(agent_pipeline, "ensure_worker_workspace_matches_result", return_value=worker), \
                     mock.patch.object(agent_pipeline, "derive_state", return_value=(agent_pipeline.STATE_REVIEW_REQUIRED, [])), \
                     mock.patch.object(agent_pipeline.subprocess, "run", return_value=completed), \
                     mock.patch.object(agent_pipeline.urllib.request, "urlopen", side_effect=transport), \
                     mock.patch.dict(os.environ, {"REVIEWER_FAKE_TEST_KEY": "not-a-real-secret"}):
                    with self.assertRaisesRegex(ValueError, "Independent reviewer failed"):
                        agent_pipeline.run_independent_review(root, run_dir, manifest, policy)
                report = agent_pipeline.read_json(run_dir / "review-attempt.json")
                self.assertEqual("FAIL", report["status"])
                self.assertEqual(expected, report["reasonCode"])
                if expected_status:
                    self.assertEqual(expected_status, report["httpStatus"])
                self.assertNotIn("secret", json.dumps(report))
                self.assertFalse((run_dir / "review.json").exists())


class AutomaticCycleTest(unittest.TestCase):
    """Protects fail-closed A3 automated check, independent review, and repair sequencing."""

    def prepare_cycle(self, location: Path, scenario: str, canonical: str) -> tuple[Path, Path, dict, dict, dict]:
        root = location / "repo"
        root.mkdir()
        helper = AgentPipelineCliTest()
        policy_path, task = helper.create_repository(root)
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
        policy["requiredChecks"] = [{"name": "canonical", "command": [sys.executable, "-c", canonical]}]
        policy["repairExecution"] = {"maxRepairAttempts": 3, "automaticCheckRepairNames": ["canonical"]}
        policy["workerExecution"] = {
            "required": True, "tool": "aider", "executable": "aider",
            "defaultModel": "deepseek/deepseek-chat", "supportedVersions": ["0.86.2"],
            "branchPrefix": "agent/", "arguments": ["--model", "{model}", "--config", "{config_file}",
                                                 "--message-file", "{task_file}", "--no-auto-commits"],
        }
        policy["independentReview"]["adapter"] = {
            "endpoint": "https://api.deepseek.com/chat/completions", "model": "deepseek-chat",
            "apiKeyEnvironment": "DEEPSEEK_API_KEY", "timeoutSeconds": 120, "maxDiffBytes": 100000,
        }
        policy_path.write_text(json.dumps(policy), encoding="utf-8")
        helper.init_git_repository(root)
        fake_bin = location / "bin"
        fake_bin.mkdir()
        fake_aider = fake_bin / "aider"
        fake_aider.write_text(
            "#!/usr/bin/env python3\n"
            "import os,sys\n"
            "from pathlib import Path\n"
            "if '--version' in sys.argv: print('aider 0.86.2'); quit()\n"
            "if '--help' in sys.argv: print('--model --config --message-file --no-auto-commits --read'); quit()\n"
            "task = Path(sys.argv[sys.argv.index('--message-file') + 1]).read_text()\n"
            "repair = 'Bounded repair round' in task\n"
            "scenario = os.environ['FAKE_WORKER_CASE']\n"
            "if scenario == 'no-diff' and not repair: quit()\n"
            "if scenario == 'always-no-diff': quit()\n"
            "if scenario == 'no-progress' and repair: quit()\n"
            "p = Path('docs/ROADMAP.md')\n"
            "p.write_text('fixed\\n' if repair else 'initial\\n')\n",
            encoding="utf-8",
        )
        fake_aider.chmod(0o755)
        runs_root = root / "build/reports/agent-pipeline"
        run_dir = agent_pipeline.create_run(root, runs_root, policy_path, policy,
            "A3-cycle", "docs/specs/active/example.md", task.read_text(),
            "aider", "deepseek/deepseek-chat", ["docs/ROADMAP.md"], "cycle-test")
        manifest = agent_pipeline.read_json(run_dir / "manifest.json")
        agent_pipeline.prepare_worker_workspace(root, root / "build/agent-worktrees", run_dir, manifest, policy)
        manifest = agent_pipeline.read_json(run_dir / "manifest.json")
        env = {"PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
               "FAKE_WORKER_CASE": scenario}
        return root, run_dir, manifest, policy, env

    def fake_reviewer(self, run_dir: Path, verdicts: list[str]):
        calls = []
        def review(repo_root, evidence, manifest, policy):
            verdict = verdicts[len(calls)]
            calls.append(verdict)
            payload = agent_pipeline.validate_review({
                "schemaVersion": 1, "reviewer": "deterministic-fake-reviewer",
                "verdict": verdict, "findings": [] if verdict == "PASS" else [{
                    "id": "F-1", "severity": "MEDIUM", "category": "CORRECTNESS",
                    "summary": "Unresolved bounded defect", "status": "OPEN",
                }],
            })
            agent_pipeline.write_json(evidence / "review.json", payload)
            agent_pipeline.write_json(evidence / "review-attempt.json", {
                "status": "PASS", "workerSha256": agent_pipeline.read_json(evidence / "worker.json")["changeSha256"],
            })
            return payload
        return review, calls

    # A no-diff first attempt is automatically repaired and still requires fresh verification and review.
    def test_cycle_repairs_no_diff_then_requires_checks_and_review(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "no-diff", "print('ok')")
            reviewer, calls = self.fake_reviewer(evidence, ["PASS"])
            with mock.patch.dict(os.environ, env), mock.patch.object(agent_pipeline, "run_independent_review", reviewer):
                self.assertEqual(0, agent_pipeline.run_automated_cycle(root, evidence, manifest, policy))
            cycle = agent_pipeline.read_json(evidence / "cycle.json")
            self.assertEqual("READY_FOR_MAIN_REVIEW", cycle["status"])
            self.assertEqual("REVIEW_AND_CHECKS_PASSED", cycle["reasonCode"])
            self.assertEqual(["PASS"], calls)
            self.assertEqual(2, agent_pipeline.read_json(evidence / "worker.json")["attempt"])
            self.assertEqual("FAIL", agent_pipeline.read_json(evidence / "worker-attempt-1.json")["status"])
            self.assertTrue((evidence / "handoff.json").exists())
            with self.assertRaisesRegex(ValueError, "already attempted"):
                agent_pipeline.run_automated_cycle(root, evidence, manifest, policy)

    # Review-requested changes trigger a fresh model attempt, new checks and a fresh reviewer context.
    def test_cycle_rechecks_after_low_risk_review_findings(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "review", "print('ok')")
            reviewer, calls = self.fake_reviewer(evidence, ["CHANGES_REQUIRED", "PASS"])
            with mock.patch.dict(os.environ, env), mock.patch.object(agent_pipeline, "run_independent_review", reviewer):
                self.assertEqual(0, agent_pipeline.run_automated_cycle(root, evidence, manifest, policy))
            self.assertEqual(["CHANGES_REQUIRED", "PASS"], calls)
            self.assertTrue((evidence / "attempt-1-review.json").exists())
            self.assertEqual(2, len([x for x in agent_pipeline.read_json(evidence / "cycle.json")["events"]
                                     if x["step"] == "check:canonical"]))
            original_check = agent_pipeline.read_json(evidence / "attempt-1-checks.json")["checks"][0]
            current_check = agent_pipeline.read_json(evidence / "checks.json")["checks"][0]
            self.assertNotEqual(original_check["log"], current_check["log"])
            self.assertEqual(original_check["logSha256"], agent_pipeline.sha256_file(evidence / original_check["log"]))
            self.assertEqual(current_check["logSha256"], agent_pipeline.sha256_file(evidence / current_check["log"]))
            self.assertEqual("fixed\n", (root / manifest["workspace"]["path"] / "docs/ROADMAP.md").read_text())

    # Canonical deterministic failures may repair; infrastructure and unknown failures stop immediately.
    def test_cycle_classifies_build_failure_but_escalates_infrastructure(self) -> None:
        deterministic = ("from pathlib import Path; import sys; "
            "fixed=Path('docs/ROADMAP.md').read_text() == 'fixed\\n'; "
            "print('all tests passed' if fixed else '> Task :modules:analysis:test FAILED\\nThere were failing tests'); "
            "sys.exit(0 if fixed else 1)")
        for infrastructure in (False, True):
            with self.subTest(infrastructure=infrastructure), tempfile.TemporaryDirectory() as td:
                command = deterministic if not infrastructure else (
                    "import sys; print('> Task :modules:analysis:test FAILED\\n"
                    "There were failing tests\\nDocker connection refused'); sys.exit(1)")
                root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "review", command)
                reviewer, calls = self.fake_reviewer(evidence, ["PASS"])
                with mock.patch.dict(os.environ, env), mock.patch.object(agent_pipeline, "run_independent_review", reviewer):
                    result = agent_pipeline.run_automated_cycle(root, evidence, manifest, policy)
                cycle = agent_pipeline.read_json(evidence / "cycle.json")
                if infrastructure:
                    self.assertEqual(1, result)
                    self.assertEqual("INFRASTRUCTURE_OR_UNKNOWN", cycle["reasonCode"])
                    self.assertFalse(calls)
                    self.assertEqual(1, agent_pipeline.read_json(evidence / "worker.json")["attempt"])
                else:
                    self.assertEqual(0, result)
                    self.assertEqual(2, agent_pipeline.read_json(evidence / "worker.json")["attempt"])
                    self.assertEqual("TEST_FAILURE", agent_pipeline.classify_automatic_check_failure(
                        evidence, agent_pipeline.read_json(evidence / "attempt-1-checks.json")["checks"][-1],
                        policy)[0])

    # Stop after one initial attempt plus three repairs, preserving every attempt's evidence.
    def test_cycle_stops_on_repair_budget(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "always-no-diff", "print('ok')")
            reviewer, calls = self.fake_reviewer(evidence, ["PASS"])
            with mock.patch.dict(os.environ, env), mock.patch.object(agent_pipeline, "run_independent_review", reviewer):
                self.assertEqual(1, agent_pipeline.run_automated_cycle(root, evidence, manifest, policy))
            self.assertEqual("REPAIR_BUDGET_EXHAUSTED", agent_pipeline.read_json(evidence / "cycle.json")["reasonCode"])
            self.assertEqual(4, agent_pipeline.read_json(evidence / "worker.json")["attempt"])
            self.assertEqual([], calls)
            self.assertEqual(4, len(list(evidence.glob("worker-attempt-*.json"))))

    # Missing/tampered logs never cause automated model retries.
    def test_classifier_rejects_tampered_log_and_security_check(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            evidence = Path(td)
            logs = evidence / "logs"
            logs.mkdir()
            output = logs / "canonical-attempt-1.log"
            output.write_text("> Task :modules:analysis:test FAILED\nThere were failing tests\n")
            policy = {"repairExecution": {"automaticCheckRepairNames": ["canonical"]}}
            check = {"name": "canonical", "status": "FAIL", "exitCode": 1,
                     "log": "logs/canonical-attempt-1.log", "logSha256": "incorrect"}
            self.assertEqual("INVALID_CHECK_EVIDENCE", agent_pipeline.classify_automatic_check_failure(
                evidence, check, policy)[0])
            check["logSha256"] = agent_pipeline.sha256_file(output)
            self.assertEqual("TEST_FAILURE", agent_pipeline.classify_automatic_check_failure(
                evidence, check, policy)[0])
            check["name"] = "gitleaks"
            self.assertEqual("SECURITY_FAILURE", agent_pipeline.classify_automatic_check_failure(
                evidence, check, policy)[0])

    # The final unittest report, not simulated infrastructure output from passing tests, controls repair.
    def test_classifier_accepts_real_unittest_errors_with_simulated_failure_noise(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            evidence = Path(td)
            logs = evidence / "logs"
            logs.mkdir()
            log = logs / "canonical-attempt-1.log"
            log.write_text(
                "==> ./tools/agent-pipeline/run_tests.sh\n"
                "test_cycle_classifies_infrastructure ... ok\n"
                "test_handoff_summarizes_all_worker_attempts ... ERROR\n"
                "test_handoff_single_attempt_remains_compatible ... ERROR\n"
                "\n======================================================================\n"
                "ERROR: test_handoff_single_attempt_remains_compatible (test_agent_pipeline.AgentPipelineCliTest)\n"
                "----------------------------------------------------------------------\n"
                "Traceback (most recent call last):\n"
                "  File \"test_agent_pipeline.py\", line 757, in test_handoff_single_attempt_remains_compatible\n"
                "KeyError: 'model'\n"
                "\n======================================================================\n"
                "ERROR: test_handoff_summarizes_all_worker_attempts (test_agent_pipeline.AgentPipelineCliTest)\n"
                "----------------------------------------------------------------------\n"
                "Traceback (most recent call last):\n"
                "KeyError: 'model'\n"
                "\n----------------------------------------------------------------------\n"
                "Ran 3 tests in 50.415s\n\nFAILED (errors=2)\n"
                "Gitleaks: FAIL (0 findings); raw secrets were not recorded\n"
                "> Task :modules:analysis:test FAILED\n"
                "Docker connection refused\n"
                "==> Verification summary\n"
                "  [PASS] docker info\n"
                "  [FAIL] ./tools/agent-pipeline/run_tests.sh\n"
                "Checks failed with exit code 1.\n",
                encoding="utf-8",
            )
            policy = {"repairExecution": {"automaticCheckRepairNames": ["canonical"]}}
            check = {"name": "canonical", "status": "FAIL", "exitCode": 1,
                     "log": "logs/canonical-attempt-1.log", "logSha256": agent_pipeline.sha256_file(log)}
            classification, detail = agent_pipeline.classify_automatic_check_failure(evidence, check, policy)
            self.assertEqual("TEST_FAILURE", classification)
            self.assertIn("test_handoff_single_attempt_remains_compatible (KeyError)", detail)
            self.assertIn("test_handoff_summarizes_all_worker_attempts (KeyError)", detail)
            self.assertNotIn("Docker", detail)
            self.assertNotIn("'model'", detail)
            # Infrastructure evidence inside the actual failing traceback must still escalate.
            log.write_text(log.read_text().replace("KeyError: 'model'", "RuntimeError: Docker connection refused"))
            check["logSha256"] = agent_pipeline.sha256_file(log)
            self.assertEqual("INFRASTRUCTURE_OR_UNKNOWN", agent_pipeline.classify_automatic_check_failure(
                evidence, check, policy)[0])
            # A forged or missing canonical verification summary never enables auto-repair.
            log.write_text(log.read_text().replace("  [FAIL] ./tools/agent-pipeline/run_tests.sh", "  [PASS] ./tools/agent-pipeline/run_tests.sh"))
            check["logSha256"] = agent_pipeline.sha256_file(log)
            self.assertNotEqual("TEST_FAILURE", agent_pipeline.classify_automatic_check_failure(
                evidence, check, policy)[0])

    # Actual failing subTests outrank a complete synthetic unittest run printed by a passing test.
    def test_classifier_recovers_spec_driven_pilot_with_nested_false_report(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            run_dir = Path(td)
            log_dir = run_dir / "logs"
            log_dir.mkdir()
            log = log_dir / "canonical-attempt-1.log"
            progress = "".join(
                f"test_success_{n} (test_agent_pipeline.HandoffWorkerAttemptsTest) ... ok\n"
                for n in range(53)
            )
            traces = "".join(
                "======================================================================\n"
                f"ERROR: test_hi0{7 if n < 4 else 8}_attempt (test_agent_pipeline.HandoffWorkerAttemptsTest) (value={n})\n"
                "----------------------------------------------------------------------\n"
                "Traceback (most recent call last):\n"
                "TypeError: write_worker() got multiple values for argument 'attempt'\n\n"
                for n in range(8)
            )
            log.write_text(
                "==> ./tools/agent-pipeline/run_tests.sh\n" + progress + traces
                + "----------------------------------------------------------------------\n"
                + "Ran 55 tests in 58.523s\n\nFAILED (errors=8)\n"
                + "Docker connection refused\n"
                + "==> ./tools/agent-pipeline/run_tests.sh\n"
                + "test_fake_handoff (test_agent_pipeline.AgentPipelineCliTest) ... ERROR\n"
                + "======================================================================\n"
                + "ERROR: test_fake_handoff (test_agent_pipeline.AgentPipelineCliTest)\n"
                + "Traceback (most recent call last):\nKeyError: 'model'\n"
                + "----------------------------------------------------------------------\n"
                + "Ran 1 test in 1.001s\n\nFAILED (errors=1)\n"
                + "Docker connection refused\n"
                + "==> Verification summary\n"
                + "  [PASS] docker info\n"
                + "  [FAIL] ./tools/agent-pipeline/run_tests.sh\n",
                encoding="utf-8",
            )
            policy = {"repairExecution": {"automaticCheckRepairNames": ["canonical"]}}
            check = {"name": "canonical", "status": "FAIL", "exitCode": 1,
                     "log": "logs/canonical-attempt-1.log", "logSha256": agent_pipeline.sha256_file(log)}
            code, detail = agent_pipeline.classify_automatic_check_failure(run_dir, check, policy)
            self.assertEqual("TEST_FAILURE", code)
            self.assertIn("test_hi07_attempt (TypeError)", detail)
            self.assertIn("test_hi08_attempt (TypeError)", detail)
            self.assertNotIn("test_fake_handoff", detail)
            self.assertNotIn("Docker", detail)
            # The same report with a real infrastructure failure in its traceback must escalate.
            log.write_text(log.read_text().replace(
                "TypeError: write_worker() got multiple values for argument 'attempt'",
                "RuntimeError: Docker connection refused",
            ))
            check["logSha256"] = agent_pipeline.sha256_file(log)
            self.assertEqual("INFRASTRUCTURE_OR_UNKNOWN", agent_pipeline.classify_automatic_check_failure(
                run_dir, check, policy)[0])

    # A real-shaped Python unittest error triggers a bounded next attempt and conveys only safe test IDs.
    def test_cycle_repairs_python_unittest_errors_with_safe_feedback(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            command = (
                "from pathlib import Path; import sys; "
                "fixed=Path('docs/ROADMAP.md').read_text() == 'fixed\\n'; "
                "print('all tests passed' if fixed else ("
                "'==> ./tools/agent-pipeline/run_tests.sh\\n'"
                "+'======================================================================\\n'"
                "+'ERROR: test_fake_handoff (test_agent_pipeline.AgentPipelineCliTest)\\n'"
                "+'Traceback (most recent call last):\\nKeyError: \\'model\\'\\n'"
                "+'----------------------------------------------------------------------\\n'"
                "+'Ran 39 tests in 1.001s\\n\\nFAILED (errors=1)\\n'"
                "+'Docker connection refused\\n'"
                "+'==> Verification summary\\n  [FAIL] ./tools/agent-pipeline/run_tests.sh\\n')); "
                "sys.exit(0 if fixed else 1)"
            )
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "review", command)
            reviewer, calls = self.fake_reviewer(evidence, ["PASS"])
            with mock.patch.dict(os.environ, env), mock.patch.object(agent_pipeline, "run_independent_review", reviewer):
                result = agent_pipeline.run_automated_cycle(root, evidence, manifest, policy)
            self.assertEqual(0, result)
            self.assertEqual(2, agent_pipeline.read_json(evidence / "worker.json")["attempt"])
            self.assertEqual(["PASS"], calls)
            repair_task = (evidence / "repair-task-2.md").read_text()
            self.assertIn("test_fake_handoff (KeyError)", repair_task)
            repair_feedback = repair_task.split("## Bounded repair round", 1)[1]
            self.assertNotIn("Docker connection refused", repair_feedback)
            self.assertNotIn("'model'", repair_feedback)
            self.assertEqual("READY_FOR_MAIN_REVIEW", agent_pipeline.read_json(evidence / "cycle.json")["status"])

    # Provider/schema errors terminate one cycle; they do not cause additional model calls.
    def test_cycle_escalates_failed_reviewer_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "review", "print('ok')")
            with mock.patch.dict(os.environ, env), mock.patch.object(
                agent_pipeline, "run_independent_review", side_effect=ValueError("invalid reviewer schema")
            ) as reviewer:
                with self.assertRaisesRegex(ValueError, "invalid reviewer schema"):
                    agent_pipeline.run_automated_cycle(root, evidence, manifest, policy)
            reviewer.assert_called_once()
            cycle = agent_pipeline.read_json(evidence / "cycle.json")
            self.assertEqual("ESCALATED", cycle["status"])
            self.assertEqual("CONTROLLER_OR_POLICY_FAILURE", cycle["reasonCode"])
            self.assertEqual(1, agent_pipeline.read_json(evidence / "worker.json")["attempt"])
            with self.assertRaisesRegex(ValueError, "already attempted"):
                agent_pipeline.run_automated_cycle(root, evidence, manifest, policy)

    # Low-severity security/architecture escalation findings cannot be auto-repaired.
    def test_cycle_refuses_review_escalation_category(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "review", "print('ok')")
            def security_review(repo_root, run_dir, run_manifest, run_policy):
                review = agent_pipeline.validate_review({
                    "schemaVersion": 1, "reviewer": "fake-reviewer", "verdict": "CHANGES_REQUIRED",
                    "findings": [{"id": "SEC-1", "severity": "LOW", "category": "SECURITY",
                                  "summary": "Security decision required", "status": "OPEN"}],
                })
                agent_pipeline.write_json(run_dir / "review.json", review)
                agent_pipeline.write_json(run_dir / "review-attempt.json", {
                    "status": "PASS", "workerSha256": agent_pipeline.read_json(run_dir / "worker.json")["changeSha256"],
                })
                return review
            with mock.patch.dict(os.environ, env), mock.patch.object(
                agent_pipeline, "run_independent_review", security_review
            ):
                with self.assertRaisesRegex(ValueError, "escalation findings"):
                    agent_pipeline.run_automated_cycle(root, evidence, manifest, policy)
            self.assertEqual("ESCALATED", agent_pipeline.read_json(evidence / "cycle.json")["status"])
            self.assertEqual(1, agent_pipeline.read_json(evidence / "worker.json")["attempt"])

    # Policy rejects automatic repair of scanner gates or unlisted checks.
    def test_policy_requires_explicit_non_security_repair_allowlist(self) -> None:
        test_policy = {
            "schemaVersion": 1,
            "requiredChecks": [{"name": "canonical", "command": ["true"]}],
            "repairExecution": {"maxRepairAttempts": 3, "automaticCheckRepairNames": ["missing"]},
        }
        with self.assertRaisesRegex(ValueError, "automaticCheckRepairNames"):
            agent_pipeline.validate_policy(test_policy)
        test_policy["repairExecution"]["automaticCheckRepairNames"] = ["canonical"]
        agent_pipeline.validate_policy(test_policy)

    # A repair that leaves the previous patch unchanged is not counted as a successful worker round.
    def test_cycle_escalates_repair_without_new_changes(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            check = ("from pathlib import Path; import sys; "
                     "print('> Task :modules:analysis:test FAILED\\nThere were failing tests'); sys.exit(1)")
            root, evidence, manifest, policy, env = self.prepare_cycle(Path(td), "no-progress", check)
            reviewer, calls = self.fake_reviewer(evidence, ["PASS"])
            with mock.patch.dict(os.environ, env), mock.patch.object(agent_pipeline, "run_independent_review", reviewer):
                self.assertEqual(1, agent_pipeline.run_automated_cycle(root, evidence, manifest, policy))
            self.assertEqual("WORKER_EXECUTION_FAILURE", agent_pipeline.read_json(evidence / "cycle.json")["reasonCode"])
            worker = agent_pipeline.read_json(evidence / "worker.json")
            self.assertEqual(2, worker["attempt"])
            self.assertEqual("worker repair made no source changes", worker["failureReason"])
            self.assertEqual([], calls)


class HandoffWorkerAttemptsTest(unittest.TestCase):
    """Protects compact, source-bound workerAttempts evidence in handoff.json."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run_dir = Path(self.temp.name) / "run"
        self.run_dir.mkdir()

    def write_worker(self, attempt: int, **overrides: object) -> dict:
        record = {
            "schemaVersion": 1,
            "attempt": attempt,
            "status": "PASS",
            "failureReason": None,
            "model": "deepseek/deepseek-chat",
            "changeSha256": f"{attempt:064d}",
            "changedFiles": [f"docs/file-{attempt}.md"],
            "taskSha256": f"task-{attempt}",
            "logSha256": f"log-{attempt}",
            "command": ["aider", "--model", "deepseek/deepseek-chat"],
            "preflight": {"status": "PASS"},
        }
        record.update(overrides)
        return record

    def write_snapshot(self, attempt: int, record: dict) -> None:
        agent_pipeline.write_json(self.run_dir / f"worker-attempt-{attempt}.json", record)

    def write_latest(self, record: dict) -> None:
        agent_pipeline.write_json(self.run_dir / "worker.json", record)

    # HI-01: no worker evidence yields an empty list without dropping legacy keys.
    def test_hi01_no_worker_evidence(self) -> None:
        self.assertEqual([], agent_pipeline.collect_worker_attempts(self.run_dir))

    # HI-02: legacy single worker.json without a snapshot yields one entry and no invented file.
    def test_hi02_legacy_single_worker(self) -> None:
        self.write_latest(self.write_worker(1))
        attempts = agent_pipeline.collect_worker_attempts(self.run_dir)
        self.assertEqual(1, len(attempts))
        self.assertNotIn("evidenceFile", attempts[0])
        self.assertNotIn("evidenceSha256", attempts[0])

    # HI-03: matching snapshot and authoritative worker produce a digest reference.
    def test_hi03_single_attempt_with_snapshot(self) -> None:
        record = self.write_worker(1)
        self.write_snapshot(1, record)
        self.write_latest(record)
        attempts = agent_pipeline.collect_worker_attempts(self.run_dir)
        self.assertEqual(1, len(attempts))
        self.assertEqual("worker-attempt-1.json", attempts[0]["evidenceFile"])
        self.assertEqual(
            agent_pipeline.sha256_file(self.run_dir / "worker-attempt-1.json"),
            attempts[0]["evidenceSha256"],
        )

    # HI-04: multiple snapshots are ordered numerically with correct digests.
    def test_hi04_multiple_attempts_ordered(self) -> None:
        for attempt in (1, 2, 3):
            self.write_snapshot(attempt, self.write_worker(attempt))
        self.write_latest(self.write_worker(3))
        attempts = agent_pipeline.collect_worker_attempts(self.run_dir)
        self.assertEqual([1, 2, 3], [entry["attempt"] for entry in attempts])
        for entry in attempts:
            self.assertEqual(
                agent_pipeline.sha256_file(self.run_dir / entry["evidenceFile"]),
                entry["evidenceSha256"],
            )

    # HI-05: malformed snapshot JSON fails closed.
    def test_hi05_malformed_snapshot(self) -> None:
        (self.run_dir / "worker-attempt-1.json").write_text("[1, 2]\n", encoding="utf-8")
        self.write_latest(self.write_worker(1))
        with self.assertRaises(ValueError):
            agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-06: noncanonical snapshot filenames are rejected.
    def test_hi06_noncanonical_filename(self) -> None:
        for name in ("worker-attempt-01.json", "worker-attempt-0.json"):
            with self.subTest(name=name):
                for existing in self.run_dir.glob("worker-attempt-*.json"):
                    existing.unlink()
                (self.run_dir / name).write_text("{}\n", encoding="utf-8")
                self.write_latest(self.write_worker(1))
                with self.assertRaises(ValueError):
                    agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-07: snapshot payload attempt identifiers must be strict integers matching the filename.
    def test_hi07_mismatched_snapshot_attempt(self) -> None:
        for value in (2, True, "1", 1.0):
            with self.subTest(value=value):
                for existing in self.run_dir.glob("worker-attempt-*.json"):
                    existing.unlink()
                record = self.write_worker(1)
                record["attempt"] = value
                self.write_snapshot(1, record)
                self.write_latest(self.write_worker(1))
                with self.assertRaises(ValueError):
                    agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-08: authoritative worker attempt identifiers must be strict positive integers.
    def test_hi08_invalid_authoritative_attempt(self) -> None:
        for value in (True, 0, "1", 1.0):
            with self.subTest(value=value):
                record = self.write_worker(1)
                record["attempt"] = value
                self.write_latest(record)
                with self.assertRaises(ValueError):
                    agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-09: a missing intermediate snapshot is rejected rather than silently omitted.
    def test_hi09_missing_intermediate_snapshot(self) -> None:
        self.write_snapshot(1, self.write_worker(1))
        self.write_snapshot(3, self.write_worker(3))
        self.write_latest(self.write_worker(3))
        with self.assertRaises(ValueError):
            agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-10: a stale/future snapshot beyond the authoritative attempt is rejected.
    def test_hi10_stale_future_snapshot(self) -> None:
        self.write_snapshot(1, self.write_worker(1))
        self.write_snapshot(2, self.write_worker(2))
        self.write_latest(self.write_worker(1))
        with self.assertRaises(ValueError):
            agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-11: snapshots without an authoritative worker.json fail closed.
    def test_hi11_missing_authoritative_worker(self) -> None:
        self.write_snapshot(1, self.write_worker(1))
        with self.assertRaises(ValueError):
            agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-12: any field mismatch between the latest worker and its snapshot is rejected.
    def test_hi12_latest_worker_snapshot_mismatch(self) -> None:
        for field, value in (
            ("taskSha256", "different-task"),
            ("model", "other/model"),
            ("logSha256", "different-log"),
            ("command", ["aider", "--other"]),
            ("preflight", {"status": "FAIL"}),
        ):
            with self.subTest(field=field):
                for existing in self.run_dir.glob("worker-attempt-*.json"):
                    existing.unlink()
                self.write_snapshot(1, self.write_worker(1))
                self.write_latest(self.write_worker(1, **{field: value}))
                with self.assertRaises(ValueError):
                    agent_pipeline.collect_worker_attempts(self.run_dir)

    # HI-13: raw logs, commands, and model transcripts are not copied into the compact list.
    def test_hi13_no_raw_transcripts_in_summary(self) -> None:
        record = self.write_worker(1)
        record["command"] = ["aider", "--model", "deepseek/deepseek-chat"]
        record["preflight"] = {"status": "PASS", "rawOutput": "secret transcript"}
        record["log"] = "logs/worker-attempt-1.log"
        self.write_snapshot(1, record)
        self.write_latest(record)
        attempts = agent_pipeline.collect_worker_attempts(self.run_dir)
        self.assertNotIn("command", attempts[0])
        self.assertNotIn("preflight", attempts[0])
        self.assertNotIn("log", attempts[0])
        self.assertNotIn("rawOutput", json.dumps(attempts[0]))

    # HI-14: the published digest binds the current snapshot bytes, not a historical trust anchor.
    def test_hi14_digest_binds_current_snapshot_bytes(self) -> None:
        record = self.write_worker(1)
        self.write_snapshot(1, record)
        self.write_latest(record)
        attempts = agent_pipeline.collect_worker_attempts(self.run_dir)
        published = attempts[0]["evidenceSha256"]
        self.write_snapshot(1, self.write_worker(1, status="FAIL"))
        self.assertNotEqual(published, agent_pipeline.sha256_file(self.run_dir / "worker-attempt-1.json"))


class RunEvidenceSummaryTest(unittest.TestCase):
    """Protects the A4.2a read-only single-run evidence aggregator (EA-01..EA-12)."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run_dir = Path(self.temp.name) / "run"
        self.run_dir.mkdir()
        self.manifest = {
            "schemaVersion": 1,
            "runId": "run-EA-01",
            "stage": "A4",
            "specPath": "docs/specs/active/subspecs/development-agent-pipeline.md",
            "taskSha256": "1" * 64,
            "policy": {"sha256": "2" * 64},
            "repository": {"baseCommit": "3" * 40},
            "requiredChecks": ["gitleaks", "canonical"],
        }
        self.worker = {
            "schemaVersion": 1,
            "attempt": 1,
            "status": "PASS",
            "failureReason": None,
            "taskSha256": "1" * 64,
            "model": "worker",
            "changeSha256": "4" * 64,
            "changedFiles": ["tools/agent-pipeline/agent_pipeline.py"],
            "logSha256": "5" * 64,
            "command": ["raw_command_secret"],
            "log": "raw_log_secret",
            "preflight": {"rawOutput": "raw_transcript_secret"},
        }
        self.checks = [
            {"name": "gitleaks", "attempt": 1, "status": "PASS", "required": True,
             "command": ["raw_command_secret"]},
            {"name": "canonical", "attempt": 1, "status": "FAIL", "required": True,
             "log": "raw_log_secret"},
        ]
        self.status = {
            "schemaVersion": 1,
            "runId": "run-EA-01",
            "stage": "A4",
            "state": "BLOCKED",
            "checks": {
                "gitleaks": {"attempt": 1, "status": "PASS", "required": True},
                "canonical": {"attempt": 1, "status": "FAIL", "required": True},
            },
            "worker": {"present": True, "required": True, "status": "PASS"},
            "review": {"present": False, "verdict": None, "openFindingCount": 0},
            "mainReviewRequiredBeforeAcceptance": True,
        }
        self.persist()

    def persist(self) -> None:
        agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        agent_pipeline.write_json(self.run_dir / "checks.json", {"schemaVersion": 1, "checks": self.checks})
        agent_pipeline.write_json(self.run_dir / "status.json", self.status)

    def report(self) -> dict:
        return agent_pipeline.summarize_run_evidence(self.run_dir)

    def reject(self) -> None:
        with self.assertRaises((ValueError, TypeError, KeyError, json.JSONDecodeError)):
            self.report()

    def ready(self) -> None:
        self.checks[1]["status"] = "PASS"
        self.status["checks"]["canonical"]["status"] = "PASS"
        self.status["state"] = "READY_FOR_MAIN_REVIEW"
        self.status["review"] = {"present": True, "verdict": "PASS", "openFindingCount": 0}
        self.persist()
        agent_pipeline.write_json(
            self.run_dir / "review.json",
            {"schemaVersion": 1, "reviewer": "reviewer", "verdict": "PASS", "findings": []},
        )

    # EA-01: a blocked run reports source identity and never claims acceptance.
    def test_ea01_blocked_source_identity(self) -> None:
        report = self.report()
        self.assertEqual(("run-EA-01", "A4", "BLOCKED"),
                         (report["runId"], report["stage"], report["state"]))
        self.assertEqual("3" * 40, report["baseCommit"])
        self.assertEqual("1" * 64, report["taskSha256"])
        self.assertEqual("2" * 64, report["policySha256"])
        self.assertTrue(report["requiresExternalAcceptance"])
        self.assertNotIn("accepted", report)

    # EA-02: a ready run requires explicit PASS evidence but still requires external acceptance.
    def test_ea02_ready_requires_explicit_pass_but_not_accepted(self) -> None:
        self.ready()
        result = self.report()
        self.assertEqual("READY_FOR_MAIN_REVIEW", result["state"])
        self.assertTrue(result["review"]["present"])
        self.assertEqual("PASS", result["review"]["verdict"])
        self.assertEqual({"PASS"}, {item["status"] for item in result["checks"]})
        self.assertTrue(result["requiresExternalAcceptance"])
        self.assertNotIn("accepted", result)

    # EA-03: a missing review is explicitly absent, never an implied PASS.
    def test_ea03_no_review_never_becomes_pass(self) -> None:
        result = self.report()
        self.assertFalse(result["review"]["present"])
        self.assertIsNone(result["review"]["verdict"])
        self.assertNotIn("review.json", result["evidenceDigests"])

    # EA-04: multiple snapshots are ordered numerically with digest-linked evidence.
    def test_ea04_ordered_snapshot_digests(self) -> None:
        for n in (1, 2, 3):
            agent_pipeline.write_json(
                self.run_dir / f"worker-attempt-{n}.json", dict(self.worker, attempt=n)
            )
        self.worker["attempt"] = 3
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        result = self.report()
        self.assertEqual([1, 2, 3], [x["attempt"] for x in result["workerAttempts"]])
        for x in result["workerAttempts"]:
            self.assertEqual(
                agent_pipeline.sha256_file(self.run_dir / x["evidenceFile"]),
                x["evidenceSha256"],
            )

    # EA-05: a pre-worker run reports empty history without inventing a worker digest.
    def test_ea05_missing_worker_is_not_invented(self) -> None:
        (self.run_dir / "worker.json").unlink()
        self.status["worker"] = {"present": False, "required": True, "status": None}
        agent_pipeline.write_json(self.run_dir / "status.json", self.status)
        result = self.report()
        self.assertEqual([], result["workerAttempts"])
        self.assertNotIn("worker.json", result["evidenceDigests"])

    # EA-06: malformed or array-shaped required JSON fails closed.
    def test_ea06_malformed_or_array_manifest_fails_closed(self) -> None:
        for payload in ("[]\n", "{broken"):
            with self.subTest(payload=payload):
                (self.run_dir / "manifest.json").write_text(payload, encoding="utf-8")
                self.reject()

    # EA-07: a latest worker that conflicts with its snapshot fails closed.
    def test_ea07_latest_snapshot_mismatch(self) -> None:
        agent_pipeline.write_json(self.run_dir / "worker-attempt-1.json", self.worker)
        self.worker["model"] = "changed"
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        self.reject()

    # EA-08: a worker or snapshot task digest that disagrees with the manifest is rejected.
    def test_ea08_task_sha_mismatch_latest_or_snapshot(self) -> None:
        self.worker["taskSha256"] = "9" * 64
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        self.reject()
        self.worker["taskSha256"] = "1" * 64
        self.persist()
        agent_pipeline.write_json(
            self.run_dir / "worker-attempt-1.json", dict(self.worker, taskSha256="9" * 64)
        )
        self.reject()

    # EA-09: a latest check record that disagrees with status.json fails closed.
    def test_ea09_status_check_disagreement(self) -> None:
        self.status["checks"]["canonical"]["status"] = "PASS"
        agent_pipeline.write_json(self.run_dir / "status.json", self.status)
        self.reject()

    # EA-10: READY without a required PASS check or independent PASS review is rejected.
    def test_ea10_unsupported_ready_without_check_or_review(self) -> None:
        self.status["state"] = "READY_FOR_MAIN_REVIEW"
        agent_pipeline.write_json(self.run_dir / "status.json", self.status)
        self.reject()
        self.checks[1]["status"] = "PASS"
        self.status["checks"]["canonical"]["status"] = "PASS"
        self.persist()
        self.reject()

    # EA-11: raw commands, logs, and transcripts never appear in the summary.
    def test_ea11_no_raw_fields(self) -> None:
        result = json.dumps(self.report())
        for needle in ("raw_command_secret", "raw_log_secret", "raw_transcript_secret"):
            self.assertNotIn(needle, result)

    # EA-12: published digests bind current file bytes, not a historical trust anchor.
    def test_ea12_file_digest_current_bytes(self) -> None:
        result = self.report()
        self.assertEqual(
            agent_pipeline.sha256_file(self.run_dir / "checks.json"),
            result["evidenceDigests"]["checks.json"],
        )
        with (self.run_dir / "checks.json").open("ab") as out:
            out.write(b"\n")
        self.assertNotEqual(
            agent_pipeline.sha256_file(self.run_dir / "checks.json"),
            result["evidenceDigests"]["checks.json"],
        )

    def test_aggregate_rejects_check_present_only_in_checks_json(self) -> None:
        self.status["checks"].pop("canonical")
        self.persist()
        self.reject()

    def test_aggregate_rejects_ready_check_not_marked_required(self) -> None:
        self.ready()
        self.checks[1]["required"] = False
        self.status["checks"]["canonical"]["required"] = False
        self.persist()
        self.reject()

    def test_aggregate_rejects_review_status_disagreement(self) -> None:
        self.ready()
        self.status["review"]["verdict"] = "CHANGES_REQUIRED"
        self.persist()
        self.reject()

    def test_aggregate_rejects_noninteger_check_attempt(self) -> None:
        self.checks[1]["attempt"] = True
        self.status["checks"]["canonical"]["attempt"] = True
        self.persist()
        self.reject()

    def test_aggregate_rejects_duplicate_required_check_names(self) -> None:
        self.manifest["requiredChecks"].append("canonical")
        self.persist()
        self.reject()


class RunDeviationEvidenceTest(unittest.TestCase):
    """Protects A4.2b read-only structured worker-deviation evidence (DV-01..DV-20)."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run_dir = Path(self.temp.name) / "run"
        self.run_dir.mkdir()
        self.manifest = {
            "schemaVersion": 1,
            "runId": "run-DV",
            "stage": "A4",
            "specPath": "docs/specs/active/subspecs/development-agent-pipeline.md",
            "taskSha256": "1" * 64,
            "policy": {"sha256": "2" * 64},
            "repository": {"baseCommit": "3" * 40},
            "requiredChecks": ["gitleaks", "canonical"],
            "stagePacket": {"requirementIds": ["AP9", "AP10"]},
        }
        self.worker = {
            "schemaVersion": 1,
            "attempt": 1,
            "status": "PASS",
            "failureReason": None,
            "taskSha256": "1" * 64,
            "model": "worker",
            "changeSha256": "4" * 64,
            "changedFiles": ["tools/agent-pipeline/agent_pipeline.py"],
            "logSha256": "5" * 64,
        }
        agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)

    def write_worker(self, attempt: int, **overrides: object) -> dict:
        record = dict(self.worker, attempt=attempt)
        record.update(overrides)
        return record

    def write_deviation(self, attempt: int, payload: dict) -> None:
        agent_pipeline.write_json(self.run_dir / f"worker-deviations-attempt-{attempt}.json", payload)

    def valid_payload(self, attempt: int = 1, **overrides: object) -> dict:
        payload = {
            "schemaVersion": 1,
            "runId": "run-DV",
            "stage": "A4",
            "attempt": attempt,
            "taskSha256": "1" * 64,
            "changeSha256": "4" * 64,
            "reportedBy": "WORKER_MODEL_CLAIM",
            "deviations": [{
                "id": "DEV-001",
                "kind": "DESIGN_ALTERNATIVE",
                "requirementIds": ["AP9"],
                "summary": "Used an existing reader instead of a second parser",
                "rationale": "Avoided duplicate input validation",
                "testImpact": "Added a malformed-record regression test",
                "affectedPaths": ["tools/agent-pipeline/agent_pipeline.py"],
                "riskImpact": "NONE_IDENTIFIED",
            }],
        }
        payload.update(overrides)
        return payload

    def collect(self) -> list[dict]:
        return agent_pipeline.collect_deviation_summaries(self.run_dir)

    def reject(self) -> None:
        with self.assertRaises((ValueError, TypeError, KeyError, json.JSONDecodeError)):
            self.collect()

    # DV-01: no deviation file yields UNREPORTED without an invented digest.
    def test_dv01_unreported(self) -> None:
        result = self.collect()
        self.assertEqual([{"attempt": 1, "state": "UNREPORTED"}], result)

    # DV-02: an explicit empty list is REPORTED_NONE with a digest.
    def test_dv02_reported_none(self) -> None:
        self.write_deviation(1, self.valid_payload(deviations=[]))
        result = self.collect()
        self.assertEqual("REPORTED_NONE", result[0]["state"])
        self.assertEqual("WORKER_MODEL_CLAIM", result[0]["source"])
        self.assertEqual(0, result[0]["count"])
        self.assertEqual(
            agent_pipeline.sha256_file(self.run_dir / "worker-deviations-attempt-1.json"),
            result[0]["evidenceSha256"],
        )

    # DV-03: a bounded design alternative is REPORTED with safe categorical metadata only.
    def test_dv03_reported_bounded(self) -> None:
        self.write_deviation(1, self.valid_payload())
        result = self.collect()
        self.assertEqual("REPORTED", result[0]["state"])
        self.assertEqual(1, result[0]["count"])
        self.assertEqual(
            {"id": "DEV-001", "kind": "DESIGN_ALTERNATIVE", "riskImpact": "NONE_IDENTIFIED"},
            result[0]["entries"][0],
        )
        self.assertNotIn("summary", json.dumps(result[0]))

    # DV-04: multiple attempts are ordered numerically with UNREPORTED gaps preserved.
    def test_dv04_numeric_ordering_with_gap(self) -> None:
        for attempt in (1, 2, 3):
            agent_pipeline.write_json(self.run_dir / f"worker-attempt-{attempt}.json", self.write_worker(attempt))
        self.worker["attempt"] = 3
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        self.write_deviation(1, self.valid_payload(attempt=1))
        self.write_deviation(3, self.valid_payload(attempt=3, changeSha256="4" * 64))
        result = self.collect()
        self.assertEqual([1, 2, 3], [entry["attempt"] for entry in result])
        self.assertEqual(["REPORTED", "UNREPORTED", "REPORTED"], [entry["state"] for entry in result])

    # DV-05: noncanonical filenames are rejected.
    def test_dv05_noncanonical_filename(self) -> None:
        for name in ("worker-deviations-attempt-01.json", "worker-deviations-attempt-0.json"):
            with self.subTest(name=name):
                for existing in self.run_dir.glob("worker-deviations-attempt-*.json"):
                    existing.unlink()
                (self.run_dir / name).write_text("{}\n", encoding="utf-8")
                self.reject()

    # DV-06: payload attempt identifiers must be strict integers matching the filename.
    def test_dv06_mismatched_payload_attempt(self) -> None:
        for value in (2, True, "1", 1.0):
            with self.subTest(value=value):
                self.write_deviation(1, self.valid_payload(attempt=value))
                self.reject()

    # DV-07: a deviation file for an absent worker attempt is rejected.
    def test_dv07_orphan_deviation(self) -> None:
        self.write_deviation(2, self.valid_payload(attempt=2))
        self.reject()

    # DV-08: malformed JSON or array-shaped payloads fail closed.
    def test_dv08_malformed_payload(self) -> None:
        for payload in ("[]\n", "{broken"):
            with self.subTest(payload=payload):
                (self.run_dir / "worker-deviations-attempt-1.json").write_text(payload, encoding="utf-8")
                self.reject()

    # DV-09: mismatched runId, stage or task digest fails closed.
    def test_dv09_identity_mismatch(self) -> None:
        for key, value in (("runId", "other"), ("stage", "A5"), ("taskSha256", "9" * 64)):
            with self.subTest(key=key):
                self.write_deviation(1, self.valid_payload(**{key: value}))
                self.reject()

    # DV-10: a mismatched changeSha256 fails closed.
    def test_dv10_change_sha_mismatch(self) -> None:
        self.write_deviation(1, self.valid_payload(changeSha256="9" * 64))
        self.reject()

    # DV-11: duplicate or unsorted deviation IDs are rejected.
    def test_dv11_duplicate_ids(self) -> None:
        entry = self.valid_payload()["deviations"][0]
        self.write_deviation(1, self.valid_payload(deviations=[entry, dict(entry)]))
        self.reject()

    # DV-12: unknown kind, riskImpact or extra keys are rejected.
    def test_dv12_unsupported_schema(self) -> None:
        for key, value in (("kind", "UNKNOWN"), ("riskImpact", "UNKNOWN"), ("extra", "x")):
            with self.subTest(key=key):
                entry = dict(self.valid_payload()["deviations"][0])
                entry[key] = value
                self.write_deviation(1, self.valid_payload(deviations=[entry]))
                self.reject()

    # DV-13: absolute, traversal, duplicate or unchanged affectedPaths are rejected.
    def test_dv13_unsupported_scope_paths(self) -> None:
        for path in ("/etc/passwd", "../secret", "docs/other.md"):
            with self.subTest(path=path):
                entry = dict(self.valid_payload()["deviations"][0])
                entry["affectedPaths"] = [path]
                self.write_deviation(1, self.valid_payload(deviations=[entry]))
                self.reject()
        entry = dict(self.valid_payload()["deviations"][0])
        entry["affectedPaths"] = ["tools/agent-pipeline/agent_pipeline.py",
                                  "tools/agent-pipeline/agent_pipeline.py"]
        self.write_deviation(1, self.valid_payload(deviations=[entry]))
        self.reject()

    # DV-14: requirementIds outside the stage packet list are rejected.
    def test_dv14_unauthorized_requirement(self) -> None:
        entry = dict(self.valid_payload()["deviations"][0])
        entry["requirementIds"] = ["AP999"]
        self.write_deviation(1, self.valid_payload(deviations=[entry]))
        self.reject()

    # DV-15: excess records or overlong text are rejected.
    def test_dv15_bounded_size_violation(self) -> None:
        entry = self.valid_payload()["deviations"][0]
        self.write_deviation(1, self.valid_payload(deviations=[entry] * 21))
        self.reject()
        long_entry = dict(entry)
        long_entry["summary"] = "x" * 501
        self.write_deviation(1, self.valid_payload(deviations=[long_entry]))
        self.reject()

    # DV-16: raw narrative and command-like input never appear in the compact output.
    def test_dv16_no_raw_text_in_output(self) -> None:
        entry = dict(self.valid_payload()["deviations"][0])
        entry["rationale"] = "raw_command_secret"
        entry["testImpact"] = "raw_log_secret"
        self.write_deviation(1, self.valid_payload(deviations=[entry]))
        result = json.dumps(self.collect())
        self.assertNotIn("raw_command_secret", result)
        self.assertNotIn("raw_log_secret", result)

    # DV-17: the published digest binds current bytes, not a historical trust anchor.
    def test_dv17_digest_binds_current_bytes(self) -> None:
        self.write_deviation(1, self.valid_payload())
        published = self.collect()[0]["evidenceSha256"]
        self.write_deviation(1, self.valid_payload(deviations=[]))
        self.assertNotEqual(
            published,
            agent_pipeline.sha256_file(self.run_dir / "worker-deviations-attempt-1.json"),
        )

    # DV-18: a deviation record without a valid worker history fails closed.
    def test_dv18_missing_worker_history(self) -> None:
        (self.run_dir / "worker.json").unlink()
        self.write_deviation(1, self.valid_payload())
        self.reject()

    # DV-19: a legacy worker without a stage packet can report an empty requirement list.
    def test_dv19_legacy_worker_without_stage_packet(self) -> None:
        self.manifest.pop("stagePacket")
        agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
        entry = dict(self.valid_payload()["deviations"][0])
        entry["requirementIds"] = []
        self.write_deviation(1, self.valid_payload(deviations=[entry]))
        result = self.collect()
        self.assertEqual("REPORTED", result[0]["state"])
        self.assertEqual([], result[0]["entries"][0].get("requirementIds", []))

    # DV-20: REQUIRES_DECISION is visible but never accepted or promoted.
    def test_dv20_requires_decision_visible_not_accepted(self) -> None:
        entry = dict(self.valid_payload()["deviations"][0])
        entry["riskImpact"] = "REQUIRES_DECISION"
        self.write_deviation(1, self.valid_payload(deviations=[entry]))
        result = self.collect()
        self.assertEqual("REQUIRES_DECISION", result[0]["entries"][0]["riskImpact"])
        self.assertNotIn("accepted", json.dumps(result))
        self.assertNotIn("approved", json.dumps(result))

    # No writes to run files and no symlink traversal.
    def test_no_writes_and_no_symlink_traversal(self) -> None:
        self.write_deviation(1, self.valid_payload())
        before = {path.name: agent_pipeline.sha256_file(path)
                  for path in self.run_dir.iterdir() if path.is_file()}
        self.collect()
        after = {path.name: agent_pipeline.sha256_file(path)
                 for path in self.run_dir.iterdir() if path.is_file()}
        self.assertEqual(before, after)
        outside = Path(self.temp.name) / "outside.json"
        outside.write_text(json.dumps(self.valid_payload()), encoding="utf-8")
        link = self.run_dir / "worker-deviations-attempt-2.json"
        link.symlink_to(outside)
        agent_pipeline.write_json(self.run_dir / "worker-attempt-2.json", self.write_worker(2))
        self.worker["attempt"] = 2
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        self.reject()

    # summarize_run_evidence preserves existing keys and adds only a non-authoritative projection.
    def test_summary_projection_is_additive_and_non_authoritative(self) -> None:
        self.write_deviation(1, self.valid_payload())
        self.manifest["requiredChecks"] = ["canonical"]
        agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
        agent_pipeline.write_json(self.run_dir / "checks.json", {"schemaVersion": 1, "checks": [
            {"name": "canonical", "attempt": 1, "status": "FAIL", "required": True},
        ]})
        agent_pipeline.write_json(self.run_dir / "status.json", {
            "schemaVersion": 1, "runId": "run-DV", "stage": "A4", "state": "BLOCKED",
            "checks": {"canonical": {"attempt": 1, "status": "FAIL", "required": True}},
            "worker": {"present": True, "required": True, "status": "PASS"},
            "review": {"present": False, "verdict": None, "openFindingCount": 0},
            "mainReviewRequiredBeforeAcceptance": True,
        })
        report = agent_pipeline.summarize_run_evidence(self.run_dir)
        for key in ("schemaVersion", "runId", "stage", "baseCommit", "taskSha256",
                    "policySha256", "state", "workerAttempts", "checks", "review",
                    "evidenceDigests", "requiresExternalAcceptance"):
            self.assertIn(key, report)
        self.assertEqual("REPORTED", report["deviations"][0]["state"])
        self.assertTrue(report["requiresExternalAcceptance"])
        self.assertNotIn("accepted", json.dumps(report))


    def test_recovery_unsorted_ids_with_distinct_affected_paths(self) -> None:
        second_path = "tools/agent-pipeline/tests/test_agent_pipeline.py"
        self.worker["changedFiles"].append(second_path)
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        first = dict(self.valid_payload()["deviations"][0], id="DEV-002")
        second = dict(first, id="DEV-001", affectedPaths=[second_path])
        self.write_deviation(1, self.valid_payload(deviations=[first, second]))
        with self.assertRaisesRegex(ValueError, "numerically ascending"):
            self.collect()
        self.write_deviation(1, self.valid_payload(deviations=[second, first]))
        self.assertEqual(2, self.collect()[0]["count"])

    def test_recovery_shared_path_between_distinct_deviations_is_valid(self) -> None:
        first = self.valid_payload()["deviations"][0]
        second = dict(first, id="DEV-002", kind="TEST_STRATEGY")
        self.write_deviation(1, self.valid_payload(deviations=[first, second]))
        self.assertEqual(["DEV-001", "DEV-002"],
                         [entry["id"] for entry in self.collect()[0]["entries"]])

    def test_recovery_rejects_malformed_matching_digest_and_worker_task_drift(self) -> None:
        for field, invalid in (("taskSha256", "X" * 64),
                               ("changeSha256", "ABCDEF" * 10 + "ABCD")):
            with self.subTest(field=field):
                saved_manifest = dict(self.manifest)
                saved_worker = dict(self.worker)
                payload = self.valid_payload()
                if field == "taskSha256":
                    self.manifest[field] = invalid
                    self.worker[field] = invalid
                    agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
                else:
                    self.worker[field] = invalid
                agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
                payload[field] = invalid
                self.write_deviation(1, payload)
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    self.collect()
                self.manifest = saved_manifest
                self.worker = saved_worker
                agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
                agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        self.worker["taskSha256"] = "a" * 64
        agent_pipeline.write_json(self.run_dir / "worker.json", self.worker)
        self.write_deviation(1, self.valid_payload())
        with self.assertRaisesRegex(ValueError, "Worker taskSha256"):
            self.collect()

    def test_recovery_rejects_noncanonical_filename_suffix(self) -> None:
        self.write_deviation(1, self.valid_payload())
        extra = self.run_dir / "worker-deviations-attempt-1.json.bak"
        extra.write_text("{}\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "filename"):
            self.collect()

    def test_recovery_rejects_malformed_authoritative_identity_and_enums(self) -> None:
        for field in ("runId", "stage"):
            with self.subTest(field=field):
                value = self.manifest[field]
                self.manifest[field] = " "
                agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
                self.write_deviation(1, self.valid_payload(**{field: " "}))
                with self.assertRaisesRegex(ValueError, "non-empty"):
                    self.collect()
                self.manifest[field] = value
                agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
        for field in ("kind", "riskImpact"):
            with self.subTest(field=field):
                entry = dict(self.valid_payload()["deviations"][0])
                entry[field] = ["DESIGN_ALTERNATIVE"]
                self.write_deviation(1, self.valid_payload(deviations=[entry]))
                with self.assertRaises(ValueError):
                    self.collect()
        self.manifest["stagePacket"] = {"requirementIds": "AP9"}
        agent_pipeline.write_json(self.run_dir / "manifest.json", self.manifest)
        self.write_deviation(1, self.valid_payload())
        with self.assertRaisesRegex(ValueError, "stagePacket requirementIds"):
            self.collect()


class AgentPipelineStagePacketTest(unittest.TestCase):
    """Protects bounded A4 stage packets, advisory context and explicit editable scope."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for relative, content in {
            "README.md": "# Root\n",
            "AGENTS.md": "# Root agent rules\n",
            "docs/specs/README.md": "# Lifecycle\n",
            "docs/TESTS.md": "# Tests\n",
            "docs/specs/active/subspecs/pipeline.md": "---\nspec_status: active\n---\n# A4\nAP9 and AP10\n",
            "docs/specs/active/subspecs/pipeline-implementation-plan.md": "# Advisory A4 plan\n",
            "tools/agent-pipeline/README.md": "# Owner\n",
            "tools/agent-pipeline/tests/existing.py": "# Existing test\n",
            "modules/other/contract.md": "# Contract\n",
            "modules/other/src/main/java/io/test/impl/Private.java": "private\n",
        }.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.packet = {
            "schemaVersion": 1,
            "stageId": "A4",
            "specPath": "docs/specs/active/subspecs/pipeline.md",
            "implementationPlanPath": "docs/specs/active/subspecs/pipeline-implementation-plan.md",
            "requirementIds": ["AP9"],
            "featureIds": [],
            "owningPaths": ["tools/agent-pipeline"],
            "editablePaths": ["tools/agent-pipeline/tests/existing.py"],
            "contextPaths": ["docs/TESTS.md", "modules/other/contract.md"],
            "riskLevel": "LOW",
            "delegation": "BOUNDED",
            "task": "Add a bounded regression scenario.",
            "completionCriteria": ["A regression test describes observable behavior."],
        }

    # Only complete, bounded and spec-linked packets may be delegated.
    def test_stage_packet_resolves_advisory_context(self) -> None:
        packet, context = agent_pipeline.validate_stage_packet(self.root, self.packet)
        self.assertEqual("A4", packet["stageId"])
        self.assertIn("AGENTS.md", context)
        self.assertIn("tools/agent-pipeline/README.md", context)
        self.assertIn(packet["specPath"], context)
        self.assertIn(packet["implementationPlanPath"], context)
        self.assertIn("modules/other/contract.md", context)

    # Unknown requirements, missing plans and non-low-risk stages fail before model access.
    def test_stage_packet_rejects_unapproved_or_stale_authority(self) -> None:
        for key, value in (("requirementIds", ["AP777"]), ("riskLevel", "HIGH"),
                           ("delegation", "DISCOVERY"), ("implementationPlanPath", None)):
            changed = {**self.packet, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                agent_pipeline.validate_stage_packet(self.root, changed)

    # A directory authorization cannot silently include repository rules or the active specification.
    def test_stage_packet_rejects_protected_and_cross_owner_paths(self) -> None:
        for path in ("tools/agent-pipeline/", "docs/specs/active/subspecs/pipeline.md",
                     "tools/agent-pipeline/pipeline-policy.json", "../secret", "/tmp/elsewhere"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                agent_pipeline.validate_stage_packet(self.root, {**self.packet, "editablePaths": [path]})

    # Cross-module read context must be a published contract or API, not private implementation.
    def test_stage_packet_rejects_private_cross_module_context(self) -> None:
        changed = {**self.packet, "contextPaths": ["modules/other/src/main/java/io/test/impl/Private.java"]}
        with self.assertRaisesRegex(ValueError, "Cross-module private"):
            agent_pipeline.validate_stage_packet(self.root, changed)

    # Stage source digests and immutable packet evidence prevent silent changes after approval.
    def test_stage_packet_read_only_drift_blocks_execution(self) -> None:
        _, paths = agent_pipeline.validate_stage_packet(self.root, self.packet)
        run_dir = self.root / "evidence"
        run_dir.mkdir()
        packet_path = run_dir / "stage-packet.json"
        packet_path.write_text(json.dumps(self.packet), encoding="utf-8")
        manifest = {"stagePacket": {
            "sha256": agent_pipeline.sha256_file(packet_path),
            "contextSha256": agent_pipeline.stage_source_evidence(self.root, paths),
            "editablePaths": self.packet["editablePaths"],
        }}
        agent_pipeline.ensure_stage_contract(self.root, run_dir, manifest)
        (self.root / self.packet["implementationPlanPath"]).write_text("Changed\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Stage context changed"):
            agent_pipeline.ensure_stage_contract(self.root, run_dir, manifest)

    # Source paths must match exactly and must not permit similarly prefixed siblings or symlinks.
    def test_stage_scope_checks_actual_modified_paths(self) -> None:
        manifest = {"stagePacket": {"editablePaths": self.packet["editablePaths"]}}
        agent_pipeline.enforce_stage_scope(self.root, manifest, self.packet["editablePaths"])
        for other in ("tools/agent-pipeline/tests/existing.py.bak", "AGENTS.md", "docs/TESTS.md"):
            with self.subTest(path=other), self.assertRaisesRegex(ValueError, "outside stage editable scope"):
                agent_pipeline.enforce_stage_scope(self.root, manifest, [other])
        outside = self.root / "outside.txt"
        outside.write_text("external\n", encoding="utf-8")
        link = self.root / "tools/agent-pipeline/tests/link.py"
        link.symlink_to(outside)
        with self.assertRaises(ValueError):
            agent_pipeline.safe_stage_path(self.root, "tools/agent-pipeline/tests/link.py")

    # Aider must get the advisory source only as --read and the editable target as an edit argument.
    def test_stage_packet_aider_command_separates_read_and_write(self) -> None:
        _, context = agent_pipeline.validate_stage_packet(self.root, self.packet)
        manifest = {"stagePacket": {"contextPaths": context, "editablePaths": self.packet["editablePaths"]},
                    "specPath": self.packet["specPath"],
                    "worker": {"tool": "aider", "model": "deepseek/deepseek-chat"}}
        policy = {"workerExecution": {"tool": "aider", "executable": "aider",
                    "arguments": ["--model", "{model}", "--message-file", "{task_file}"]}}
        command = agent_pipeline.build_aider_command(policy, manifest, self.root / "evidence")
        self.assertIn(self.packet["editablePaths"][0], command)
        for value in context:
            index = command.index(value)
            self.assertEqual("--read", command[index - 1])
        self.assertNotIn(self.packet["implementationPlanPath"], command[-1:])

    # The public init-stage CLI must persist the same validated packet and source-bound context.
    def test_init_stage_cli_creates_immutable_evidence(self) -> None:
        for relative in ("docs/QUALITY.md", "tools/agent-pipeline/gitleaks.toml"):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# Fixture\n", encoding="utf-8")
        policy = self.root / "policy.json"
        policy.write_text(json.dumps({
            "schemaVersion": 1,
            "requiredRepositoryFiles": ["README.md", "AGENTS.md", "docs/specs/README.md"],
            "requiredChecks": [{"name": "canonical", "command": [sys.executable, "-c", "print('ok')"]}],
        }), encoding="utf-8")
        packet = self.root / "packet.json"
        packet.write_text(json.dumps(self.packet), encoding="utf-8")
        cmd = [sys.executable, str(MODULE_PATH), "--repo-root", str(self.root),
               "--policy", str(policy), "init-stage", "--packet", str(packet), "--run-id", "pilot-a4"]
        result = subprocess.run(cmd, text=True, capture_output=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        evidence = self.root / "build/reports/agent-pipeline/pilot-a4"
        manifest = json.loads((evidence / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(["AP9"], manifest["stagePacket"]["requirementIds"])
        self.assertEqual("LOW", manifest["stagePacket"]["riskLevel"])
        self.assertIn("advisory", (evidence / "task.md").read_text(encoding="utf-8"))
        packet_copy = json.loads((evidence / "stage-packet.json").read_text(encoding="utf-8"))
        self.assertEqual(self.packet, packet_copy)
        cmd[-1] = "pilot-a4"
        repeated = subprocess.run(cmd, text=True, capture_output=True, check=False)
        self.assertNotEqual(0, repeated.returncode)
