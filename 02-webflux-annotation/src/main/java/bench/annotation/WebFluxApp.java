package bench.annotation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * APP 02 - Spring WebFlux, annotation-based controller.
 *
 * <p>This is the mainstream "Java + Mono" setup: {@code @RestController} methods
 * whose return type is {@code Mono<T>}. Spring's {@code RequestMappingHandlerAdapter}
 * subscribes to the Mono and writes whatever it emits - i.e. the response is
 * assembled by the reactive pipeline rather than by blocking a worker thread.
 *
 * <p>Why a separate folder from app 03: the annotation path costs real CPU that
 * the functional path does not. Measured here, switching to functional routes
 * (app 03) is worth roughly +47% throughput for the identical payload.
 *
 * <p>Deliberately in its own package ({@code bench.annotation}) with its own
 * {@code @SpringBootApplication}: two application classes in the same package
 * get both component-scanned and Spring dies with
 * "Ambiguous mapping. Cannot map 'otherApp' method ... to {GET /json}".
 * That is a mistake worth knowing before you build a multi-app benchmark.
 *
 * <p>Run: {@code ./run.sh 8082}
 */
@SpringBootApplication
@RestController
public class WebFluxApp {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WebFluxApp.class);
        // Forced explicitly. Even with a clean webflux-only classpath this makes
        // the intent obvious, and it protects against the Tomcat trap described
        // in the pom comment.
        app.setWebApplicationType(WebApplicationType.REACTIVE);
        app.run(args);
    }

    /** Smallest possible reactive handler: Mono&lt;String&gt;. */
    @GetMapping("/json")
    public Mono<String> json() {
        return Mono.just("{\"ok\":true,\"id\":12345,\"name\":\"bench\"}");
    }

    /** The realistic shape: Mono&lt;Pojo&gt; goes through Jackson. */
    @GetMapping("/pojo")
    public Mono<Payload> pojo() {
        return Mono.just(new Payload());
    }

    /**
     * Async wait that holds no thread for 50ms. Contrast with app 04, where the
     * same 50ms pins one thread out of a fixed pool per in-flight request.
     */
    @GetMapping("/slow")
    public Mono<String> slow() {
        return Mono.delay(Duration.ofMillis(50)).map(t -> "{\"ok\":true}");
    }

    public static class Payload {
        public boolean ok = true;
        public int id = 12345;
        public String name = "bench";
    }
}
