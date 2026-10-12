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
                "branchPrefix": "agent/",
                "arguments": [
                    "--model",
                    "{model}",
                    "--message-file",
                    "{task_file}",
                    "--yes-always",
                    "--no-auto-commits",
                    "--no-suggest-shell-commands",
                ],
            }
            policy.write_text(json.dumps(policy_data), encoding="utf-8")
            base_commit = self.init_git_repository(root)

            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            fake_aider = fake_bin / "aider"
            fake_aider.write_text(
                "#!/usr/bin/env python3\n"
                "from pathlib import Path\n"
                "Path('worker-output.txt').write_text('changed by fake aider\\n', encoding='utf-8')\n"
                "print('fake aider completed')\n",
                encoding="utf-8",
            )
            fake_aider.chmod(0o755)
            env = dict(os.environ)
            env["PATH"] = f"{fake_bin}{os.pathsep}{env.get('PATH', '')}"

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
            self.assertEqual(["worker-output.txt"], worker_result["changedFiles"])
            self.assertFalse(worker_result["unexpectedCommit"])
            self.assertEqual(base_commit, worker_result["headCommit"])

            second_worker = self.run_cli(root, policy, "run-worker", "--run-id", "worker-test", env=env)
            self.assertEqual(2, second_worker.returncode)
            self.assertIn("does not provide repair/retry loops", second_worker.stderr)

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


if __name__ == "__main__":
    unittest.main()
