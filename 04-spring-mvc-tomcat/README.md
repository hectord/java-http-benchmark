# App 04 — Spring MVC on Tomcat (the blocking baseline)

No Reactor. Every in-flight request holds an OS thread for its whole lifetime.
This is the control group: it tells you whether `Mono` actually bought anything,
instead of just assuming it did.

## Why you need this app

Because the answer is not the one the marketing suggests:

| Case | WebFlux `Mono` | Tomcat (blocking) |
|---|---:|---:|
| `/json` small payload, 64 conns | 6,600 | **7,700** |
| `/pojo` Jackson, 64 conns | 4,600 | **5,800** |
| `/slow` 50ms, 1000 conns | 4,530 | 5,370 (needs 1000 threads) |
| `/slow` 50ms, 1000 conns | 4,530 | 3,835 (default 200 threads) |
| threads under `/slow` load | **25** | 200–1000 |
| idle p50 latency, c=1 | **106 µs** | 121 µs |

For a trivial CPU-bound handler, plain Tomcat wins on this machine. A warm
thread pool with a couple of array copies is very hard to beat, and the reactive
pipeline adds allocation.

`Mono` wins on **thread cost and memory per in-flight request**, which becomes
rps only when your handler actually waits (DB, downstream HTTP, file I/O). The
`/slow` rows show both sides of that: default Tomcat collapses to 3.8k because
200 threads × (1/50ms) is a hard ceiling of 4k, and it takes 1000 threads to beat
WebFlux — at 40× the thread count.

## Endpoints

| Path | Behaviour |
|---|---|
| `/json` | hand-written JSON string |
| `/pojo` | Jackson-serialized object |
| `/slow` | `Thread.sleep(50)` — simulated blocking I/O |

## Run

```bash
./run.sh 8095                                            # default 200 threads
./run.sh 8095 --server.tomcat.threads.max=1000           # raise the ceiling
```

Watch RSS and thread count while `/slow` is under load: 1000 Tomcat threads is
~1 GB of stack address space, versus ~25 event-loop/scheduler threads for
WebFlux. On a 3.7 GB box that difference is the whole argument.
