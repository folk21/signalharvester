"""Regression checks for the modular controller import and read-only CLI boundaries."""

from __future__ import annotations

import ast
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "agent_pipeline_lib"


class PipelineLayoutTest(unittest.TestCase):
    """Preserve an acyclic controller and side-effect-free CLI startup."""

    def test_subsystem_dependencies_are_acyclic(self) -> None:
        graph = {}
        for source in PACKAGE.glob("*.py"):
            parsed = ast.parse(source.read_text(encoding="utf-8"))
            graph[source.stem] = {
                item.name for node in parsed.body
                if isinstance(node, ast.ImportFrom) and node.level == 1
                for item in node.names
            }
        self.assertIn("cli_main", graph)
        visiting: set[str] = set()
        visited: set[str] = set()

        def visit(name: str) -> None:
            self.assertNotIn(name, visiting, f"Cyclic subsystem import: {name}")
            if name in visited:
                return
            visiting.add(name)
            for dependency in graph[name]:
                self.assertIn(dependency, graph)
                visit(dependency)
            visiting.remove(name)
            visited.add(name)

        for module in graph:
            visit(module)

    def test_cli_help_does_not_write_python_bytecode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            isolated = Path(temporary)
            shutil.copy2(ROOT / "agent_pipeline.py", isolated / "agent_pipeline.py")
            shutil.copytree(PACKAGE, isolated / "agent_pipeline_lib",
                            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            env = {key: value for key, value in os.environ.items()
                   if key != "PYTHONDONTWRITEBYTECODE"}
            completed = subprocess.run(
                [sys.executable, str(isolated / "agent_pipeline.py"), "--help"],
                cwd=isolated,
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn("plan-batch", completed.stdout)
            self.assertIn("Create and verify bounded development-agent runs", completed.stdout)
            self.assertEqual([], list(isolated.rglob("__pycache__")))
            self.assertEqual([], list(isolated.rglob("*.pyc")))


if __name__ == "__main__":
    unittest.main()
