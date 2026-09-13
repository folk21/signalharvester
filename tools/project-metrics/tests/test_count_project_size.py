#!/usr/bin/env python3
"""Regression tests for SLOC, specification, byte, and SpecFactor calculations."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "count_project_size.py"
SPEC = importlib.util.spec_from_file_location("count_project_size", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class PhysicalLineCountingTest(unittest.TestCase):
    """Verifies raw physical-line helpers remain deterministic across common line endings."""

    def test_empty_file_has_zero_lines(self) -> None:
        """Empty content must not invent a source line."""
        self.assertEqual(0, MODULE.count_physical_lines(b""))

    def test_final_line_without_terminator_is_counted(self) -> None:
        """A final unterminated line still counts as one physical line."""
        self.assertEqual(2, MODULE.count_physical_lines(b"first\nsecond"))

    def test_trailing_newline_does_not_add_extra_line(self) -> None:
        """A trailing terminator closes a line without creating another one."""
        self.assertEqual(2, MODULE.count_physical_lines(b"first\nsecond\n"))

    def test_crlf_and_cr_line_endings_are_supported(self) -> None:
        """Raw line counting must support Unix, Windows, and CR line endings."""
        self.assertEqual(2, MODULE.count_physical_lines(b"first\r\nsecond\r\n"))
        self.assertEqual(2, MODULE.count_physical_lines(b"first\rsecond\r"))


class JavaSlocTest(unittest.TestCase):
    """Verifies Java physical SLOC excludes comments and blank lines without hiding real code."""

    def test_blank_and_comment_only_lines_are_excluded(self) -> None:
        """Line, block, and Javadoc-only lines must not contribute to Java SLOC."""
        data = b"""
// line comment
/**
 * javadoc
 */
class Example {
    /* block comment */
    int value;
}
"""
        self.assertEqual(3, MODULE.count_java_sloc(data))

    def test_inline_comments_keep_code_line(self) -> None:
        """A line with code before or after a block comment still counts once."""
        data = b"int a = 1; // comment\n/* comment */ int b = 2;\n"
        self.assertEqual(2, MODULE.count_java_sloc(data))

    def test_comment_markers_inside_literals_are_code(self) -> None:
        """Comment-like text inside strings and chars must not start comments."""
        data = b'String url = "https://example.test/a/*b*/";\nchar slash = \'/\';\n'
        self.assertEqual(2, MODULE.count_java_sloc(data))

    def test_multiline_block_comment_does_not_hide_following_code(self) -> None:
        """Code after a closing block comment on the same line must be counted."""
        data = b"/* start\n * middle\n */ int value = 1;\n"
        self.assertEqual(1, MODULE.count_java_sloc(data))

    def test_text_block_content_counts_as_code(self) -> None:
        """Java text blocks are code even when their content contains comment markers."""
        data = b'String text = """\n// data\n/* data */\n""";\n'
        self.assertEqual(4, MODULE.count_java_sloc(data))


class MarkdownContentTest(unittest.TestCase):
    """Verifies Markdown metrics exclude blank and HTML-comment-only content."""

    def test_nonblank_content_is_counted_without_html_comments(self) -> None:
        """Formatting blanks and hidden Markdown comments must not inflate Spec LOC."""
        data = b"# Spec\n\n<!-- hidden\ncontinued -->\nRequirement\n"
        self.assertEqual(2, MODULE.count_markdown_content_lines(data))


class GlobMatchingTest(unittest.TestCase):
    """Verifies globstar patterns also match files with zero intermediate directories."""

    def test_internal_globstar_matches_zero_or_more_directories(self) -> None:
        """A /**/ segment must work directly and through nested subdirectories."""
        pattern = "docs/specs/**/*.md"
        self.assertTrue(MODULE.matches("docs/specs/spec.md", [pattern]))
        self.assertTrue(MODULE.matches("docs/specs/archive/spec.md", [pattern]))


class FileMetricsTest(unittest.TestCase):
    """Verifies byte totals preserve exact on-disk bytes independently from SLOC."""

    def test_read_metrics_preserves_raw_utf8_byte_count(self) -> None:
        """UTF-8 byte totals must equal the bytes stored on disk."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "sample.txt"
            data = "Привет\r\nмир".encode("utf-8")
            path.write_bytes(data)
            metrics = MODULE.read_metrics(path, root, MODULE.count_nonblank_lines)
            self.assertIsNotNone(metrics)
            assert metrics is not None
            self.assertEqual(len(data), metrics.byte_count)
            self.assertEqual(2, metrics.line_count)


class SpecFactorTest(unittest.TestCase):
    """Verifies SpecFactor is defined only as all specification lines over production SLOC."""

    def test_spec_factor_uses_production_code_denominator(self) -> None:
        """Test/support lines must not participate in the SpecFactor denominator."""
        self.assertEqual(1.5, MODULE.spec_factor(spec_lines=150, code_lines=100))

    def test_spec_factor_is_undefined_without_production_code(self) -> None:
        """Repositories with zero production SLOC must report SpecFactor as unavailable."""
        self.assertIsNone(MODULE.spec_factor(spec_lines=50, code_lines=0))


class ClassificationIntegrationTest(unittest.TestCase):
    """Verifies production/test SLOC and all-spec classification through the CLI boundary."""

    def test_cli_counts_all_specs_and_excludes_code_comments(self) -> None:
        """Active and archived specs both contribute while Java comments do not inflate SLOC."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src/main/java/io").mkdir(parents=True)
            (root / "src/test/java/io").mkdir(parents=True)
            (root / "docs/specs/active").mkdir(parents=True)
            (root / "docs/specs/archive").mkdir(parents=True)
            (root / "infra").mkdir()

            (root / "src/main/java/io/App.java").write_text(
                "// header\n\nclass App {\n  int value; /* note */\n}\n", encoding="utf-8")
            (root / "src/test/java/io/AppTest.java").write_text(
                "/** docs */\nclass AppTest {\n  // note\n  void test() {}\n}\n", encoding="utf-8")
            (root / "docs/README.md").write_text("# Docs\n\nText\n", encoding="utf-8")
            (root / "docs/specs/active/spec.md").write_text("# Active\n\nR1\n", encoding="utf-8")
            (root / "docs/specs/archive/old.md").write_text("# Old\n\nR2\nR3\n", encoding="utf-8")
            (root / "infra/app.yaml").write_text("key: value\n", encoding="utf-8")
            report = root / "metrics.json"

            subprocess.run([
                sys.executable, str(MODULE_PATH), "--root", str(root),
                "--code-glob", "**/src/main/java/**/*.java",
                "--project-glob", "**/*.yaml", "--docs-glob", "**/*.md",
                "--spec-glob", "docs/specs/**/*.md", "--test-root", "**/src/test/**",
                "--json-report", str(report),
            ], check=True, stdout=subprocess.DEVNULL)

            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(3, data["production_code"]["lines"])
            self.assertEqual(3, data["tests"]["lines"])
            self.assertEqual(1, data["project_support"]["lines"])
            self.assertEqual(7, data["markdown_docs"]["lines"])
            self.assertEqual(5, data["specification_markdown"]["lines"])
            self.assertEqual(11, data["project_total_excluding_tests"]["lines"])
            self.assertAlmostEqual(5 / 3, data["spec_factor"])


if __name__ == "__main__":
    unittest.main()
