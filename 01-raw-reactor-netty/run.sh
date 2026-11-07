#!/usr/bin/env bash
# Build once, then run in a forked JVM (never exec:java - it would benchmark Maven's JVM).
#   ./run.sh [port] [eventLoops]
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-8081}"
LOOPS="${2:-2}"

# Build once; cp.txt caches the resolved classpath for subsequent runs.
[ -f cp.txt ] || mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt

exec java -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
  -cp "target/classes:$(cat cp.txt)" \
  bench.RawNetty "$PORT" "$LOOPS"
