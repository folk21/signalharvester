#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_name="$(basename "$repo_dir")"
parent_dir="$(dirname "$repo_dir")"
concat_tool="${SIGNALHARVESTER_CONCAT_TOOL:-$HOME/work/python/concat_files_to_txt.py}"
output_path="${1:-$parent_dir/signalharvester_files.txt}"
python_bin="${PYTHON:-python3}"

if [[ "$repo_name" != "signalharvester" ]]; then
  echo "Expected repository directory name 'signalharvester', got '$repo_name'." >&2
  exit 2
fi

if [[ ! -f "$concat_tool" ]]; then
  echo "Concat tool not found: $concat_tool" >&2
  echo "Set SIGNALHARVESTER_CONCAT_TOOL to the path of concat_files_to_txt.py." >&2
  exit 2
fi

"$python_bin" "$concat_tool" \
  "$repo_dir" \
  "$output_path" \
  -i .git -i '*/.git/*' \
  -i .idea -i '*/.idea/*' \
  -i .vscode -i '*/.vscode/*' \
  -i .gradle -i '*/.gradle/*' \
  -i .kotlin -i '*/.kotlin/*' \
  -i __pycache__ -i '*/__pycache__/*' \
  -i .pytest_cache -i '*/.pytest_cache/*' \
  -i .venv -i '*/.venv/*' \
  -i venv -i '*/venv/*' \
  -i .cache -i '*/.cache/*' \
  -i build -i '*/build/*' \
  -i target -i '*/target/*' \
  -i out -i '*/out/*' \
  -i dist -i '*/dist/*' \
  -i generated -i '*/generated/*' \
  -i configuration-cache -i '*/configuration-cache/*' \
  -i node_modules -i '*/node_modules/*' \
  -i docs/specs/archive -i '*/docs/specs/archive/*' \
  -i .env -i '*/.env' \
  -i signalharvester_files.txt -i '*/signalharvester_files.txt' \
  -i '*.class' -i '*.jar' -i '*.zip' -i '*.log' -i '*.pyc' -i '*.pyo' \
  -e .java -e .kt -e .kts -e .py -e .md -e .txt \
  -e .yaml -e .yml -e .json -e .toml \
  -e .sh -e .bat -e .properties -e .sql -e .xml -e .proto \
  -e .csv -e .conf -e .ini -e .graphql -e .gql -e .http -e .feature \
  -e .gitignore -e .editorconfig -e .env.example \
  -e Dockerfile -e Makefile -e VERSION -e gradlew


echo "Created $output_path"
