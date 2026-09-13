#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CALLER_DIR=$(pwd)
OUT_ARG=${1:-"$ROOT_DIR/../signalharvester-FULL.zip"}
case "$OUT_ARG" in
  /*) OUT=$OUT_ARG ;;
  *) OUT="$CALLER_DIR/$OUT_ARG" ;;
esac
WRAPPER_JAR='signalharvester/gradle/wrapper/gradle-wrapper.jar'

command -v zip >/dev/null 2>&1 || { echo "ERROR: zip is required to create a FULL archive" >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "ERROR: unzip is required to validate a FULL archive" >&2; exit 1; }

if [ ! -f "$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "ERROR: gradle/wrapper/gradle-wrapper.jar is required for a reproducible FULL archive" >&2
  exit 1
fi

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

zip -q "$OUT" "$WRAPPER_JAR"

if ! unzip -Z1 "$OUT" | grep -Fqx "$WRAPPER_JAR"; then
  echo "ERROR: FULL archive validation failed: Gradle Wrapper JAR is missing" >&2
  exit 1
fi

echo "$OUT"
