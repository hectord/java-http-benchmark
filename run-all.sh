#!/usr/bin/env bash
#
# Harness used to produce the numbers in the READMEs.
#
#   ./run-all.sh build                 # compile all four apps once (needs network)
#   ./run-all.sh throughput            # tiny JSON, 64 keep-alive conns
#   ./run-all.sh reactor               # plain Netty vs Reactor Netty (the Reactor tax)
#   ./run-all.sh concurrency           # 50ms downstream, 1000 conns
#   ./run-all.sh latency               # idle latency, 1 connection
#   ./run-all.sh all                   # everything
#
# Env: DUR (measure seconds, default 15), WARM (warmup seconds, default 15)
#
# Requires: jdk21+, maven, wrk, taskset (util-linux).
#
# --- Measurement design -----------------------------------------------------
# This box has 2 vCPUs. A load generator and a server on the same 2 cores
# measure each other, not the server. So:
#   * the server is pinned with `taskset -c $PIN`, the generator with `-c 1`
#   * "1 vCPU" runs use PIN=0, realistic runs use PIN=0,1
# Absolute numbers are therefore ~20-40% below what a dedicated generator
# would report, but the comparison *between apps* is fair because every app is
# measured the same way.
#
# --- Two bugs this harness exists to avoid --------------------------------
# 1. `setsid java ... &` double-forks: $! is setsid's pid, not the JVM's, so
#    `kill $!` leaves the JVM running and the next benchmark silently competes
#    with it. Every number then drifts. Fix: plain `&`, since `exec java` and
#    `taskset` both preserve the pid, plus a `pgrep -x java` assert + fallback.
# 2. `pkill -f 'bench.reactive'` also matches the shell running a heredoc that
#    contains that string, killing the harness itself (exit 143). Fix: kill by
#    pid, and only fall back to `pkill -x java` (name-exact, cannot self-match).

set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG="$ROOT/results"
DUR="${DUR:-15}"
WARM="${WARM:-15}"
GEN_CORE=1

mkdir -p "$LOG"

APPS=(01-raw-reactor-netty 02-webflux-annotation 03-webflux-functional 04-spring-mvc-tomcat 05-plain-netty)

java_count() { pgrep -x java 2>/dev/null | wc -l; }

assert_clean() {
  if [ "$(java_count)" != "0" ]; then
    echo "  !! stray JVM(s) found, killing to avoid polluting the measurement"
    pkill -9 -x java 2>/dev/null
    sleep 2
  fi
}

build_all() {
  for app in "${APPS[@]}"; do
    printf '  build %-28s ' "$app"
    if ( cd "$ROOT/$app" \
         && mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt ); then
      echo "ok"
    else
      echo "FAILED"; return 1
    fi
  done
}

# run_bench <label> <folder> <port> <path> <pin> <wrkThreads> <conns> [app args...]
run_bench() {
  local label=$1 folder=$2 port=$3 path=$4 pin=$5 t=$6 c=$7
  shift 7

  assert_clean
  ( cd "$ROOT/$folder" && exec taskset -c "$pin" ./run.sh "$port" "$@" ) \
      >"$LOG/srv_${port}.log" 2>&1 &
  local pid=$!

  local ready=0
  for _ in $(seq 240); do
    if curl -sf -o /dev/null "http://127.0.0.1:$port$path"; then ready=1; break; fi
    sleep 0.5
  done
  if [ "$ready" = "0" ]; then
    printf '%-44s SERVER DID NOT START\n' "$label"
    tail -5 "$LOG/srv_${port}.log" | sed 's/^/      /'
    kill -9 $pid 2>/dev/null; wait $pid 2>/dev/null
    pkill -9 -x java 2>/dev/null; sleep 1
    return 1
  fi

  # Confirm we measured what we think we measured.
  local kind
  kind=$(grep -aoE 'Netty started|Tomcat started' "$LOG/srv_${port}.log" | head -1)
  [ -z "$kind" ] && kind="raw-netty"
  sleep 2

  taskset -c $GEN_CORE wrk -t"$t" -c"$c" -d"${WARM}s" "http://127.0.0.1:$port$path" >/dev/null 2>&1
  local out
  out=$(taskset -c $GEN_CORE wrk -t"$t" -c"$c" -d"${DUR}s" --latency \
        "http://127.0.0.1:$port$path" 2>/dev/null)

  local rss threads
  rss=$(ps -o rss= -p $pid 2>/dev/null | tr -d ' ')
  threads=$(ls /proc/$pid/task 2>/dev/null | wc -l)

  printf '%-44s %-15s %8s rps  p50 %-9s p99 %-9s  %4s thr %6s MB\n' \
    "$label" "[$kind]" \
    "$(echo "$out" | awk '/Requests\/sec/{printf "%.0f",$2}')" \
    "$(echo "$out" | awk '/^ *50%/{print $2}')" \
    "$(echo "$out" | awk '/^ *99%/{print $2}')" \
    "${threads:-?}" "$(awk -v k="${rss:-0}" 'BEGIN{printf "%.0f", k/1024}')"

  kill -TERM $pid 2>/dev/null; sleep 2
  kill -9 $pid 2>/dev/null
  wait $pid 2>/dev/null          # reaps the job so bash does not print "Killed"
  pkill -9 -x java 2>/dev/null
  for _ in $(seq 40); do [ "$(java_count)" = "0" ] && break; sleep 0.25; done
  sleep 1
}

