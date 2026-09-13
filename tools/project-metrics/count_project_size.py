#!/usr/bin/env python3
"""Count production SLOC, test SLOC, specifications, and project text size."""

from __future__ import annotations

import argparse
import fnmatch
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

DEFAULT_EXCLUDED_DIRS = {
    ".git", ".gradle", ".idea", ".kotlin", ".vscode", ".venv", "venv",
    "build", "target", "out", "dist", "node_modules", "__pycache__", "generated",
}

DEFAULT_TEXT_SUFFIXES = {
    ".java", ".kt", ".kts", ".py", ".sh", ".bat", ".proto", ".yaml", ".yml",
    ".json", ".toml", ".properties", ".sql", ".xml", ".txt", ".csv", ".conf",
    ".ini", ".graphql", ".gql", ".http", ".feature", ".md",
}

DEFAULT_TEXT_NAMES = {"Dockerfile", "gradlew"}
TEST_CODE_SUFFIXES = {".java", ".kt", ".kts", ".py", ".sh"}


@dataclass(frozen=True)
class FileMetrics:
    path: str
    byte_count: int
    line_count: int


@dataclass(frozen=True)
class Metrics:
    files: int
    bytes: int
    lines: int

    def __add__(self, other: "Metrics") -> "Metrics":
        return Metrics(self.files + other.files, self.bytes + other.bytes, self.lines + other.lines)


ZERO = Metrics(0, 0, 0)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Count production/test SLOC and project text size.")
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--code-glob", action="append", default=[])
    parser.add_argument("--project-glob", action="append", default=[])
    parser.add_argument("--docs-glob", action="append", default=[])
    parser.add_argument("--spec-glob", action="append", default=[])
    parser.add_argument("--test-root", action="append", default=[])
    parser.add_argument("--exclude-dir", action="append", default=[])
    parser.add_argument("--report", type=Path)
    parser.add_argument("--json-report", type=Path)
    parser.add_argument("--list-files", action="store_true")
    parser.add_argument("--console-summary", action="store_true")
    return parser.parse_args()


def globstar_variants(pattern: str) -> set[str]:
    """Return fnmatch-compatible variants where every **/ may match zero directories."""
    variants = {pattern}
    pending = [pattern]
    while pending:
        current = pending.pop()
        candidates: list[str] = []
        if current.startswith("**/"):
            candidates.append(current[3:])
        marker = "/**/"
        index = current.find(marker)
        if index >= 0:
            candidates.append(current[:index] + "/" + current[index + len(marker):])
        for candidate in candidates:
            if candidate not in variants:
                variants.add(candidate)
                pending.append(candidate)
    return variants


def matches(path: str, patterns: Iterable[str]) -> bool:
    return any(fnmatch.fnmatchcase(path, variant)
               for pattern in patterns for variant in globstar_variants(pattern))


def excluded(path: Path, root: Path, excluded_dirs: set[str]) -> bool:
    return any(part in excluded_dirs for part in path.relative_to(root).parts[:-1])


def is_text_candidate(path: Path) -> bool:
    return path.name in DEFAULT_TEXT_NAMES or path.suffix.lower() in DEFAULT_TEXT_SUFFIXES


def count_physical_lines(data: bytes) -> int:
    """Count physical lines, including blank/comment lines; retained for raw diagnostics/tests."""
    return len(data.splitlines())


def count_nonblank_lines(data: bytes) -> int:
    """Count non-empty physical text lines after UTF-8 decoding."""
    text = data.decode("utf-8", errors="replace")
    return sum(1 for line in text.splitlines() if line.strip())


def count_markdown_content_lines(data: bytes) -> int:
    """Count nonblank Markdown lines excluding HTML-comment-only content."""
    text = data.decode("utf-8", errors="replace")
    in_comment = False
    count = 0
    for line in text.splitlines():
        i = 0
        visible: list[str] = []
        while i < len(line):
            if in_comment:
                end = line.find("-->", i)
                if end < 0:
                    i = len(line)
                    continue
                in_comment = False
                i = end + 3
                continue
            start = line.find("<!--", i)
            if start < 0:
                visible.append(line[i:])
                break
            visible.append(line[i:start])
            in_comment = True
            i = start + 4
        if "".join(visible).strip():
            count += 1
    return count


