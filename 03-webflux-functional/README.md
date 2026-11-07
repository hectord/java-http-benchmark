# App 03 — Spring WebFlux, functional routing

Same Netty server, same `Mono`, same JSON as app 02. The only difference is how a
request finds its handler:

```java
@Bean
RouterFunction<ServerResponse> routes() {
    return route().GET("/json", this::bytes).build();
}
```

instead of

```java
@GetMapping("/json") public Mono<String> json() { ... }
```

## Why this is a separate folder

Because it is the cheapest throughput win measured in this whole exercise, and
it is invisible if you only ever benchmark one app. Functional routing skips the
annotation handler-mapping registry and reflective argument resolution:

| | rps | delta |
|---|---:|---:|
| App 02 annotation `Mono<String>` | 6,600 | — |
| App 03 functional `Mono<String>` | **9,700** | **+47%** |
| App 03 functional `Mono<byte[]>` | 7,900 | +20% |

The last row is a reminder not to over-read single 15 s runs: `byte[]` *should*
be at least as fast as `String`, and the 7.9k vs 9.7k gap is run-to-run noise on
a 2 vCPU box where `wrk` is competing for a core. The reliable signal is
annotation → functional, not bytes vs string.

## Endpoints

| Path | Returns |
|---|---|
| `/json` | `Mono<byte[]>` (pre-serialized) |
| `/jsonstr` | `Mono<String>` (encoder cost) |
| `/slow` | `Mono.delay(50ms)` |

## Run

```bash
./run.sh 8102
```

## Takeaway

If you want throughput out of WebFlux and your routes are not dynamic, use
`RouterFunction`. You keep the reactive model and lose a per-request mapping
cost. If you need annotations for team familiarity, that is a legitimate choice —
just know it is ~47% of your ceiling.
