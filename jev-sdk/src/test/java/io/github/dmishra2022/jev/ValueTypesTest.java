package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Nested
    class Usages {
        @Test
        void totalsAndSums() {
            assertThat(Usage.of(296, 20).totalTokens()).hasValue(316);
            assertThat(Usage.EMPTY.totalTokens()).isEmpty();
            var inputOnly = new Usage(OptionalLong.of(5), OptionalLong.empty());
            assertThat(inputOnly.totalTokens()).hasValue(5);
            assertThat(new Usage(OptionalLong.empty(), OptionalLong.of(7)).totalTokens()).hasValue(7);
            assertThat(Usage.of(1, 2).plus(Usage.of(10, 20))).isEqualTo(Usage.of(11, 22));
            assertThat(Usage.EMPTY.plus(inputOnly)).isEqualTo(inputOnly);
            assertThat(inputOnly.plus(Usage.EMPTY)).isEqualTo(inputOnly);
        }

        @Test
        void nullsRejected() {
            assertThatNullPointerException().isThrownBy(() -> new Usage(null, OptionalLong.empty()));
            assertThatNullPointerException().isThrownBy(() -> new Usage(OptionalLong.empty(), null));
            assertThatNullPointerException().isThrownBy(() -> Usage.EMPTY.plus(null));
        }
    }

    @Test
    void modelInfoValidates() {
        var info = new ModelInfo("jev-latest", Optional.of("flagship"), Optional.empty());
        assertThat(info.name()).isEqualTo("jev-latest");
        assertThatNullPointerException().isThrownBy(() -> new ModelInfo(null, Optional.empty(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new ModelInfo("m", null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new ModelInfo("m", Optional.empty(), null));
    }

    @Nested
    class Results {
        private final EvaluationResult result = new EvaluationResult("jev-1.13.0", answers(), Usage.of(1, 2),
                Optional.of("req-1"));

        private static Map<String, Answer> answers() {
            var answers = new LinkedHashMap<String, Answer>();
            answers.put("urgent", new NoulAnswer(0.9));
            answers.put("team", new ChoiceAnswer("billing", Map.of("billing", 1.0), 0.9));
            answers.put("anger", new ScoreAnswer(1, Map.<Integer, Object>of(0, "a", 1, "b"), Map.of(0, 0.0, 1, 1.0), 1));
            answers.put("second", new NoulAnswer(0.1));
            return answers;
        }

        @Test
        void typedAccessors() {
            assertThat(result.noul("urgent").noul()).isEqualTo(0.9);
            assertThat(result.choice("team").choice()).isEqualTo("billing");
            assertThat(result.score("anger").level()).isEqualTo(1);
            assertThat(result.answer("urgent")).containsInstanceOf(NoulAnswer.class);
            assertThat(result.answer("missing")).isEmpty();
            assertThat(result.requestId()).contains("req-1");
        }

        @Test
        void groupedViewsKeepQuestionOrder() {
            assertThat(result.nouls().keySet()).containsExactly("urgent", "second");
            assertThat(result.choices()).containsOnlyKeys("team");
            assertThat(result.scores()).containsOnlyKeys("anger");
            assertThat(result.answers().keySet()).containsExactly("urgent", "team", "anger", "second");
        }

        @Test
        void wrongNameOrTypeFailsClearly() {
            assertThatThrownBy(() -> result.noul("nope")).isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("no answer named 'nope'");
            assertThatThrownBy(() -> result.choice("urgent")).isInstanceOf(IllegalStateException.class)
                    .hasMessage("answer 'urgent' is a noul, not a ChoiceAnswer");
        }

        @Test
        void validates() {
            var withNull = new HashMap<String, Answer>();
            withNull.put("x", null);
            assertThatNullPointerException().isThrownBy(
                    () -> new EvaluationResult("m", withNull, Usage.EMPTY, Optional.empty())).withMessage("answer for x");
            assertThatNullPointerException().isThrownBy(() -> new EvaluationResult(null, Map.of(), Usage.EMPTY, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new EvaluationResult("m", Map.of(), null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new EvaluationResult("m", Map.of(), Usage.EMPTY, null));
        }
    }

    @Nested
    class Requests {
        @Test
        void builderKeepsOrderAndModel() {
            var request = SystemOneRequest.builder()
                    .state(Map.of("ticket", "x"))
                    .question("b", Question.noul("b?"))
                    .question("a", Question.noul("a?"))
                    .questions(Map.of("c", Question.noul("c?")))
                    .model("jev-1.13.0")
                    .build();
            assertThat(request.questions().keySet()).containsExactly("b", "a", "c");
            assertThat(request.model()).contains("jev-1.13.0");
        }

        @Test
        void ofPreservesInsertionOrder() {
            var questions = new LinkedHashMap<String, Question>();
            questions.put("z", Question.noul("z"));
            questions.put("a", Question.noul("a"));
            var request = SystemOneRequest.of("state", questions);
            assertThat(request.questions().keySet()).containsExactly("z", "a");
            assertThat(request.model()).isEmpty();
        }

        @Test
        void rejectsInvalidInput() {
            var builder = SystemOneRequest.builder().question("a", Question.noul("a"));
            assertThatIllegalArgumentException().isThrownBy(() -> builder.question("a", Question.noul("again")))
                    .withMessage("duplicate question name: a");
            assertThatIllegalArgumentException().isThrownBy(() -> builder.question(" ", Question.noul("x")));
            assertThatNullPointerException().isThrownBy(() -> builder.question("b", null));
            assertThatNullPointerException().isThrownBy(() -> builder.questions(null));
            assertThatNullPointerException().isThrownBy(builder::build).withMessage("state");
            assertThatIllegalArgumentException().isThrownBy(() -> SystemOneRequest.builder().state("s").build())
                    .withMessage("at least one question is required");
            assertThatIllegalArgumentException().isThrownBy(
                    () -> builder.state("s").model(" ").build()).withMessage("model must not be blank");
            var withNull = new HashMap<String, Question>();
            withNull.put("q", null);
            assertThatNullPointerException().isThrownBy(() -> new SystemOneRequest("s", withNull, Optional.empty()))
                    .withMessage("question q");
            assertThatNullPointerException().isThrownBy(
                    () -> new SystemOneRequest("s", Map.of("q", Question.noul("q")), null));
        }
    }

    @Nested
    class Options {
        @Test
        void defaultsAreEmpty() {
            assertThat(RequestOptions.DEFAULT.model()).isEmpty();
            assertThat(RequestOptions.DEFAULT.retryPolicy()).isEmpty();
            assertThat(RequestOptions.DEFAULT.timeout()).isEmpty();
            assertThat(RequestOptions.DEFAULT.extraHeaders()).isEmpty();
            assertThat(RequestOptions.DEFAULT.extraBody()).isEmpty();
        }

        @Test
        void builderSetsEverything() {
            var options = RequestOptions.builder()
                    .model("jev-preview")
                    .retryPolicy(RetryPolicy.disabled())
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Tenant", "acme")
                    .bodyField("trace", true)
                    .build();
            assertThat(options.model()).contains("jev-preview");
            assertThat(options.retryPolicy()).contains(RetryPolicy.disabled());
            assertThat(options.timeout()).contains(Duration.ofSeconds(3));
            assertThat(options.extraHeaders()).containsEntry("X-Tenant", "acme");
            assertThat(options.extraBody()).containsEntry("trace", true);
        }

        @Test
        void validates() {
            assertThatIllegalArgumentException().isThrownBy(() -> RequestOptions.builder().model("").build());
            assertThatIllegalArgumentException().isThrownBy(() -> RequestOptions.builder().timeout(Duration.ZERO).build());
            assertThatIllegalArgumentException().isThrownBy(
                    () -> RequestOptions.builder().header("Authorization", "Bearer x").build());
            assertThatNullPointerException().isThrownBy(
                    () -> new RequestOptions(null, Optional.empty(), Optional.empty(), Map.of(), Map.of()));
            assertThatNullPointerException().isThrownBy(
                    () -> new RequestOptions(Optional.empty(), null, Optional.empty(), Map.of(), Map.of()));
            assertThatNullPointerException().isThrownBy(
                    () -> new RequestOptions(Optional.empty(), Optional.empty(), null, Map.of(), Map.of()));
        }
    }

    @Nested
    class HeaderValidation {
        @Test
        void acceptsOrdinaryHeaders() {
            HeaderRules.validateUserHeaders(Map.of("X-Tenant", "acme", "traceparent", "00-abc-01"));
        }

        @Test
        void rejectsProtectedRestrictedAndMalformed() {
            for (String name : List.of("Authorization", "accept", "Content-Type", "USER-AGENT")) {
                assertThatIllegalArgumentException().isThrownBy(() -> HeaderRules.validateUserHeader(name, "v"))
                        .withMessageContaining("managed by the SDK");
            }
            for (String name : List.of("Host", "content-length", "Connection", "Upgrade", "Expect")) {
                assertThatIllegalArgumentException().isThrownBy(() -> HeaderRules.validateUserHeader(name, "v"))
                        .withMessageContaining("java.net.http.HttpClient");
            }
            assertThatIllegalArgumentException().isThrownBy(() -> HeaderRules.validateUserHeader("bad name", "v"))
                    .withMessage("invalid header name: 'bad name'");
            assertThatNullPointerException().isThrownBy(() -> HeaderRules.validateName(null));
            for (String value : List.of("a\rb", "a\nb", "a\0b")) {
                assertThatIllegalArgumentException().isThrownBy(() -> HeaderRules.validateUserHeader("X-A", value))
                        .withMessageContaining("CR, LF or NUL");
            }
            assertThatNullPointerException().isThrownBy(() -> HeaderRules.validateUserHeader("X-A", null))
                    .withMessage("value of header X-A");
        }
    }

    @Nested
    class ArgumentChecks {
        @Test
        void durations() {
            assertThat(Checks.requirePositive(Duration.ofMillis(1), "d")).isEqualTo(Duration.ofMillis(1));
            assertThatIllegalArgumentException().isThrownBy(() -> Checks.requirePositive(Duration.ofMillis(-1), "d"));
            assertThatIllegalArgumentException().isThrownBy(() -> Checks.requirePositive(Duration.ZERO, "d"));
            assertThat(Checks.requireNonNegative(Duration.ZERO, "d")).isZero();
            assertThatIllegalArgumentException().isThrownBy(() -> Checks.requireNonNegative(Duration.ofNanos(-1), "d"));
            assertThatNullPointerException().isThrownBy(() -> Checks.requireNonNegative(null, "d"));
        }

        @Test
        void instructionsMayBeStructured() {
            assertThat(Checks.requireInstructions(List.of("a"))).isEqualTo(List.of("a"));
        }
    }
}
