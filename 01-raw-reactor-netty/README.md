# App 01 — Raw Reactor Netty (`Mono` with no framework)

**Purpose: establish the ceiling.** This is the fastest Mono-based HTTP server
you can write on the JVM with a sane amount of code. Everything measured in apps
02–04 is this number minus framework overhead.

## What is deliberately missing

| Feature | Raw Netty (here) | Spring WebFlux (02/03) |
|---|---|---|
| Routing | `if (path.startsWith(...))` | `RequestMappingHandlerMapping` / `RouterFunction` |
| Handler mapping | none | pattern parsing per request |
| Argument resolution | none | resolvers, converters, validators |
| Content negotiation | `Content-Type` set by hand | `MediaType` negotiation chain |
| JSON | bytes built by hand | Jackson `ObjectMapper` |
| Exception handling | none | `WebExceptionHandler` chain |
| Bean container | none | full context, proxies |

## Design decisions worth noticing

- **`LoopResources.create("bench-el", loops, true)`** — explicit event-loop size.
  This is the reactive analogue of a thread-pool size. 1 loop pinned to 1 core
  gives an honest per-vCPU number; 2 loops is the realistic default.
- **Pre-serialized `byte[]`** — `/json` writes bytes, `/jsonstr` writes a
  `String`. Comparing them isolates the cost of the `CharSequenceEncoder`
  (it has to encode to bytes and cannot always pre-compute `Content-Length`).
- **`Mono.delay(50ms)` for `/slow`** — occupies no thread while waiting. This is
  the property that makes the reactive model worth its complexity, and it is what
  app 04 (Tomcat) cannot do without one thread per in-flight request.
- **`wiretap(false)`, `compress(false)`** — both add real per-request cost and
  are not part of the measurement.

## Run

```bash
./run.sh 8081 2          # 2 event loops
./run.sh 8081 1          # 1 event loop (pair with taskset -c 0 for 1 vCPU)
```

## Measured on this box (2 vCPU Haswell, 64 keep-alive conns)

| Config | rps | p50 | p99 |
|---|---:|---:|---:|
| 2 event loops | **42,600** | 1.0 ms | 5.9 ms |
| 1 event loop, pinned to 1 core | 33,000 | 1.6 ms | 11.4 ms |

**~21k rps per vCPU.** That is the hardware ceiling. The lesson: on this machine
`Mono` itself is cheap — Spring is what costs 4–6×.
