package io.github.dmishra2022.jev.sample;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local, keyword-heuristic imitation of the TypeSafe API so the sample runs without an API key.
 *
 * <p>It speaks the same wire format as {@code /v1/systemone} and {@code /v1/models}, adds small random latency,
 * and can inject {@code 529 Overloaded} every N-th call to exercise the SDK's retry policy. The answers are crude
 * keyword matches, not model output.
 */
public final class OfflineTypeSafeServer implements AutoCloseable {

    public static final String MODEL = "jev-offline-heuristic";

    private static final Set<String> STOPWORDS = Set.of("does", "the", "this", "that", "with", "from", "need",
            "help", "customer", "which", "should", "handle", "ticket", "right", "expressed", "what", "into");
    private static final Map<String, List<String>> SIGNALS = Map.of(
            "urgent", List.of("urgent", "asap", "immediately", "now", "today", "down", "blocked", "outage",
                    "critical", "losing", "production"),
            "cancel", List.of("cancel", "cancelling", "leave", "leaving", "switch", "switching", "competitor",
                    "unsubscribe", "close"),
            "frustrated", List.of("angry", "ridiculous", "unacceptable", "terrible", "worst", "again", "still",
                    "third", "furious", "joke"));

    private final HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();
    private final int failEvery;

    /** @param failEvery return 529 on every N-th evaluation request; 0 disables fault injection */
    public OfflineTypeSafeServer(int failEvery) throws IOException {
        this.failEvery = failEvery;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public int calls() {
        return calls.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                send(exchange, 401, "{\"detail\":\"missing bearer token\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/models")) {
                send(exchange, 200, "{\"models\":[{\"name\":\"" + MODEL
                        + "\",\"description\":\"Offline keyword heuristic\",\"release_date\":\"2026-01-01\"}]}");
                return;
            }
            if (!path.equals("/v1/systemone") || !exchange.getRequestMethod().equals("POST")) {
                send(exchange, 404, "{\"detail\":\"not found\"}");
                return;
            }
            int call = calls.incrementAndGet();
            sleepQuietly(ThreadLocalRandom.current().nextLong(20, 120));
            if (failEvery > 0 && call % failEvery == 0) {
                exchange.getResponseHeaders().add("retry-after-ms", "50");
                send(exchange, 529, "{\"detail\":\"overloaded (injected)\"}");
                return;
            }
            exchange.getResponseHeaders().add("x-typesafe-request-id", "offline-" + call);
            send(exchange, 200, mapper.writeValueAsString(evaluate(mapper.readTree(body))));
        }
    }

    private ObjectNode evaluate(JsonNode request) {
        Set<String> words = words(flatten(request.path("state"), new StringBuilder()).toString());
        long exclamations = request.path("state").toString().chars().filter(c -> c == '!').count();
        ObjectNode response = mapper.createObjectNode();
        response.put("model", request.path("model").asString(MODEL));
        ObjectNode answers = response.putObject("answers");
        for (var entry : request.path("questions").properties()) {
            JsonNode question = entry.getValue();
            String instructions = question.path("instructions").asString("").toLowerCase(Locale.ROOT);
            ObjectNode answer = answers.putObject(entry.getKey());
            String type = question.path("type").asString();
            answer.put("type", type);
            switch (type) {
                case "noul" -> answer.put("noul", noul(instructions, words));
                case "choice" -> choice(answer, question.path("criteria"), words);
                default -> score(answer, question.path("criteria"), hits(words, SIGNALS.get("frustrated"))
                        + (int) Math.min(2, exclamations));
            }
        }
        ObjectNode usage = response.putObject("usage");
        usage.put("input_tokens", 40 + words.size());
        usage.put("output_tokens", 4L * answers.size());
        return response;
    }

    private static double noul(String instructions, Set<String> words) {
        int hits = 0;
        for (var signal : SIGNALS.entrySet()) {
            if (instructions.contains(signal.getKey())) {
                hits += hits(words, signal.getValue());
            }
        }
        return round(Math.min(0.97, 0.06 + 0.3 * hits));
    }

    private static void choice(ObjectNode answer, JsonNode criteria, Set<String> words) {
        var weights = new LinkedHashMap<String, Double>();
        double total = 0;
        for (var option : criteria.properties()) {
            String text = option.getKey() + " " + option.getValue().asString("");
            double weight = 0.2 + hits(words, List.copyOf(words(text)));
            weights.put(option.getKey(), weight);
            total += weight;
        }
        String best = null;
        double bestP = -1;
        ObjectNode probabilities = answer.putObject("probabilities");
        for (var entry : weights.entrySet()) {
            double p = round(entry.getValue() / total);
            probabilities.put(entry.getKey(), p);
            if (p > bestP) {
                best = entry.getKey();
                bestP = p;
            }
        }
        answer.put("choice", best);
        answer.put("confidence", bestP);
    }

    private static void score(ObjectNode answer, JsonNode criteria, int intensity) {
        int levels = criteria.size();
        int level = Math.min(levels - 1, intensity);
        ObjectNode legend = answer.putObject("legend");
        ObjectNode probabilities = answer.putObject("probabilities");
        int neighbours = (level > 0 ? 1 : 0) + (level < levels - 1 ? 1 : 0);
        double score = 0;
        for (int i = 0; i < levels; i++) {
            double p = i == level ? 0.85 : Math.abs(i - level) == 1 ? 0.15 / neighbours : 0;
            legend.set(String.valueOf(i), criteria.get(i));
            probabilities.put(String.valueOf(i), round(p));
            score += i * p;
        }
        answer.put("score", round(score));
        answer.put("confidence", 0.85);
    }

    private static StringBuilder flatten(JsonNode node, StringBuilder out) {
        if (node.isString()) {
            out.append(node.stringValue()).append(' ');
        }
        for (JsonNode child : node) {
            flatten(child, out);
        }
        return out;
    }

    private static Set<String> words(String text) {
        var words = new HashSet<String>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            String word = raw.endsWith("s") && raw.length() > 4 ? raw.substring(0, raw.length() - 1) : raw;
            if (word.length() >= 4 && !STOPWORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static int hits(Set<String> words, List<String> keywords) {
        return (int) keywords.stream().filter(words::contains).count();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
