package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Wire format: request encoding, strict response decoding and error-body interpretation. */
final class Codec {

    /** An HTTP response as seen by the decoders. */
    record Raw(int status, byte[] body, HttpHeaders headers, String endpoint) {
    }

    private static final int MAX_BODY_IN_MESSAGE = 200;
    private static final List<String> ERROR_FIELDS = List.of("detail", "message", "msg", "error");

    private final JsonMapper mapper;

    Codec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- requests

    byte[] encodeEvaluation(SystemOneRequest request, String model, Map<String, Object> extraBody) {
        var questions = new LinkedHashMap<String, Object>();
        request.questions().forEach((name, question) -> questions.put(name, encodeQuestion(question)));
        var body = new LinkedHashMap<String, Object>();
        body.put("state", request.state());
        body.put("model", model);
        body.put("questions", questions);
        body.putAll(extraBody);
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("request could not be serialized to JSON: " + e.getOriginalMessage(), e);
        }
    }

    static Map<String, Object> encodeQuestion(Question question) {
        var json = new LinkedHashMap<String, Object>();
        json.put("type", question.type().wireName());
        json.put("instructions", question.instructions());
        switch (question) {
            case NoulQuestion noul -> {
                if (noul.hasCriteria()) {
                    var criteria = new LinkedHashMap<String, Object>();
                    criteria.put("true", noul.whenTrue());
                    criteria.put("false", noul.whenFalse());
                    json.put("criteria", criteria);
                }
            }
            case ChoiceQuestion choice -> json.put("criteria", choice.options());
            case ScoreQuestion score -> json.put("criteria", score.levels());
        }
        return json;
    }

    // ---------------------------------------------------------------- responses

    EvaluationResult decodeEvaluation(SystemOneRequest request, Raw raw) {
        var parser = new Parser(raw);
        JsonNode root = parser.object(parser.root(), "$");
        String model = parser.string(parser.required(root, "model", "model"), "model");
        Usage usage = parser.usage(root.get("usage"));
        JsonNode answers = parser.object(parser.required(root, "answers", "answers"), "answers");
        var result = new LinkedHashMap<String, Answer>();
        for (var entry : request.questions().entrySet()) {
            String path = "answers." + entry.getKey();
            JsonNode node = parser.object(parser.required(answers, entry.getKey(), path), path);
            result.put(entry.getKey(), parser.answer(node, entry.getValue().type(), path));
        }
        return new EvaluationResult(model, result, usage, raw.headers().firstValue(JevApiException.REQUEST_ID_HEADER));
    }

    List<ModelInfo> decodeModels(Raw raw) {
        var parser = new Parser(raw);
        JsonNode root = parser.root();
        String path = root.isArray() ? "$" : "models";
        JsonNode list = root.isArray() ? root : parser.required(parser.object(root, "$"), "models", path);
        if (!list.isArray()) {
            throw parser.invalid(path, "expected an array");
        }
        var models = new ArrayList<ModelInfo>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String itemPath = path + "[" + i + "]";
            JsonNode item = parser.object(list.get(i), itemPath);
            models.add(new ModelInfo(
                    parser.string(parser.required(item, "name", itemPath + ".name"), itemPath + ".name"),
                    parser.optionalString(item.get("description"), itemPath + ".description"),
                    parser.optionalString(item.get("release_date"), itemPath + ".release_date")));
        }
        return List.copyOf(models);
    }

    // ---------------------------------------------------------------- errors

    JevApiException toApiException(Raw raw, Instant now) {
        String body = new String(raw.body(), StandardCharsets.UTF_8);
        String detail = errorDetail(raw.body()).orElse(abbreviate(body.strip()));
        String requestId = raw.headers().firstValue(JevApiException.REQUEST_ID_HEADER)
                .map(id -> " (request-id " + id + ")").orElse("");
        String message = "HTTP " + raw.status() + " from " + raw.endpoint() + (detail.isEmpty() ? "" : ": " + detail)
                + requestId;
        Duration retryAfter = RetryAfter.parse(raw.headers(), now).orElse(null);
        int status = raw.status();
        HttpHeaders headers = raw.headers();
        String endpoint = raw.endpoint();
        return switch (status) {
            case 400 -> new JevBadRequestException(message, status, body, headers, endpoint, retryAfter);
            case 401 -> new JevAuthenticationException(message, status, body, headers, endpoint, retryAfter);
            case 403 -> new JevPermissionDeniedException(message, status, body, headers, endpoint, retryAfter);
            case 404 -> new JevNotFoundException(message, status, body, headers, endpoint, retryAfter);
            case 422 -> new JevUnprocessableEntityException(message, status, body, headers, endpoint, retryAfter);
            case 429 -> new JevRateLimitException(message, status, body, headers, endpoint, retryAfter);
            default -> status >= 500
                    ? new JevServerException(message, status, body, headers, endpoint, retryAfter)
                    : new JevApiException(message, status, body, headers, endpoint, retryAfter);
        };
    }

    /** Best-effort human-readable message from JSON error bodies such as FastAPI's {@code detail}. */
    Optional<String> errorDetail(byte[] body) {
        try {
            return describe(mapper.readTree(body));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> describe(JsonNode node) {
        if (node.isString()) {
            String text = node.stringValue().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
        if (node.isArray()) {
            return node.isEmpty() ? Optional.empty() : describe(node.get(0));
        }
        if (node.isObject()) {
            for (String field : ERROR_FIELDS) {
                JsonNode value = node.get(field);
                Optional<String> text = value == null ? Optional.empty() : describe(value);
                if (text.isPresent()) {
                    return Optional.of(withLocation(node.get("loc"), text.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static String withLocation(JsonNode location, String text) {
        if (location == null || !location.isArray() || location.isEmpty()) {
            return text;
        }
        var path = new StringJoiner(".");
        for (JsonNode part : location) {
            path.add(part.asString());
        }
        return path + ": " + text;
    }

    static String abbreviate(String text) {
        return text.length() <= MAX_BODY_IN_MESSAGE ? text : text.substring(0, MAX_BODY_IN_MESSAGE) + "…";
    }

    /** Typed accessors that fail with a {@link JevResponseValidationException} naming the field path. */
    private final class Parser {
        private final Raw raw;

        Parser(Raw raw) {
            this.raw = raw;
        }

        JsonNode root() {
            try {
                return mapper.readTree(raw.body());
            } catch (JacksonException e) {
                throw invalid("$", "body is not valid JSON (" + e.getOriginalMessage() + ")");
            }
        }

        JsonNode required(JsonNode object, String field, String path) {
            JsonNode value = object.get(field);
            if (value == null || value.isNull()) {
                throw invalid(path, "is required");
            }
            return value;
        }

        JsonNode object(JsonNode node, String path) {
            if (!node.isObject()) {
                throw invalid(path, "expected an object");
            }
            return node;
        }

        String string(JsonNode node, String path) {
            if (!node.isString()) {
                throw invalid(path, "expected a string");
            }
            return node.stringValue();
        }

        Optional<String> optionalString(JsonNode node, String path) {
            return node == null || node.isNull() ? Optional.empty() : Optional.of(string(node, path));
        }

        double number(JsonNode node, String path) {
            if (!node.isNumber()) {
                throw invalid(path, "expected a number");
            }
            return node.doubleValue();
        }

        Usage usage(JsonNode node) {
            if (node == null || node.isNull()) {
                return Usage.EMPTY;
            }
            object(node, "usage");
            return new Usage(tokens(node.get("input_tokens"), "usage.input_tokens"),
                    tokens(node.get("output_tokens"), "usage.output_tokens"));
        }

        private OptionalLong tokens(JsonNode node, String path) {
            if (node == null || node.isNull()) {
                return OptionalLong.empty();
            }
            if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                throw invalid(path, "expected an integer");
            }
            return OptionalLong.of(node.longValue());
        }

        Answer answer(JsonNode node, QuestionType expected, String path) {
            JsonNode type = node.get("type");
            if (type != null && !type.isNull()) {
                String actual = string(type, path + ".type");
                if (!actual.equals(expected.wireName())) {
                    throw invalid(path + ".type", "expected '" + expected.wireName() + "' but was '" + actual + "'");
                }
            }
            return switch (expected) {
                case NOUL -> new NoulAnswer(number(required(node, "noul", path + ".noul"), path + ".noul"));
                case CHOICE -> new ChoiceAnswer(
                        string(required(node, "choice", path + ".choice"), path + ".choice"),
                        probabilities(required(node, "probabilities", path + ".probabilities"),
                                path + ".probabilities"),
                        confidence(node, path));
                case SCORE -> new ScoreAnswer(
                        number(required(node, "score", path + ".score"), path + ".score"),
                        legend(required(node, "legend", path + ".legend"), path + ".legend"),
                        levelProbabilities(required(node, "probabilities", path + ".probabilities"),
                                path + ".probabilities"),
                        confidence(node, path));
            };
        }

        private double confidence(JsonNode node, String path) {
            return number(required(node, "confidence", path + ".confidence"), path + ".confidence");
        }

        private Map<String, Double> probabilities(JsonNode node, String path) {
            object(node, path);
            var result = new LinkedHashMap<String, Double>();
            for (var entry : node.properties()) {
                result.put(entry.getKey(), number(entry.getValue(), path + "." + entry.getKey()));
            }
            return result;
        }

        private TreeMap<Integer, Object> legend(JsonNode node, String path) {
            object(node, path);
            var result = new TreeMap<Integer, Object>();
            for (var entry : node.properties()) {
                result.put(level(entry.getKey(), path), mapper.treeToValue(entry.getValue(), Object.class));
            }
            return result;
        }

        private TreeMap<Integer, Double> levelProbabilities(JsonNode node, String path) {
            object(node, path);
            var result = new TreeMap<Integer, Double>();
            for (var entry : node.properties()) {
                result.put(level(entry.getKey(), path), number(entry.getValue(), path + "." + entry.getKey()));
            }
            return result;
        }

        private int level(String key, String path) {
            try {
                return Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw invalid(path + "." + key, "expected an integer level key");
            }
        }

        JevResponseValidationException invalid(String path, String problem) {
            String message = "invalid response from " + raw.endpoint() + ": " + path + " " + problem;
            return new JevResponseValidationException(message, raw.status(),
                    new String(raw.body(), StandardCharsets.UTF_8), raw.headers(), raw.endpoint(), path);
        }
    }
}
