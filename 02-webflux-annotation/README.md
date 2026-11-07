# App 02 — Spring WebFlux, annotation-based `@RestController`

This is the setup most people mean by "Java + Reactor Mono": a controller method
whose return type is `Mono<T>`.

## The reactive bit

```java
@GetMapping("/json")
public Mono<String> json() { return Mono.just("..."); }
```

`RequestMappingHandlerAdapter` subscribes to the returned `Mono` and writes what
it emits. Nothing blocks a thread: the response is produced by the pipeline. Add
`Mono.delay(...)`, `WebClient`, or a reactive DB driver and the waiting happens
on a timer/event loop instead of on a thread.

What you pay for that generality is the full annotation pipeline on every
request — handler lookup by pattern, argument resolvers, converters, content
negotiation, exception translation.

## Endpoints

| Path | Returns | Why it exists |
|---|---|---|
| `/json` | `Mono<String>` | cheapest reactive handler, comparable with app 01 |
| `/pojo` | `Mono<Payload>` | the realistic case, goes through Jackson |
| `/slow` | `Mono.delay(50ms)` | async wait that holds no thread |

## Run

```bash
./run.sh 8082
./run.sh 8082 -Dreactor.netty.ioWorkerCount=1    # 1 event loop, for per-vCPU numbers
```

## Measured on this box (2 vCPU, 64 keep-alive conns)

| Endpoint | rps | p50 | p99 |
|---|---:|---:|---:|
| `/json` `Mono<String>`, 2 loops | 6,600 | 9.8 ms | 23 ms |
| `/json` `Mono<String>`, 1 loop, 1 core | 2,800 | 20 ms | 43 ms |
| `/pojo` `Mono<Payload>` (Jackson) | 4,600 | 13 ms | 29 ms |
| `/json` idle, c=1 | — | **106 µs** | 2.0 ms |

## What it teaches

- **6.6k rps vs 42.6k in app 01** → the framework, not `Mono`, is the cost.
- **Jackson costs ~30%** on a 45-byte payload (`/json` → `/pojo`).
- **Annotation mapping is expensive**: app 03 does the same work with functional
  routes and reaches 9.7k rps.
- **Idle latency is excellent** (106 µs p50) — it is throughput, not latency,
  that suffers on 2 vCPUs.

> Gotcha encoded in this app: if `spring-boot-starter-web` sneaks onto the
> classpath, Spring Boot deduces `SERVLET`, ignores the reactive setup and
> starts Tomcat. Check the startup line says *"Netty started"*, not
> *"Tomcat started"* — that single line invalidated my first set of results.
