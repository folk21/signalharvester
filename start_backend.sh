#!/bin/sh

set -o pipefail
./gradlew :app:run 2>&1 | tee logs/backend.log
