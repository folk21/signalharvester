"""Pure, bounded preparation of the initial Aider chat/repository-map context.

This is a context-selection adapter, never an authorization or filesystem sandbox.
The source-bound stage packet and post-execution scope checker retain authority.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any


PROFILE_KEYS = frozenset({"mode", "mapTokens", "maxInitialEditableBytes", "ignorePatterns"})


def validate_context_profile(profile: Any) -> None:
    """Fail closed on unsupported controls and ambiguous ignore rules."""
    if not isinstance(profile, dict) or set(profile) != PROFILE_KEYS:
        raise ValueError("Aider contextProfile must define exactly the supported fields")
    if profile["mode"] != "SELECTIVE_REPO_MAP":
        raise ValueError("Unsupported Aider contextProfile mode")
    tokens = profile["mapTokens"]
    if type(tokens) is not int or not 256 <= tokens <= 8192:
        raise ValueError("Aider mapTokens must be an integer from 256 to 8192")
    size = profile["maxInitialEditableBytes"]
    if type(size) is not int or not 0 <= size <= 262144:
        raise ValueError("Aider maxInitialEditableBytes must be an integer from 0 to 262144")
    patterns = profile["ignorePatterns"]
    if (not isinstance(patterns, list) or len(patterns) > 64
            or len(set(map(str, patterns))) != len(patterns)):
        raise ValueError("Aider ignorePatterns must be a bounded unique list")
    for pattern in patterns:
        if (not isinstance(pattern, str) or len(pattern) > 256 or not pattern.endswith("/")
                or pattern.startswith(("/", "!", "#")) or "\\" in pattern
                or any(part in ("", ".", "..") for part in pattern[:-1].split("/"))
                or any(character in pattern for character in ("*", "?", "[", "]", "\n", "\r"))):
            raise ValueError("Aider ignorePatterns must be safe repository-relative directory prefixes")


def select_initial_editable_files(
    worktree: Path, editable_paths: list[str], max_bytes: int
) -> tuple[list[str], list[str]]:
    """Do not add oversized editable files to chat; Aider can find them in its repo map.

    Aider may later request the complete file. This is an initial-context optimization,
    not a hard limit on API request tokens or on later file additions.
    """
    selected: list[str] = []
    omitted: list[str] = []
    for relative in editable_paths:
        if relative.endswith("/"):
            continue
        path = worktree / relative
        # Packet paths are validated separately. Recheck here to prevent a symlink
        # from leaking a target outside the worktree during size inspection.
        if path.is_symlink():
            raise ValueError(f"Aider initial edit path is a symlink: {relative}")
        if not path.is_file():
            # New files can be created from the task; they do not enter chat yet.
            continue
        if path.stat().st_size <= max_bytes:
            selected.append(relative)
        else:
            omitted.append(relative)
    return selected, omitted


def prepare_context_flags(
    profile: dict[str, Any], worktree: Path, run_dir: Path, editable_paths: list[str]
) -> tuple[list[str], list[str], list[str]]:
    """Write a private run-local Aider ignore file and build explicit context flags."""
    validate_context_profile(profile)
    selected, omitted = select_initial_editable_files(
        worktree, editable_paths, profile["maxInitialEditableBytes"]
    )
    ignore_path = run_dir / "aider.context.ignore"
    if ignore_path.is_symlink():
        raise ValueError("Aider context ignore artifact must not be a symlink")
    ignore_path.write_text(
        "# Run-local repository-map exclusions; not a security boundary.\n"
        + "".join(pattern + "\n" for pattern in profile["ignorePatterns"]),
        encoding="utf-8",
    )
    return ["--map-tokens", str(profile["mapTokens"]),
            "--aiderignore", str(ignore_path.resolve())], selected, omitted