def count_java_sloc(data: bytes) -> int:
    """Count physical Java SLOC, excluding blank and comment-only lines.

    Inline comments do not remove a line that also contains code. Block comments and Javadocs may span
    lines. Java strings/chars and text blocks are recognized so comment markers inside literals are code.
    """
    text = data.decode("utf-8", errors="replace")
    in_block_comment = False
    in_text_block = False
    sloc = 0

    for line in text.splitlines():
        i = 0
        has_code = False
        in_string = False
        in_char = False
        escaped = False

        while i < len(line):
            if in_block_comment:
                end = line.find("*/", i)
                if end < 0:
                    i = len(line)
                    continue
                in_block_comment = False
                i = end + 2
                continue

            if in_text_block:
                has_code = True
                end = line.find('"""', i)
                if end < 0:
                    i = len(line)
                    continue
                in_text_block = False
                i = end + 3
                continue

            ch = line[i]
            nxt = line[i + 1] if i + 1 < len(line) else ""

            if in_string:
                has_code = True
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
                i += 1
                continue

            if in_char:
                has_code = True
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == "'":
                    in_char = False
                i += 1
                continue

            if ch.isspace():
                i += 1
                continue
            if ch == "/" and nxt == "/":
                break
            if ch == "/" and nxt == "*":
                in_block_comment = True
                i += 2
                continue
            if line.startswith('"""', i):
                has_code = True
                in_text_block = True
                i += 3
                continue
            if ch == '"':
                has_code = True
                in_string = True
                i += 1
                continue
            if ch == "'":
                has_code = True
                in_char = True
                i += 1
                continue

            has_code = True
            i += 1

        if has_code:
            sloc += 1

    return sloc


def count_test_sloc(path: Path, data: bytes) -> int:
    suffix = path.suffix.lower()
    if suffix == ".java":
        return count_java_sloc(data)
    # Test helper languages are uncommon in this Java repository. Count nonblank source lines and keep
    # their bytes separate; Java, which dominates test SLOC, receives full comment-aware lexing.
    return count_nonblank_lines(data)


def read_metrics(path: Path, root: Path, line_counter: Callable[[bytes], int] = count_physical_lines) -> FileMetrics | None:
    data = path.read_bytes()
    if b"\x00" in data[:8192]:
        return None
    return FileMetrics(path.relative_to(root).as_posix(), len(data), line_counter(data))


def summarize(files: Iterable[FileMetrics]) -> Metrics:
    result = ZERO
    for item in files:
        result += Metrics(1, item.byte_count, item.line_count)
    return result


def format_int(value: int) -> str:
    return f"{value:,}"


def metrics_dict(metrics: Metrics) -> dict[str, int]:
    return {"files": metrics.files, "bytes": metrics.bytes, "lines": metrics.lines}


def concise_metrics(metrics: Metrics) -> str:
    return f"{format_int(metrics.lines)} lines / {format_int(metrics.bytes)} bytes"


def spec_factor(spec_lines: int, code_lines: int) -> float | None:
    return None if code_lines == 0 else spec_lines / code_lines


def format_spec_factor(spec_lines: int, code_lines: int) -> str:
    value = spec_factor(spec_lines, code_lines)
    return "n/a" if value is None else f"{value:.2f}"


def render_console_summary(code: Metrics, tests: Metrics, support: Metrics, docs: Metrics, specs: Metrics) -> str:
    return "\n".join([
        "Code: "
        f"production SLOC {concise_metrics(code)}; test SLOC {concise_metrics(tests)}; "
        f"code+tests {concise_metrics(code + tests)}",
        "Project: "
        f"config/contracts/scripts {concise_metrics(support)}; markdown {concise_metrics(docs)}; "
        f"total excluding tests {concise_metrics(code + support + docs)}",
        "Spec: "
        f"all specs {concise_metrics(specs)}; SpecFactor {format_spec_factor(specs.lines, code.lines)} "
        "(all spec lines / production SLOC)",
    ]) + "\n"


