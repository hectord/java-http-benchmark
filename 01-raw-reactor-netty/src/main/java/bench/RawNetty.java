package bench;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * APP 01 - Raw Reactor Netty, no Spring at all.
 *
 * <p>This is the lowest-level way to write a Mono-returning HTTP handler in the
 * Reactor ecosystem. There is no DispatcherHandler, no handler mapping, no
 * argument resolution, no media-type negotiation, no exception translator.
 * The handler is a plain functional interface {@code (req, res) -> Publisher<Void>}.
 *
 * <p>Purpose in the benchmark: establish the hardware ceiling. If this box can do
 * ~42k rps here but only ~7k in Spring WebFlux, the gap is framework cost and
 * NOT the cost of Mono / the reactive model.
 *
 * <p>Two knobs matter and both are command line:
 * <ul>
 *   <li>{@code port}   - listen port</li>
 *   <li>{@code loops}  - number of Reactor Netty event loop threads (default 2)</li>
 * </ul>
 * The event loop count is the reactive equivalent of a thread pool size: one
 * Netty worker thread per CPU core is the normal setting. Setting it to 1 while
 * pinning the process to a single core is how we got an honest "per vCPU" number
 * instead of letting the OS scheduler muddy it.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code /json}      - 45 byte pre-serialized JSON, Mono&lt;byte[]&gt;</li>
 *   <li>{@code /jsonstr}   - same bytes wrapped in Mono&lt;String&gt; (encoder cost)</li>
 *   <li>{@code /slow}      - Mono.delay(50ms): async wait, event loop stays free</li>
 * </ul>
 *
 * <p>Run: {@code ./run.sh 8081 2}
 */
public class RawNetty {

    /** Pre-serialized once at class load: no Jackson, no per-request encoding. */
    private static final byte[] JSON = "{\"ok\":true,\"id\":12345,\"name\":\"bench\"}"
            .getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        int loops = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        // daemon=true so the JVM can exit without a shutdown hook fight.
        LoopResources loop = LoopResources.create("bench-el", loops, true);

        HttpServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(port)
                .runOn(loop)
                // wiretap and compression both add measurable per-request cost,
                // and neither is part of the thing we are measuring.
                .wiretap(false)
                .compress(false)
                .handle((req, res) -> {
                    String path = req.fullPath();

                    if (path.startsWith("/json")) {
                        // Mono.just(...) here is a synchronous publisher; the point is
                        // that the handler *signature* is reactive, so it composes
                        // with async work, and Reactor Netty can write it without a
                        // blocking thread.
                        return res.header("Content-Type", "application/json")
                                .header("Content-Length", String.valueOf(JSON.length))
                                .sendByteArray(Mono.just(JSON))
                                .then();
                    }

                    if (path.startsWith("/jsonstr")) {
                        // Same payload as a String: lets us isolate the cost of the
                        // CharSequenceEncoder vs writing bytes directly.
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just(new String(JSON, StandardCharsets.UTF_8)))
                                .then();
                    }

                    if (path.startsWith("/slow")) {
                        // THE interesting case: a 50ms wait that occupies no thread.
                        // Mono.delay schedules a timer and releases the event loop.
                        // A thread-per-request server needs a whole thread for
                        // those 50ms, which is what the Tomcat app demonstrates.
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.delay(Duration.ofMillis(50))
                                        .map(t -> "{\"ok\":true}"))
                                .then();
                    }

                    return res.status(404).send().then();
                });

        DisposableServer bound = server.bindNow();
        System.out.println("RawNetty listening on " + port + " loops=" + loops);
        bound.onDispose().block();
    }
}
