# Java + Project Reactor `Mono` — measured throughput on this machine

Five standalone apps, one variable apart each, plus the harness that produced the
numbers. The question being answered: *how many QPS can a Java service built on
Reactor `Mono` do here, and what actually limits it?*

Machine under test: **2 vCPU Intel Haswell @ 2.6 GHz (KVM), 3.7 GB RAM, no GPU,
Debian 13, JDK 21.** Wake-up call: [`../README` note on the box](../) — it is a
small cloud VM, so treat all numbers as *per-2-vCPUs*, not as a framework verdict.

## The five apps

| Folder | Stack | What it isolates |
|---|---|---|
| `05-plain-netty` | plain Netty, **no Reactor at all** | the true hardware ceiling |
| `01-raw-reactor-netty` | Reactor Netty, **no Spring** | **the Reactor tax** (01 vs 05) |
| `02-webflux-annotation` | Spring WebFlux, `@RestController` → `Mono<T>` | the mainstream "Java + Mono" setup |
| `03-webflux-functional` | Spring WebFlux, `RouterFunction` → `Mono<T>` | cost of annotation-based handler mapping |
| `04-spring-mvc-tomcat` | Spring MVC on Tomcat, blocking | control group: is `Mono` actually faster? |

Each folder has its own `pom.xml`, `run.sh`, `README.md` and can be built and run
in isolation. They use different Java packages on purpose — see "pitfalls".

## Results (2 vCPU, 64 keep-alive conns, server & generator on separate cores)

| App / config | rps | p50 | p99 |
|---|---:|---:|---:|
| 05 plain Netty, 1 worker, 1 core | **43,000** | ~1.1 ms | 6.8 ms |
| 01 Reactor Netty, 1 loop, 1 core | **32,200** | ~1.4 ms | 7.9 ms |
| 01 raw Reactor Netty, 2 loops | 42,600 | 1.0 ms | 5.9 ms |
| 01 raw Reactor Netty, 1 loop, 1 core | 33,000 | 1.6 ms | 11.4 ms |
| 03 WebFlux functional → `Mono<String>` | 9,700 | 5.7 ms | 20 ms |
| 03 WebFlux functional → `Mono<byte[]>` | 7,900 | 8.0 ms | 20 ms |
| 04 Spring MVC Tomcat | 7,700 | 7.7 ms | 37 ms |
| 02 WebFlux annotation → `Mono<String>` | 6,600 | 9.8 ms | 23 ms |
| 02 WebFlux annotation → `Mono<String>`, 1 core | 2,800 | 20 ms | 43 ms |
| 02 WebFlux annotation → `Mono<Pojo>` (Jackson) | 4,600 | 13 ms | 29 ms |
| 04 Tomcat `Pojo` (Jackson) | 5,800 | 9.8 ms | 48 ms |

Idle single-request latency: **106 µs p50 (WebFlux)** vs 121 µs (Tomcat).

### The case that justifies `Mono` (50 ms downstream, 1000 clients)

| App | rps | p50 | threads | RSS |
|---|---:|---:|---:|---:|
| 05 plain Netty `schedule(50ms)` | 18,764 | 51.7 ms | 22 | 613 MB |
| 01 Reactor Netty `Mono.delay(50ms)` | 18,565 | 51.7 ms | 24 | 634 MB |
| 02 WebFlux `Mono.delay(50ms)` | 4,530 | 206 ms | **25** | 715 MB |
| 04 Tomcat, 200 threads (default) | 3,835 | 309 ms | 200 | — |
| 04 Tomcat, 1000 threads | 5,370 | 165 ms | 1000 | — |

## The Reactor tax (app 01 vs app 05)

Same Netty 4.1.135, same JDK, same heap, same endpoint — only the Reactor layer
differs. Five alternating rounds, server and generator on separate cores:

| Workload | plain Netty | Reactor Netty | Reactor cost |
|---|---:|---:|---:|
| tiny JSON, CPU-bound | 42,998 | 32,230 | **+25% CPU per request** |
| 50 ms async wait, 1000 conns | 18,764 | 18,565 | **~1%** |

So: **Reactor is not free for CPU-bound handlers (~25%), and essentially free
when requests wait.** And the layer above Reactor dwarfs it —

