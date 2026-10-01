package io.github.dmishra2022.jev.supplychain.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local stand-in for the TypeSafe API that answers this application's questions with keyword heuristics, so the
 * service runs end to end (over real HTTP, through the SDK) without an API key. It understands explicit day/week
 * counts, revised ISO dates compared with the PO's original ETA, and evidence words such as booking or tracking
 * numbers. It is deliberately simple; the real model handles paraphrase, negation and context.
 */
public final class OfflineTypeSafeServer implements AutoCloseable {

    public static final String MODEL = "jev-offline-supplychain-heuristic";

    private static final List<String> DELAY = List.of("delay", "postpone", "pushed", "later than", "revised eta",
            "slip", "rolled", "unable to ship", "behind schedule", "will not make", "reschedul", "new eta",
            "new delivery date", "on hold", "allocation", "will follow", "balance will");
    private static final List<String> ON_TIME = List.of("on schedule", "on track", "as planned", "on time",
            "ahead of schedule", "original eta stands", "confirm the original");
    private static final Map<String, List<String>> CAUSES = Map.of(
            "logistics", List.of("port", "vessel", "congestion", "container", "carrier", "freight", "sailing",
                    "flight", "truck", "rolled"),
            "raw_material_shortage", List.of("raw material", "resin", "wafer", "cathode", "shortage", "allocation",
                    "component supply", "sub-supplier"),
            "production_capacity", List.of("capacity", "breakdown", "maintenance", "labour", "labor", "strike",
                    "line down", "machine"),
            "customs", List.of("customs", "clearance", "import licen", "export licen", "documentation"),
            "quality_hold", List.of("quality", "defect", "inspection", "rework", "non-conform", "contamina",
                    "out of spec"),
            "force_majeure", List.of("typhoon", "earthquake", "flood", "fire", "force majeure", "war", "sanction"));
    private static final List<String> EVIDENCE = List.of("booking", "tracking", "bill of lading", "b/l", "awb",
            "container no", "confirmed slot", "confirmed production slot");
    private static final List<String> VAGUE = List.of("approximately", "around", "a few", "tbc", "estimate",
            "roughly", "possibly", "expected to");
    private static final List<String> PARTIAL = List.of("partial", "part of the quantity", "first batch", "split",
            "% of the", "half of", "50%");
    private static final List<String> SYSTEMIC = List.of("allocation", "all customers", "industry", "further notice",
            "next quarter", "ongoing", "all orders", "coming months");
    private static final List<String> QUALITY = List.of("defect", "failed inspection", "quality", "non-conform",
            "contamina", "recall", "rework", "out of spec", "compliance hold");
    private static final Pattern DURATION = Pattern.compile(
            "(\\d{1,3})\\s*(?:-|to)?\\s*(\\d{1,3})?\\s*(?:business\\s+|working\\s+|calendar\\s+)?(day|week)");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");

    private final HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicLong requests = new AtomicLong();

