#!/usr/bin/env bash
#   ./run.sh [port] [extra spring args...]
# Raise the thread pool to see the ceiling move: --server.tomcat.threads.max=1000
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-8095}"
shift || true

# Build once; cp.txt caches the resolved classpath for subsequent runs.
[ -f cp.txt ] || mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt

exec java -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
  -cp "target/classes:$(cat cp.txt)" \
  bench.blocking.BlockingApp --server.port="$PORT" "$@"
