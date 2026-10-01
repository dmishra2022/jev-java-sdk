package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class CodecTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final Codec codec = new Codec(MAPPER);

    static final SystemOneRequest MIXED = SystemOneRequest.builder()
            .state("Help! My payouts have been failing for 3 days.")
            .question("is_urgent", Question.noul("Does this convey urgency?"))
            .question("department", Question.choice("Which team?", "billing", "technical", "sales"))
            .question("frustration", Question.score("How frustrated?", "Calm", "Frustrated", "Very angry"))
            .build();

    static final String MIXED_RESPONSE = """
            {
              "model": "jev-1.13.0",
              "answers": {
                "is_urgent": {"type": "noul", "noul": 0.95},
                "department": {"type": "choice", "choice": "billing",
                               "probabilities": {"billing": 0.88, "technical": 0.12, "sales": 0.0},
                               "confidence": 0.81},
                "frustration": {"type": "score", "score": 1.05,
                                "legend": {"0": "Calm", "1": "Frustrated", "2": {"label": "Very angry"}},
                                "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05},
                                "confidence": 0.92},
                "unrequested": {"type": "noul", "noul": 0.1}
              },
              "usage": {"input_tokens": 318, "output_tokens": 34}
            }
            """;

    static Codec.Raw raw(int status, String body, String... headers) {
        return new Codec.Raw(status, body.getBytes(StandardCharsets.UTF_8), SupportTest.headers(headers),
                "POST https://api.typesafe.ai/v1/systemone");
    }

    private Map<Object, Object> json(byte[] bytes) {
        return map(MAPPER.readValue(bytes, Map.class));
    }

    private static Map<Object, Object> map(Object value) {
        return new java.util.LinkedHashMap<>((Map<?, ?>) value);
    }

    @Nested
    class Encoding {
        @Test
        void encodesEveryQuestionType() {
            var request = SystemOneRequest.builder()
                    .state(Map.of("message", "hi"))
                    .question("plain", Question.noul("Is it billing?"))
                    .question("criteria", Question.noul("Urgent?", "Explicitly time-sensitive", null))
                    .question("choice", Question.choice("Team?", Map.of("billing", "Payments")))
                    .question("score", Question.score("Anger?", "Calm", null))
                    .build();
            Map<Object, Object> body = json(codec.encodeEvaluation(request, "jev-latest", Map.of()));
            assertThat(body.keySet()).containsExactly("state", "model", "questions");
            assertThat(body.get("state")).isEqualTo(Map.of("message", "hi"));
            assertThat(body.get("model")).isEqualTo("jev-latest");
            Map<Object, Object> questions = map(body.get("questions"));
            assertThat(questions.keySet()).containsExactly("plain", "criteria", "choice", "score");
            assertThat(questions.get("plain")).isEqualTo(Map.of("type", "noul", "instructions", "Is it billing?"));
            Map<Object, Object> criteria = map(map(questions.get("criteria")).get("criteria"));
            assertThat(criteria).containsEntry("true", "Explicitly time-sensitive").containsEntry("false", null);
            assertThat(questions.get("choice"))
                    .isEqualTo(Map.of("type", "choice", "instructions", "Team?", "criteria", Map.of("billing", "Payments")));
            assertThat(map(questions.get("score")).get("criteria")).isEqualTo(Arrays.asList("Calm", null));
        }

        @Test
        void extraBodyIsMergedLastAndWins() {
            Map<Object, Object> body = json(codec.encodeEvaluation(MIXED, "jev-latest", Map.of("model", "override", "flag", true)));
            assertThat(body.get("model")).isEqualTo("override");
            assertThat(body.get("flag")).isEqualTo(true);
        }

        @Test
        void recordsSerializeAsObjects() {
            record Ticket(String subject, int priority) {
            }
            var request = SystemOneRequest.of(new Ticket("Refund", 2), Map.of("q", Question.noul("Refund?")));
            assertThat(json(codec.encodeEvaluation(request, "m", Map.of())).get("state"))
                    .isEqualTo(Map.of("subject", "Refund", "priority", 2));
        }

        @Test
        void unserializableStateIsAnArgumentError() {
            record Exploding(String value) {
                @Override
                public String value() {
                    throw new IllegalStateException("boom");
                }
            }
            var request = SystemOneRequest.of(new Exploding("x"), Map.of("q", Question.noul("q")));
            assertThatIllegalArgumentException().isThrownBy(() -> codec.encodeEvaluation(request, "m", Map.of()))
                    .withMessageStartingWith("request could not be serialized to JSON");
        }
    }

    @Nested
    class Decoding {
        @Test
        void decodesTheDocumentedExample() {
            EvaluationResult result = codec.decodeEvaluation(MIXED, raw(200, MIXED_RESPONSE,
                    "x-typesafe-request-id", "req-42"));
            assertThat(result.model()).isEqualTo("jev-1.13.0");
            assertThat(result.requestId()).contains("req-42");
            assertThat(result.usage()).isEqualTo(Usage.of(318, 34));
            assertThat(result.answers().keySet()).containsExactly("is_urgent", "department", "frustration");
            assertThat(result.noul("is_urgent").noul()).isEqualTo(0.95);
            ChoiceAnswer department = result.choice("department");
            assertThat(department.choice()).isEqualTo("billing");
            assertThat(department.probabilities().keySet()).containsExactly("billing", "technical", "sales");
            assertThat(department.confidence()).isEqualTo(0.81);
            ScoreAnswer frustration = result.score("frustration");
            assertThat(frustration.score()).isEqualTo(1.05);
            assertThat(frustration.legend()).containsEntry(0, "Calm").containsEntry(2, Map.of("label", "Very angry"));
            assertThat(frustration.probabilities()).containsEntry(1, 0.95);
        }

        @Test
        void typeAndUsageAreOptional() {
            var request = SystemOneRequest.of("s", Map.of("q", Question.noul("q")));
            EvaluationResult result = codec.decodeEvaluation(request,
                    raw(200, "{\"model\":\"m\",\"answers\":{\"q\":{\"noul\":1}}}"));
            assertThat(result.usage()).isEqualTo(Usage.EMPTY);
            assertThat(result.requestId()).isEmpty();
            assertThat(codec.decodeEvaluation(request, raw(200,
                    "{\"model\":\"m\",\"usage\":null,\"answers\":{\"q\":{\"type\":null,\"noul\":0}}}")).usage())
                    .isEqualTo(Usage.EMPTY);
            assertThat(codec.decodeEvaluation(request, raw(200,
                    "{\"model\":\"m\",\"usage\":{\"input_tokens\":null},\"answers\":{\"q\":{\"noul\":0}}}")).usage())
                    .isEqualTo(Usage.EMPTY);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                not json                 | {                                                        | $
                empty body               | ''                                                       | $
                array root               | []                                                       | $
                missing model            | {"answers":{}}                                           | model
                null model               | {"model":null,"answers":{}}                              | model
                numeric model            | {"model":1,"answers":{}}                                 | model
                usage not object         | {"model":"m","usage":3,"answers":{}}                     | usage
                fractional tokens        | {"model":"m","usage":{"input_tokens":1.5},"answers":{}}  | usage.input_tokens
                huge tokens              | {"model":"m","usage":{"output_tokens":1e30},"answers":{}} | usage.output_tokens
                overflowing tokens       | {"model":"m","usage":{"output_tokens":99999999999999999999},"answers":{}} | usage.output_tokens
                missing answers          | {"model":"m"}                                            | answers
                answers not object       | {"model":"m","answers":[]}                               | answers
                missing answer           | {"model":"m","answers":{}}                               | answers.is_urgent
                answer not object        | {"model":"m","answers":{"is_urgent":1}}                  | answers.is_urgent
                wrong type               | {"model":"m","answers":{"is_urgent":{"type":"score"}}}   | answers.is_urgent.type
                non-string type          | {"model":"m","answers":{"is_urgent":{"type":7}}}         | answers.is_urgent.type
                missing noul             | {"model":"m","answers":{"is_urgent":{}}}                 | answers.is_urgent.noul
                string noul              | {"model":"m","answers":{"is_urgent":{"noul":"0.9"}}}     | answers.is_urgent.noul
                """)
        void invalidNoulResponsesNameTheField(String scenario, String body, String path) {
            var request = SystemOneRequest.of("s", Map.of("is_urgent", Question.noul("q")));
            assertThatThrownBy(() -> codec.decodeEvaluation(request, raw(200, body)))
                    .isInstanceOfSatisfying(JevResponseValidationException.class, e -> {
                        assertThat(e.fieldPath()).isEqualTo(path);
                        assertThat(e.status()).isEqualTo(200);
                        assertThat(e.body()).isEqualTo(body);
                        assertThat(e.getMessage()).startsWith("invalid response from POST https://api.typesafe.ai");
                    });
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                missing choice           | {"probabilities":{},"confidence":1}                        | answers.c.choice
                probabilities not object | {"choice":"a","probabilities":[],"confidence":1}           | answers.c.probabilities
                bad probability          | {"choice":"a","probabilities":{"a":"x"},"confidence":1}    | answers.c.probabilities.a
                missing confidence       | {"choice":"a","probabilities":{"a":1}}                     | answers.c.confidence
                """)
        void invalidChoiceResponses(String scenario, String answer, String path) {
            var request = SystemOneRequest.of("s", Map.of("c", Question.choice("q", "a")));
            assertThatThrownBy(() -> codec.decodeEvaluation(request,
                    raw(200, "{\"model\":\"m\",\"answers\":{\"c\":" + answer + "}}")))
                    .isInstanceOfSatisfying(JevResponseValidationException.class,
                            e -> assertThat(e.fieldPath()).isEqualTo(path));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                missing score            | {"legend":{},"probabilities":{},"confidence":1}                | answers.s.score
                legend not object        | {"score":1,"legend":[],"probabilities":{},"confidence":1}      | answers.s.legend
                bad legend key           | {"score":1,"legend":{"x":"a"},"probabilities":{},"confidence":1} | answers.s.legend.x
                bad probability key      | {"score":1,"legend":{},"probabilities":{"one":1},"confidence":1} | answers.s.probabilities.one
                bad probability value    | {"score":1,"legend":{},"probabilities":{"1":true},"confidence":1} | answers.s.probabilities.1
                probabilities not object | {"score":1,"legend":{},"probabilities":2,"confidence":1}       | answers.s.probabilities
                missing confidence       | {"score":1,"legend":{},"probabilities":{}}                     | answers.s.confidence
                """)
        void invalidScoreResponses(String scenario, String answer, String path) {
            var request = SystemOneRequest.of("s", Map.of("s", Question.score("q", "a", "b")));
            assertThatThrownBy(() -> codec.decodeEvaluation(request,
                    raw(200, "{\"model\":\"m\",\"answers\":{\"s\":" + answer + "}}")))
                    .isInstanceOfSatisfying(JevResponseValidationException.class,
                            e -> assertThat(e.fieldPath()).isEqualTo(path));
        }
    }

    @Nested
    class Models {
        @Test
        void wrappedList() {
            List<ModelInfo> models = codec.decodeModels(raw(200, """
                    {"models": [
                      {"name": "jev-latest", "description": "Flagship", "release_date": "2026-05-01"},
                      {"name": "jev-preview", "description": null}
                    ]}"""));
            assertThat(models).extracting(ModelInfo::name).containsExactly("jev-latest", "jev-preview");
            assertThat(models.getFirst().description()).contains("Flagship");
            assertThat(models.getFirst().releaseDate()).contains("2026-05-01");
            assertThat(models.get(1).description()).isEmpty();
            assertThat(models.get(1).releaseDate()).isEmpty();
        }

        @Test
        void bareArray() {
            assertThat(codec.decodeModels(raw(200, "[{\"name\":\"jev-latest\"}]"))).hasSize(1);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                string root       | "x"                                   | $
                missing list      | {}                                    | models
                list not array    | {"models":{}}                         | models
                item not object   | {"models":[1]}                        | models[0]
                missing name      | [{}]                                  | $[0].name
                bad description   | [{"name":"a","description":3}]        | $[0].description
                bad release date  | [{"name":"a","release_date":false}]   | $[0].release_date
                """)
        void invalidModels(String scenario, String body, String path) {
            assertThatThrownBy(() -> codec.decodeModels(raw(200, body)))
                    .isInstanceOfSatisfying(JevResponseValidationException.class,
                            e -> assertThat(e.fieldPath()).isEqualTo(path));
        }
    }

    @Nested
    class Errors {
        private final Instant now = Instant.parse("2026-10-01T00:00:00Z");

        @ParameterizedTest
        @CsvSource({
                "400, JevBadRequestException", "401, JevAuthenticationException",
                "403, JevPermissionDeniedException", "404, JevNotFoundException",
                "422, JevUnprocessableEntityException", "429, JevRateLimitException",
                "500, JevServerException", "529, JevServerException", "418, JevApiException",
                "302, JevApiException"})
        void statusesMapToExceptionTypes(int status, String type) {
            JevApiException e = codec.toApiException(raw(status, ""), now);
            assertThat(e.getClass().getSimpleName()).isEqualTo(type);
            assertThat(e.status()).isEqualTo(status);
            assertThat(e).hasMessage("HTTP " + status + " from POST https://api.typesafe.ai/v1/systemone");
        }

        @Test
        void messageCarriesDetailRequestIdAndRetryAfter() {
            JevApiException e = codec.toApiException(raw(429, "{\"detail\":\"Too many requests\"}",
                    "x-typesafe-request-id", "r-9", "retry-after", "3"), now);
            assertThat(e).isInstanceOf(JevRateLimitException.class)
                    .hasMessage("HTTP 429 from POST https://api.typesafe.ai/v1/systemone: Too many requests (request-id r-9)");
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
            assertThat(e.body()).isEqualTo("{\"detail\":\"Too many requests\"}");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                fastapi validation | {"detail":[{"loc":["body","questions","q"],"msg":"Field required"}] } | body.questions.q: Field required
                loc not array      | {"detail":[{"loc":"body","msg":"bad"}]}                         | bad
                empty loc          | {"detail":[{"loc":[],"msg":"bad"}]}                             | bad
                message field      | {"message":"Invalid API key"}                                   | Invalid API key
                nested error       | {"error":{"message":"Overloaded"}}                             | Overloaded
                error string       | {"error":"nope"}                                                | nope
                skips blank detail | {"detail":"  ","message":"real"}                                | real
                bare string        | "plain"                                                         | plain
                """)
        void detailExtraction(String scenario, String body, String expected) {
            assertThat(codec.errorDetail(body.getBytes(StandardCharsets.UTF_8))).contains(expected);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                not json      | <html>
                empty array   | {"detail":[]}
                number        | 42
                no known keys | {"code":7}
                empty string  | ""
                """)
        void noDetail(String scenario, String body) {
            assertThat(codec.errorDetail(body.getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        @Test
        void nonJsonBodiesAreAbbreviatedIntoTheMessage() {
            String html = "<html>" + "x".repeat(500) + "</html>";
            JevApiException e = codec.toApiException(raw(502, html), now);
            assertThat(e.getMessage()).endsWith("…").hasSizeLessThan(300);
            assertThat(e.body()).isEqualTo(html);
            assertThat(Codec.abbreviate("short")).isEqualTo("short");
            String exactlyAtLimit = "x".repeat(200);
            assertThat(Codec.abbreviate(exactlyAtLimit)).isEqualTo(exactlyAtLimit);
            assertThat(Codec.abbreviate(exactlyAtLimit + "y")).isEqualTo(exactlyAtLimit + "…");
        }

        @Test
        void headersArePreserved() {
            HttpHeaders headers = codec.toApiException(raw(500, "", "X-A", "1"), now).headers();
            assertThat(headers.firstValue("x-a")).contains("1");
        }
    }
}
