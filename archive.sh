#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_name="$(basename -- "$repo_root")"
parent_dir="$(dirname -- "$repo_root")"
output="${1:-$parent_dir/signalharvester_FULL.zip}"

if [[ "$repo_name" != "signalharvester" ]]; then
    echo "Repository directory must be named 'signalharvester' so the archive keeps the required root." >&2
    exit 1
fi

rm -f -- "$output"

(
    cd -- "$parent_dir"
    zip -r "$output" "$repo_name" \
        -x \
        "$repo_name/.git/*" \
        "$repo_name/.gradle/*" \
        "$repo_name/.idea/*" \
        "$repo_name/.kotlin/*" \
        "$repo_name/.venv/*" \
        "$repo_name/build/*" \
        "$repo_name/*/build/*" \
        "$repo_name/*/*/build/*" \
        "$repo_name/*/target/*" \
        "$repo_name/*/*/target/*" \
        "$repo_name/*/.structurizr/*" \
        "$repo_name/*/*/.structurizr/*" \
        "$repo_name/*/__pycache__/*" \
        "$repo_name/*/*/__pycache__/*" \
        "$repo_name/*/.pytest_cache/*" \
        "$repo_name/*/*/.pytest_cache/*" \
        "$repo_name/*/node_modules/*" \
        "$repo_name/*/*/node_modules/*" \
        "$repo_name/.env" \
        "$repo_name/*/.env" \
        "$repo_name/*/*/.env" \
        "$repo_name/*.class" \
        "$repo_name/*/*.class" \
        "$repo_name/*/*/*.class" \
        "$repo_name/*.zip" \
        "$repo_name/*/*.zip" \
        "$repo_name/*/*/*.zip" \
        "$repo_name/.DS_Store" \
        "$repo_name/*/.DS_Store" \
        "$repo_name/*/*/.DS_Store"
)
