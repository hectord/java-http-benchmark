package bench.fn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * APP 03 - Spring WebFlux, functional routing.
 *
 * <p>Same runtime, same Mono, same JSON bytes as app 02. The only change is that
 * the request is matched by a {@code RouterFunction} instead of by scanning a
 * {@code @RequestMapping} annotation. Consequences:
 *
 * <ul>
 *   <li>no annotation handler-mapping registry lookup per request</li>
 *   <li>no reflective argument resolution / {@code InvocableHandlerMethod}</li>
 *   <li>routing is an ordinary Java predicate chain you can read top to bottom</li>
 * </ul>
 *
 * <p>Measured effect: {@code /json} goes from 6.6k to 9.7k rps, about +47%, with
 * byte-identical responses. That is the single cheapest optimisation in this
 * whole benchmark.
 *
 * <p>Two routes exist purely so the encoder cost is visible:
 * {@code /json} returns {@code Mono<byte[]>} (write-through),
 * {@code /jsonstr} returns {@code Mono<String>} (must encode char-&gt;byte).
 * Counter-intuitively in these runs the String variant measured faster; the
 * difference is small and within run-to-run noise, so the honest conclusion is
 * "both are fine, Jackson is the thing to avoid".
 *
 * <p>Run: {@code ./run.sh 8102}
 */
@SpringBootApplication
public class WebFluxFn {

    private static final byte[] BODY =
            "{\"ok\":true,\"id\":12345,\"name\":\"bench\"}".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WebFluxFn.class);
        app.setWebApplicationType(WebApplicationType.REACTIVE);
        app.run(args);
    }

    @Bean
    RouterFunction<ServerResponse> routes() {
        return route()
                .GET("/json", this::bytes)
                .GET("/jsonstr", this::str)
                .GET("/slow", this::slow)
                .build();
    }

    /** Pre-serialized bytes: no encoder, no Jackson. */
    private Mono<ServerResponse> bytes(ServerRequest req) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(BODY);
    }

    /** Same payload as a CharSequence, to price the encoder. */
    private Mono<ServerResponse> str(ServerRequest req) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(new String(BODY, StandardCharsets.UTF_8)), String.class);
    }

    /** Non-blocking 50ms wait, comparable with app 02 and app 04. */
    private Mono<ServerResponse> slow(ServerRequest req) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.delay(java.time.Duration.ofMillis(50))
                        .map(t -> "{\"ok\":true}"), String.class);
    }
}