| `/slow`, 50 ms, 1000 conns | rps | vs plain Netty |
|---|---:|---:|
| plain Netty | 18,764 | 100% |
| Reactor Netty | 18,565 | 99% |
| Spring WebFlux | 4,530 | **24%** |

If you are choosing a reactive stack, "Reactor overhead" is the wrong thing to
worry about: Spring's controller pipeline costs ~4× while Reactor costs ~1% under
real I/O. Use `Mono` for concurrency, and use plain Netty only if you are writing
infrastructure (gateways, proxies) where 25% of a CPU-bound ceiling matters.

## What the numbers mean

1. **`Mono` is not a QPS multiplier, and Reactor is not the villain.** Plain
   Netty 43.0k → Reactor Netty 32.2k (Reactor costs 25% on trivial handlers) →
   Spring WebFlux 6.6k (Spring costs another 5×). The framework above Reactor is
   the dominant cost, not the reactive model.
2. **The box is CPU-bound, not thread-bound.** At 2 vCPUs every Spring app
   saturates around 5–10k rps. Budget **3–5k rps per vCPU** for WebFlux with real
   JSON, and run at ~60–70% of measured max in production.
3. **Cheapest wins, in order:** functional routes instead of annotations (+47%),
   pre-serialized `byte[]`/`String` instead of Jackson, and
   `-Dreactor.netty.ioWorkerCount=<cores>`.
4. **`Mono` earns its keep on thread cost, not throughput.** 4.5k rps on 25
   threads vs needing 1000 threads for 5.4k. And under load the Reactor layer is
   ~1% of a plain-Netty stack, so the concurrency is nearly free. On a 3.7 GB machine that is the
   difference between fitting and swapping.
5. **Tomcat beats WebFlux for trivial CPU-bound handlers here.** That is expected
   and worth internalising: for a warm thread pool, "parse, copy 45 bytes, write"
   is very fast. Choose reactive for I/O concurrency, not for hello-world bragging.

## Usage

```bash
./run-all.sh build          # first time: compile all four (needs network for maven)
./run-all.sh throughput
./run-all.sh concurrency
./run-all.sh latency
./run-all.sh reactor          # plain Netty vs Reactor Netty, 2 rounds

DUR=30 WARM=30 ./run-all.sh throughput   # longer runs, steadier numbers
```

Single app, interactive:

```bash
cd 02-webflux-annotation && ./run.sh 8082
curl -s localhost:8082/pojo
```

Server logs land in `results/srv_<port>.log`.

## Pitfalls this layout encodes (all of them cost me a bogus result)

1. **`spring-boot-starter-web` + `-webflux` on one classpath → Tomcat wins.**
   Spring Boot deduces `SERVLET` and silently ignores the reactive setup, so you
   benchmark Spring MVC while believing it is WebFlux. That is why each app has
   its own pom, and why `run-all.sh` greps the startup line for
   `Netty started` / `Tomcat started` and prints it next to every result.
2. **Two `@SpringBootApplication` classes in one package** get both component-
   scanned → `Ambiguous mapping. Cannot map 'blockingApp' ... to {GET /json}`.
   Hence one package per app (`bench.annotation`, `bench.fn`, `bench.blocking`).
3. **`setsid java ... &` double-forks**, so `$!` is not the JVM's pid and
   `kill $!` leaves it running. The next benchmark then competes with a zombie
   server and every number drifts. The harness asserts `pgrep -x java` is empty
   before each run and force-kills on the way out.
4. **`pkill -f '<package name>'` matches your own shell** when that string appears
   in its command line (e.g. a heredoc), so the harness kills itself with exit
   143. Only `pkill -x java` (name-exact) is used as a fallback.
7. **Never benchmark via `mvn exec:java`** — it runs inside Maven's JVM. `run.sh`
   always forks a fresh JVM with explicit `-Xms/-Xmx`.

## Caveats

- The generator shares the 2 cores, so absolute rps is ~20–40% below what a
  dedicated load generator would show; the *comparison* between apps is fair.
- 15 s runs after 15 s warmup, single run each, `-Xms512m -Xmx512m`. Treat ±10%
  differences as noise — the byte[]-vs-String row in app 03 shows exactly that.
- Real services are usually I/O-bound (DB, downstream calls), where the concurrency
  table matters far more than the microbenchmark table.
