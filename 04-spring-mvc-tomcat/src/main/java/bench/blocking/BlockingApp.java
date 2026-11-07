package bench.blocking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * APP 04 - Spring MVC on Tomcat, the thread-per-request baseline.
 *
 * <p>No Reactor anywhere. Every in-flight request owns an OS thread for its whole
 * duration, which is the thing {@code Mono} is supposed to free you from.
 *
 * <p>This app exists to answer two questions honestly:
 * <ol>
 *   <li><b>Is Mono faster for trivial CPU-bound handlers?</b> On this box: no.
 *       5.8k rps (MVC, Jackson pojo) vs 4.6k (WebFlux, Jackson pojo). A blocking
 *       stack with a warm thread pool is extremely well optimised for "parse
 *       request, build 45 bytes, write response".</li>
 *   <li><b>When does Mono actually pay off?</b> When responses wait. With a 50ms
 *       downstream and 1000 concurrent clients, WebFlux held 4.5k rps on ~25
 *       threads. Tomcat needed 1000 threads to reach 5.4k and only managed 3.8k
 *       at its default 200. Same throughput class, 40x the threads - that is
 *       where the reactive model earns its complexity.</li>
 * </ol>
 *
 * <p>{@code /slow} uses {@code Thread.sleep(50)} to stand in for JDBC or a
 * blocking HTTP call. That is exactly the pattern that forces you to size a
 * thread pool against your worst-case downstream latency.
 *
 * <p>Run: {@code ./run.sh 8095} or, to raise the thread pool:
 * {@code ./run.sh 8095 --server.tomcat.threads.max=1000}
 */
@SpringBootApplication
@RestController
public class BlockingApp {

    public static void main(String[] args) {
        SpringApplication.run(BlockingApp.class, args);
    }

    @GetMapping("/json")
    public String json() {
        return "{\"ok\":true,\"id\":12345,\"name\":\"bench\"}";
    }

    @GetMapping("/pojo")
    public Payload pojo() {
        return new Payload();
    }

    /** Simulated blocking I/O: pins one Tomcat worker thread for 50ms. */
    @GetMapping("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(50);
        return "{\"ok\":true}";
    }

    public static class Payload {
        public boolean ok = true;
        public int id = 12345;
        public String name = "bench";
    }
}