scenario_reactor() {
  echo "=== REACTOR TAX: identical endpoint, identical Netty 4.1.135, two rounds ==="
  local round
  for round in 1 2; do
    echo "-- round $round --"
    run_bench "plain Netty, 1 worker / 1 vCPU"        05-plain-netty      8151 /json 0   1 64 1
    run_bench "Reactor Netty Mono, 1 loop / 1 vCPU"   01-raw-reactor-netty 8152 /json 0   1 64 1
    run_bench "plain Netty, 2 workers"                05-plain-netty      8153 /json 0,1 1 64 2
    run_bench "Reactor Netty Mono, 2 loops"           01-raw-reactor-netty 8154 /json 0,1 1 64 2
  done
  echo "=== same, with a 50ms async wait, 1000 concurrent conns ==="
  run_bench "plain Netty /slow, c=1000"               05-plain-netty      8155 /slow 0,1 1 1000 2
  run_bench "Reactor Netty Mono /slow, c=1000"        01-raw-reactor-netty 8156 /slow 0,1 1 1000 2
}

scenario_throughput() {
  echo "=== THROUGHPUT: tiny JSON, 64 keep-alive conns ==="
  run_bench "raw Reactor Netty Mono, 1 loop / 1 vCPU" 01-raw-reactor-netty 8091 /json 0   1 64 1
  run_bench "raw Reactor Netty Mono, 2 loops"        01-raw-reactor-netty 8092 /json 0,1 1 64 2
  run_bench "WebFlux annotation Mono<String>, 1 loop" 02-webflux-annotation 8093 /json 0   1 64 -Dreactor.netty.ioWorkerCount=1
  run_bench "WebFlux annotation Mono<String>, 2 loops" 02-webflux-annotation 8094 /json 0,1 1 64
  run_bench "WebFlux functional Mono<byte[]>"         03-webflux-functional 8095 /json    0,1 1 64
  run_bench "WebFlux functional Mono<String>"         03-webflux-functional 8096 /jsonstr 0,1 1 64
  run_bench "Spring MVC Tomcat (blocking)"            04-spring-mvc-tomcat  8097 /json    0,1 1 64
  echo "=== THROUGHPUT: Jackson POJO ==="
  run_bench "WebFlux annotation Mono<Pojo>"           02-webflux-annotation 8098 /pojo 0,1 1 64
  run_bench "Tomcat Pojo (blocking)"                  04-spring-mvc-tomcat  8099 /pojo 0,1 1 64
}

scenario_concurrency() {
  echo "=== 50ms DOWNSTREAM, 1000 concurrent clients ==="
  run_bench "WebFlux Mono.delay(50ms), c=200"         02-webflux-annotation 8101 /slow 0,1 1 200
  run_bench "WebFlux Mono.delay(50ms), c=1000"        02-webflux-annotation 8102 /slow 0,1 1 1000
  run_bench "Tomcat sleep(50ms), c=1000, 200 threads"  04-spring-mvc-tomcat 8103 /slow 0,1 1 1000
  run_bench "Tomcat sleep(50ms), c=1000, 1000 threads" 04-spring-mvc-tomcat 8104 /slow 0,1 1 1000 \
      --server.tomcat.threads.max=1000 --server.tomcat.threads.min-spare=1000
}

scenario_latency() {
  echo "=== IDLE LATENCY: 1 connection, single client ==="
  run_bench "raw Reactor Netty /json"  01-raw-reactor-netty 8111 /json 0,1 1 1 2
  run_bench "WebFlux annotation /json" 02-webflux-annotation 8112 /json 0,1 1 1
  run_bench "Tomcat /json"             04-spring-mvc-tomcat  8113 /json 0,1 1 1
}

case "${1:-all}" in
  build)       build_all ;;
  reactor)     scenario_reactor ;;
  throughput)  scenario_throughput ;;
  concurrency) scenario_concurrency ;;
  latency)     scenario_latency ;;
  all)         scenario_throughput; echo; scenario_reactor; echo; scenario_concurrency; echo; scenario_latency ;;
  *)           sed -n '3,12p' "$0"; exit 1 ;;
esac

assert_clean
echo
echo "done. server logs in $LOG/"
