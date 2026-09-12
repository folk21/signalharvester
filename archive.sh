#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT=${1:-"$ROOT_DIR/../signalharvester-FULL.zip"}

cd "$ROOT_DIR/.."
rm -f "$OUT"

zip -qr "$OUT" signalharvester \
  -x 'signalharvester/.git/*' \
  -x 'signalharvester/.idea/*' \
  -x 'signalharvester/.gradle/*' \
  -x 'signalharvester/.kotlin/*' \
  -x 'signalharvester/.vscode/*' \
  -x 'signalharvester/.venv/*' \
  -x 'signalharvester/**/__pycache__/*' \
  -x 'signalharvester/**/configuration-cache/*' \
  -x 'signalharvester/**/.pytest_cache/*' \
  -x 'signalharvester/**/build/*' \
  -x 'signalharvester/**/target/*' \
  -x 'signalharvester/**/out/*' \
  -x 'signalharvester/**/dist/*' \
  -x 'signalharvester/**/.structurizr/*' \
  -x 'signalharvester/**/node_modules/*' \
  -x 'signalharvester/**/*.jar' \
  -x 'signalharvester/.env' \
  -x 'signalharvester/**/.env' \
  -x 'signalharvester/**/*.class' \
  -x 'signalharvester/**/*.zip' \
  -x 'signalharvester/**/*.log' \
  -x 'signalharvester/*.log' \
  -x 'signalharvester/signalharvester_files.txt' \
  -x 'signalharvester/**/.DS_Store'

WRAPPER_JAR='signalharvester/gradle/wrapper/gradle-wrapper.jar'
if [ -f "$WRAPPER_JAR" ]; then
  zip -q "$OUT" "$WRAPPER_JAR"
fi

echo "$OUT"

