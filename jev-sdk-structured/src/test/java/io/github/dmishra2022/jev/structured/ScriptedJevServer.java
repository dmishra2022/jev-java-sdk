package io.github.dmishra2022.jev.structured;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A System One fake whose behavior is driven by the request's state string, so that concurrent requests are
 * deterministic regardless of arrival order. State syntax: {@code key=value;key=value} with keys
 * {@code delay} (ms), {@code status} (HTTP status), {@code slowFirst} (ms, applied to the first request with
 * this state only) and {@code requestId}.
 */
final class ScriptedJevServer implements AutoCloseable {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
    private final List<String> states = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    ScriptedJevServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> states() {
        return states;
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try (exchange) {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String state = request.get("state").asString();
            states.add(state);
            Map<String, String> script = parse(state);
            int occurrence = seen.computeIfAbsent(state, key -> new AtomicInteger()).incrementAndGet();
            long delay = Long.parseLong(script.getOrDefault("delay", "0"));
            if (occurrence == 1) {
                delay += Long.parseLong(script.getOrDefault("slowFirst", "0"));
            }
            if (delay > 0 && closed.await(delay, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (script.containsKey("requestId")) {
                exchange.getResponseHeaders().add("x-typesafe-request-id", script.get("requestId"));
            }
            int status = Integer.parseInt(script.getOrDefault("status", "200"));
            byte[] body = (status == 200 ? answer(request) : "{\"detail\":\"scripted " + status + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static Map<String, String> parse(String state) {
        var script = new LinkedHashMap<String, String>();
        for (String part : state.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                script.put(kv[0].strip(), kv[1].strip());
            }
        }
        return script;
    }

    private static String answer(JsonNode request) {
        ObjectNode answers = MAPPER.createObjectNode();
        for (var entry : request.get("questions").properties()) {
            JsonNode question = entry.getValue();
            ObjectNode answer = answers.putObject(entry.getKey());
            String type = question.get("type").asString();
            answer.put("type", type);
            switch (type) {
                case "noul" -> answer.put("noul", 0.8);
                case "choice" -> {
                    ObjectNode probabilities = answer.putObject("probabilities");
                    var options = question.get("criteria").propertyNames().stream().toList();
                    for (int i = 0; i < options.size(); i++) {
                        probabilities.put(options.get(i), i == 0 ? 1.0 : 0.0);
                    }
                    answer.put("choice", options.getFirst());
                    answer.put("confidence", 0.9);
                }
                default -> {
                    ObjectNode legend = answer.putObject("legend");
                    ObjectNode probabilities = answer.putObject("probabilities");
                    JsonNode levels = question.get("criteria");
                    for (int i = 0; i < levels.size(); i++) {
                        legend.set(String.valueOf(i), levels.get(i));
                        probabilities.put(String.valueOf(i), i == 1 ? 1.0 : 0.0);
                    }
                    answer.put("score", 1.0);
                    answer.put("confidence", 0.9);
                }
            }
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("model", "jev-1.13.0");
        response.set("answers", answers);
        response.putObject("usage").put("input_tokens", 10).put("output_tokens", 2);
        return MAPPER.writeValueAsString(response);
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
