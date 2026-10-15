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