    public OfflineTypeSafeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
                send(exchange, 401, "{\"detail\":\"missing bearer token\"}");
            } else if (path.equals("/v1/models")) {
                send(exchange, 200, "{\"models\":[{\"name\":\"" + MODEL
                        + "\",\"description\":\"Offline keyword heuristic\",\"release_date\":\"2026-01-01\"}]}");
            } else if (path.equals("/v1/systemone") && exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().add("x-typesafe-request-id", "offline-" + requests.incrementAndGet());
                send(exchange, 200, mapper.writeValueAsString(evaluate(mapper.readTree(body))));
            } else {
                send(exchange, 404, "{\"detail\":\"not found\"}");
            }
        }
    }

    ObjectNode evaluate(JsonNode request) {
        JsonNode state = request.path("state");
        String text = state.path("notice").asString("").toLowerCase(Locale.ROOT);
        LocalDate originalEta = parseDate(state.path("purchase_order").path("original_eta").asString(""));

        boolean delayed = containsAny(text, DELAY);
        boolean onTime = !delayed && containsAny(text, ON_TIME);
        Integer delayDays = onTime ? Integer.valueOf(0) : delayDays(text, originalEta);
        boolean vague = containsAny(text, VAGUE);

        ObjectNode response = mapper.createObjectNode();
        response.put("model", request.path("model").asString(MODEL));
        ObjectNode answers = response.putObject("answers");
        for (var question : request.path("questions").properties()) {
            ObjectNode answer = answers.putObject(question.getKey());
            switch (question.getKey()) {
                case "delay_confirmed" -> noul(answer, delayed || (delayDays != null && delayDays > 0) ? 0.93
                        : onTime ? 0.04 : 0.5);
                case "delay_window" -> choice(answer, question.getValue(), window(delayDays, delayed),
                        delayDays == null || vague ? 0.55 : 0.82);
                case "root_cause" -> choice(answer, question.getValue(), cause(text), 0.8);
                case "commitment" -> score(answer, question.getValue(),
                        onTime ? 2 : containsAny(text, EVIDENCE) && delayDays != null ? 3
                                : delayDays != null && !vague ? 2 : delayDays != null || vague ? 1 : 0);
                case "partial_offered" -> noul(answer, containsAny(text, PARTIAL) ? 0.88 : 0.07);
                case "systemic" -> noul(answer, containsAny(text, SYSTEMIC) ? 0.84 : 0.12);
                case "quality_risk" -> noul(answer, containsAny(text, QUALITY) ? 0.88 : 0.04);
                default -> noul(answer, 0.5);
            }
        }
        ObjectNode usage = response.putObject("usage");
        usage.put("input_tokens", 120 + text.length() / 4);
        usage.put("output_tokens", 4L * answers.size());
        return response;
    }

    private static Integer delayDays(String text, LocalDate originalEta) {
        Matcher date = ISO_DATE.matcher(text);
        while (originalEta != null && date.find()) {
            LocalDate revised = parseDate(date.group(1));
            if (revised != null && !revised.equals(originalEta)) {
                return (int) Math.max(0, ChronoUnit.DAYS.between(originalEta, revised));
            }
        }
        Matcher duration = DURATION.matcher(text);
        if (duration.find()) {
            int amount = Integer.parseInt(duration.group(2) != null ? duration.group(2) : duration.group(1));
            return duration.group(3).equals("week") ? amount * 7 : amount;
        }
        return null;
    }

    private static String window(Integer days, boolean delayed) {
        if (days == null) {
            return delayed ? "unspecified" : "none";
        }
        if (days == 0) {
            return delayed ? "under_one_week" : "none";
        }
        return days <= 6 ? "under_one_week" : days <= 14 ? "one_to_two_weeks" : days <= 28 ? "two_to_four_weeks"
                : "over_four_weeks";
    }

    private static String cause(String text) {
        String best = "other";
        int bestHits = 0;
        for (var cause : new TreeMap<>(CAUSES).entrySet()) {
            int hits = (int) cause.getValue().stream().filter(text::contains).count();
            if (hits > bestHits) {
                best = cause.getKey();
                bestHits = hits;
            }
        }
        return best;
    }

    private static void noul(ObjectNode answer, double p) {
        answer.put("type", "noul");
        answer.put("noul", p);
    }

    private static void choice(ObjectNode answer, JsonNode question, String chosen, double confidence) {
        answer.put("type", "choice");
        var options = question.path("criteria").properties();
        double rest = (1 - confidence) / Math.max(1, options.size() - 1);
        ObjectNode probabilities = answer.putObject("probabilities");
        for (var option : options) {
            probabilities.put(option.getKey(), round(option.getKey().equals(chosen) ? confidence : rest));
        }
        answer.put("choice", chosen);
        answer.put("confidence", confidence);
    }

    private static void score(ObjectNode answer, JsonNode question, int level) {
        answer.put("type", "score");
        JsonNode levels = question.path("criteria");
        int top = levels.size() - 1;
        int neighbours = (level > 0 ? 1 : 0) + (level < top ? 1 : 0);
        ObjectNode legend = answer.putObject("legend");
        ObjectNode probabilities = answer.putObject("probabilities");
        double expected = 0;
        for (int i = 0; i <= top; i++) {
            double p = i == level ? 0.86 : Math.abs(i - level) == 1 ? 0.14 / neighbours : 0;
            legend.set(String.valueOf(i), levels.get(i));
            probabilities.put(String.valueOf(i), round(p));
            expected += i * p;
        }
        answer.put("score", round(expected));
        answer.put("confidence", 0.86);
    }

    private static boolean containsAny(String text, List<String> needles) {
        return needles.stream().anyMatch(text::contains);
    }

    private static LocalDate parseDate(String value) {
        try {
            return value.isEmpty() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
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
