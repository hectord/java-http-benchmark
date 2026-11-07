# App 05 — Plain Netty (no Reactor, no Spring)

**Purpose: price the Reactor layer exactly.** App 01 is Netty *plus* Reactor. This
is Netty alone. Same Netty version (4.1.135.Final), same JDK 21, same heap
(`-Xms512m -Xmx512m -XX:+AlwaysPreTouch`), same event-loop count, same payload,
same load generator. The delta is the Reactor tax and nothing else.

## What Reactor adds per request

Reactor Netty is not a different HTTP server — it is the same `ChannelPipeline`
with a reactive facade:

```java
// app 05 (this app): hand the pipeline a response and return
ctx.writeAndFlush(response(JSON, keepAlive), ctx.voidPromise());

// app 01: build a Publisher, let Reactor Netty subscribe and drive the write
res.header(...).sendByteArray(Mono.just(JSON)).then();
```

Reactively that means, per request: an `HttpServerOperations` instance, a `Mono`
and its subscription, operator bookkeeping, and Reactor Netty's extra pipeline
handlers (HTTP/2 upgrade support, metrics hooks, protocol validation). None of it
is free, and this app measures what it costs.

## Pipeline (deliberately minimal)

```
HttpServerCodec        bytes <-> HttpRequest/HttpResponse
HttpObjectAggregator   HttpRequest + body -> FullHttpRequest
RequestHandler         our code (allocation-light)
```

Fairness details: `SimpleChannelInboundHandler` auto-releases requests (no leak),
`writeAndFlush(..., ctx.voidPromise())` avoids one promise allocation per
response, the body is a pre-encoded `byte[]` wrapped zero-copy, keep-alive honours
`HttpUtil.isKeepAlive`, and `/slow` uses `ctx.executor().schedule(...)` — the exact
analogue of `Mono.delay(50ms)`, so the event loop is never blocked.

## Run

```bash
./run.sh 8085 2      # port, worker threads
./run.sh 8085 1      # 1 worker, pair with taskset -c 0 for a clean per-vCPU number
```

## Measured

### Throughput — tiny JSON, isolated cores (server cpu0, generator cpu1), 5 alternating rounds

| Stack | mean rps | rounds |
|---|---:|---|
| plain Netty (1 worker, 1 core) | **42,998** | 40.8k, 43.9k, 51.6k, 39.5k, 39.2k |
| Reactor Netty `Mono` (1 loop, 1 core) | **32,230** | 31.4k, 33.5k, 33.8k, 31.5k, 30.9k |

**→ Reactor costs ~25% per request** for a trivial synchronous handler. Plain
Netty was faster in every single round; the Reactor numbers are tightly clustered
(30.9–33.8k) while plain Netty drifted more (39.2–51.6k).

### Async wait — 50 ms delay, 1000 concurrent connections, 2 vCPU

| Stack | rps | p50 | threads |
|---|---:|---:|---:|
| plain Netty `/slow` | **18,764** | 51.7 ms | 22 |
| Reactor Netty `Mono.delay(50ms)` | **18,565** | 51.7 ms | 24 |

**→ Reactor costs ~1% here.** With 1000 connections and a 50 ms wait, the
theoretical ceiling is 1000/0.05 = 20,000 rps, and both stacks reach ~94% of it.
Per-request CPU is no longer the constraint — the timer wheel is — so Reactor's
25% allocation overhead becomes invisible.

## Why this matters more than the 25%

Same workload, same machine, three layers:

| `/slow` 50 ms, 1000 conns | rps | relative |
|---|---:|---:|
| plain Netty | 18,764 | 100% |
| Reactor Netty | 18,565 | 99% |
| Spring WebFlux (app 02) | 4,530 | **24%** |

Reactor is essentially free when you are waiting. **Spring is what costs 4×.**
If you are choosing a reactive stack and worried about "Reactor overhead", you are
optimising the wrong layer — the framework above Reactor is 16× more expensive
than Reactor itself in this measurement.

## Honest uncertainty

An earlier 2-worker A/B (server on cores 0+1, generator sharing core 1) gave a
much smaller, inconsistent delta: 37.1k vs 36.4k in one round, 44.7k vs 38.7k in
another. Since the server and generator then share a core, that configuration is
inherently noisy. The 5-round isolated A/B above is the trustworthy one and it is
unambiguous. Always pin a load generator away from the server before concluding
anything about single-digit-percent differences.