def render_report(root: Path, code_files: list[FileMetrics], test_files: list[FileMetrics],
                  support_files: list[FileMetrics], docs_files: list[FileMetrics], spec_files: list[FileMetrics],
                  skipped_binary_files: int, list_files: bool) -> str:
    code, tests = summarize(code_files), summarize(test_files)
    support, docs, specs = summarize(support_files), summarize(docs_files), summarize(spec_files)
    rows = [
        ("Production SLOC", code), ("Test SLOC", tests), ("Code + tests", code + tests),
        ("Config / contracts / scripts", support), ("Markdown docs", docs), ("All specifications", specs),
        ("Project (code + config)", code + support),
        ("Project total excl. tests", code + support + docs),
        ("Project + tests excl. docs", code + support + tests),
    ]
    lines = [
        "Project size metrics", f"Root: {root}",
        "Production/test line definition: physical source lines containing code; blank and comment-only lines excluded.",
        "Markdown/spec line definition: nonblank content lines; HTML-comment-only content excluded.",
        "Support/config line definition: physical lines (format-specific comments are not normalized).",
        "Byte definition: raw file bytes; comments/blank lines still contribute to byte size.", "",
        f"{'Category':31} {'Files':>9} {'Bytes':>15} {'Lines':>15}",
        f"{'-' * 31} {'-' * 9} {'-' * 15} {'-' * 15}",
    ]
    for label, metrics in rows:
        lines.append(f"{label:31} {format_int(metrics.files):>9} {format_int(metrics.bytes):>15} {format_int(metrics.lines):>15}")
    lines.extend(["",
        f"SpecFactor (all spec lines / production SLOC): {format_spec_factor(specs.lines, code.lines)}",
        "SpecFactor is descriptive only and is not a quality gate.",
        "All Markdown below docs/specs/** is included, including active, completed, and archived specifications.",
        f"Skipped binary candidate files: {skipped_binary_files}",
    ])
    if list_files:
        for title, files in [
            ("Production code files", code_files), ("Test code files", test_files),
            ("Config / contracts / scripts files", support_files), ("Markdown documentation files", docs_files),
            ("Specification files", spec_files),
        ]:
            lines.extend(["", title + ":"])
            lines.extend(f"  {item.path}" for item in sorted(files, key=lambda item: item.path))
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f"Project root does not exist or is not a directory: {root}")
    for name, value in [("--code-glob", args.code_glob), ("--project-glob", args.project_glob),
                        ("--docs-glob", args.docs_glob), ("--spec-glob", args.spec_glob),
                        ("--test-root", args.test_root)]:
        if not value:
            raise SystemExit(f"At least one {name} is required.")

    excluded_dirs = DEFAULT_EXCLUDED_DIRS | set(args.exclude_dir)
    code_files: list[FileMetrics] = []
    test_files: list[FileMetrics] = []
    support_files: list[FileMetrics] = []
    docs_files: list[FileMetrics] = []
    spec_files: list[FileMetrics] = []
    skipped_binary_files = 0

    for path in root.rglob("*"):
        if path.is_symlink() or not path.is_file() or excluded(path, root, excluded_dirs):
            continue
        relative = path.relative_to(root).as_posix()
        in_test_tree = matches(relative, args.test_root)
        is_code = matches(relative, args.code_glob)
        is_project_file = matches(relative, args.project_glob)
        is_doc = matches(relative, args.docs_glob)
        is_spec = matches(relative, args.spec_glob)

        if in_test_tree:
            if path.suffix.lower() not in TEST_CODE_SUFFIXES:
                continue
            item = read_metrics(path, root, lambda data, p=path: count_test_sloc(p, data))
            if item is None:
                skipped_binary_files += 1
            else:
                test_files.append(item)
            continue

        if not is_code and not is_project_file and not is_doc and not is_spec:
            continue
        if not is_text_candidate(path):
            continue

        if is_code:
            counter = count_java_sloc if path.suffix.lower() == ".java" else count_nonblank_lines
        elif is_doc or is_spec:
            counter = count_markdown_content_lines
        else:
            counter = count_physical_lines
        item = read_metrics(path, root, counter)
        if item is None:
            skipped_binary_files += 1
            continue

        if is_code:
            code_files.append(item)
        elif is_doc or is_spec:
            docs_files.append(item)
            if is_spec:
                spec_files.append(item)
        else:
            support_files.append(item)

    code, tests = summarize(code_files), summarize(test_files)
    support, docs, specs = summarize(support_files), summarize(docs_files), summarize(spec_files)
    payload = {
        "root": str(root),
        "production_code": metrics_dict(code), "tests": metrics_dict(tests),
        "code_plus_tests": metrics_dict(code + tests), "project_support": metrics_dict(support),
        "specification_markdown": metrics_dict(specs), "markdown_docs": metrics_dict(docs),
        "project_non_test": metrics_dict(code + support),
        "project_with_docs": metrics_dict(code + support + docs),
        "project_with_tests": metrics_dict(code + support + tests),
        "project_total_excluding_tests": metrics_dict(code + support + docs),
        "spec_factor": spec_factor(specs.lines, code.lines),
        "definitions": {
            "production_line_metric": "physical SLOC excluding blank and comment-only Java lines",
            "test_line_metric": "source SLOC excluding blank/comment-only Java lines; non-Java test helpers use nonblank lines",
            "markdown_line_metric": "nonblank content lines excluding HTML-comment-only content",
            "support_line_metric": "physical lines",
            "specification_scope": "all Markdown files matched below docs/specs/**",
            "spec_factor": "all specification content lines / production SLOC",
        },
        "skipped_binary_candidate_files": skipped_binary_files,
    }

    report = render_report(root, code_files, test_files, support_files, docs_files, spec_files,
                           skipped_binary_files, args.list_files)
    print(render_console_summary(code, tests, support, docs, specs) if args.console_summary else report, end="")
    if args.report:
        target = args.report if args.report.is_absolute() else root / args.report
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(report, encoding="utf-8")
    if args.json_report:
        target = args.json_report if args.json_report.is_absolute() else root / args.json_report
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
