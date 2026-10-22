"""Independent regression cases for selective Aider initial context configuration."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(name: str, source: Path):
    """Load real source without depending on the ambient Python import path."""
    spec = importlib.util.spec_from_file_location(name, source)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


context = load("aider_context_test", ROOT / "aider_context.py")
controller = load("agent_pipeline_context_test", ROOT / "agent_pipeline.py")


def profile(**overrides):
    result = {
        "mode": "SELECTIVE_REPO_MAP",
        "mapTokens": 2048,
        "maxInitialEditableBytes": 20480,
        "ignorePatterns": ["docs/specs/archive/", "build/"],
    }
    result.update(overrides)
    return result


class AiderContextTest(unittest.TestCase):
    """Guard explicit Aider input selection without weakening source/scope constraints."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.worktree = root / "repo"
        self.worktree.mkdir()
        self.run = root / "private-run"
        self.run.mkdir()
        (self.worktree / "src").mkdir()
        (self.worktree / "src" / "small.py").write_text("ok\n", encoding="utf-8")
        (self.worktree / "src" / "huge.py").write_text("a" * 40000, encoding="utf-8")
        self.editable = ["src/small.py", "src/huge.py", "src/new.py", "src/"]
        self.manifest = {
            "worker": {"tool": "aider", "model": "deepseek/deepseek-chat"},
            "specPath": "docs/specs/active/example.md",
            "stagePacket": {
                "editablePaths": self.editable,
                "contextPaths": ["AGENTS.md", "docs/specs/active/example.md"],
            },
        }
        self.policy = {
            "workerExecution": {
                "tool": "aider", "executable": "aider",
                "arguments": ["--model", "{model}", "--message-file", "{task_file}"],
                "contextProfile": profile(),
            }
        }

    def test_selective_mode_omits_large_initial_edit_file_but_keeps_permission(self):
        command = controller.build_aider_command(
            self.policy, self.manifest, self.run, worktree=self.worktree
        )
        self.assertIn("src/small.py", command)
        self.assertNotIn("src/huge.py", command)
        self.assertNotIn("src/new.py", command)
        self.assertIn("--map-tokens", command)
        self.assertEqual("2048", command[command.index("--map-tokens") + 1])
        self.assertIn("--read", command)
        self.assertIn("docs/specs/active/example.md", command)
        self.assertEqual(self.editable, self.manifest["stagePacket"]["editablePaths"])

    def test_edit_authorization_is_independent_of_initial_chat_selection(self):
        command = controller.build_aider_command(
            self.policy, self.manifest, self.run, worktree=self.worktree
        )
        self.assertNotIn("src/huge.py", command)
        controller.enforce_stage_scope(
            self.worktree, self.manifest, ["src/huge.py"]
        )
        with self.assertRaisesRegex(ValueError, "outside stage editable scope"):
            controller.enforce_stage_scope(
                self.worktree, self.manifest, ["unrelated.py"]
            )

    def test_run_local_ignore_file_contains_exact_reviewed_patterns(self):
        command = controller.build_aider_command(
            self.policy, self.manifest, self.run, worktree=self.worktree
        )
        path = Path(command[command.index("--aiderignore") + 1])
        self.assertEqual((self.run / "aider.context.ignore").resolve(), path)
        self.assertIn("build/\n", path.read_text())
        self.assertIn("docs/specs/archive/\n", path.read_text())
        self.assertFalse((self.worktree / ".aiderignore").exists())

    def test_run_local_ignore_path_uses_canonical_directory_alias(self):
        # macOS /var and /private/var can refer to the same directory.
        alias = self.run.parent / "run-alias"
        alias.symlink_to(self.run, target_is_directory=True)
        command = controller.build_aider_command(
            self.policy, self.manifest, alias, worktree=self.worktree
        )
        path = Path(command[command.index("--aiderignore") + 1])
        self.assertEqual((self.run / "aider.context.ignore").resolve(), path)
        self.assertEqual((alias / "aider.context.ignore").resolve(), path)
        self.assertTrue(path.is_file())
        self.assertFalse((self.worktree / ".aiderignore").exists())

    def test_legacy_packet_invocation_is_unchanged(self):
        del self.policy["workerExecution"]["contextProfile"]
        command = controller.build_aider_command(self.policy, self.manifest, self.run)
        self.assertIn("src/huge.py", command)
        self.assertNotIn("--map-tokens", command)
        self.assertNotIn("--aiderignore", command)
        self.assertFalse((self.run / "aider.context.ignore").exists())

    def test_invalid_configuration_is_fail_closed(self):
        invalid = [
            profile(mode="UNSAFE"),
            profile(mapTokens=True),
            profile(mapTokens=0),
            profile(mapTokens=1000000),
            profile(maxInitialEditableBytes=-1),
            profile(maxInitialEditableBytes=1.5),
            profile(ignorePatterns=["../secrets/"]),
            profile(ignorePatterns=["/etc/"]),
            profile(ignorePatterns=["!src/"]),
            profile(ignorePatterns=["src/**"]),
            profile(ignorePatterns=["build/", "build/"]),
            {**profile(), "unknownField": True},
        ]
        for candidate in invalid:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                context.validate_context_profile(candidate)

    def test_selective_mode_requires_a_worktree(self):
        with self.assertRaisesRegex(ValueError, "requires the exact worker worktree"):
            controller.build_aider_command(self.policy, self.manifest, self.run)

    def test_context_selection_rejects_symlink_edit_path(self):
        (self.worktree / "src" / "link.py").symlink_to(self.worktree / "src" / "small.py")
        with self.assertRaisesRegex(ValueError, "symlink"):
            context.select_initial_editable_files(self.worktree, ["src/link.py"], 2000)

    def test_private_ignore_artifact_rejects_symlink(self):
        (self.run / "aider.context.ignore").symlink_to(self.worktree / "src" / "small.py")
        with self.assertRaisesRegex(ValueError, "symlink"):
            context.prepare_context_flags(profile(), self.worktree, self.run, self.editable)
        self.assertEqual("ok\n", (self.worktree / "src" / "small.py").read_text())

    def test_threshold_boundary_and_no_file_changes(self):
        before = (self.worktree / "src" / "huge.py").read_bytes()
        chosen, omitted = context.select_initial_editable_files(
            self.worktree, ["src/huge.py", "src/small.py"], len(before)
        )
        self.assertEqual(["src/huge.py", "src/small.py"], chosen)
        self.assertEqual([], omitted)
        self.assertEqual(before, (self.worktree / "src" / "huge.py").read_bytes())

    def test_installed_aider_flags_checked_for_selective_mode(self):
        flags = controller.configured_aider_flags(self.policy["workerExecution"])
        self.assertIn("--map-tokens", flags)
        self.assertIn("--aiderignore", flags)
        del self.policy["workerExecution"]["contextProfile"]
        legacy = controller.configured_aider_flags(self.policy["workerExecution"])
        self.assertNotIn("--map-tokens", legacy)

    def test_control_module_remains_protected_from_worker_scope(self):
        self.assertIn("tools/agent-pipeline/aider_context.py",
                      controller.PROTECTED_WORKER_CONTROL_PATHS)

    def test_policy_context_profile_rejects_invalid_values(self):
        # Keep policy validation separate from stage context selection.
        data = json.loads((ROOT / "pipeline-policy.json").read_text(encoding="utf-8"))
        controller.validate_policy(data)
        data["workerExecution"]["contextProfile"]["mapTokens"] = "2048"
        with self.assertRaises(ValueError):
            controller.validate_policy(data)

    def test_canonical_runner_has_kernel_recognizable_shebang(self):
        runner = ROOT.parents[1] / "run_checks.sh"
        self.assertTrue(runner.read_bytes().startswith(b"#!/"))

    def test_adapter_load_does_not_generate_repository_bytecode(self):
        parent = ROOT / "__pycache__"
        before = sorted(str(p) for p in parent.glob("aider_context*.pyc")) if parent.exists() else []
        controller._aider_context_functions()["validate_context_profile"](profile())
        after = sorted(str(p) for p in parent.glob("aider_context*.pyc")) if parent.exists() else []
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
