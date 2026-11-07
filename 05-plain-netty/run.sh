#!/usr/bin/env bash
#   ./run.sh [port] [workerThreads]
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-8085}"
LOOPS="${2:-2}"

[ -f cp.txt ] || mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt

exec java -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
  -cp "target/classes:$(cat cp.txt)" \
  bench.plain.PlainNetty "$PORT" "$LOOPS"
