package io.github.dmishra2022.jev;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable local HTTP server: each request consumes the next queued {@link Responder}. */
final class MockServer implements AutoCloseable {

    /** Writes the response for one exchange. */
    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    /** What the server received. */
    record Recorded(String method, String path, Headers headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Responder> script = new LinkedBlockingQueue<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Responder fallback = json(500, "{\"detail\":\"no scripted response\"}");

    private MockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static MockServer start() {
        try {
            return new MockServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    MockServer enqueue(Responder responder) {
        script.add(responder);
        return this;
    }

    MockServer enqueue(int status, String body, String... headers) {
        return enqueue(json(status, body, headers));
    }

    /** Responder used once the script is exhausted. */
    MockServer otherwise(Responder responder) {
        this.fallback = responder;
        return this;
    }

    List<Recorded> requests() {
        return requests;
    }

    Recorded lastRequest() {
        return requests.getLast();
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            Responder responder = script.poll();
            (responder == null ? fallback : responder).respond(exchange);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // ------------------------------------------------------------ responders

    static Responder json(int status, String body, String... headers) {
        return exchange -> {
            for (int i = 0; i < headers.length; i += 2) {
                exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        };
    }

    /** Waits {@code delay} (or until the server closes) before delegating. */
    Responder delayed(Duration delay, Responder then) {
        return exchange -> {
            try {
                if (released.await(delay.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            then.respond(exchange);
        };
    }

    /** Never answers until the server closes. */
    Responder hang() {
        return delayed(Duration.ofMinutes(5), json(200, "{}"));
    }

    /** Closes the connection without sending a response. */
    static Responder drop() {
        return HttpExchange::close;
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
