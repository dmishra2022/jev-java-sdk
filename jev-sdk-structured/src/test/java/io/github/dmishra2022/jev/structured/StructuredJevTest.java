package io.github.dmishra2022.jev.structured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dmishra2022.jev.EvaluationResult;
import io.github.dmishra2022.jev.JevAuthenticationException;
import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevContext;
import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.JevInterruptedException;
import io.github.dmishra2022.jev.JevListener;
import io.github.dmishra2022.jev.JevServerException;
import io.github.dmishra2022.jev.JevTimeoutException;
import io.github.dmishra2022.jev.NoulAnswer;
import io.github.dmishra2022.jev.Question;
import io.github.dmishra2022.jev.RetryPolicy;
import io.github.dmishra2022.jev.SystemOneRequest;
import io.github.dmishra2022.jev.Usage;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StructuredJevTest {

    ScriptedJevServer server;
    JevClient client;
    StructuredJev structured;
    final List<String> correlationIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new ScriptedJevServer();
        client = JevClient.builder()
                .apiKey("test-key")
                .baseUrl(server.baseUrl())
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .retryPolicy(RetryPolicy.disabled())
                .listener(new JevListener() {
                    @Override
                    public void onAttemptStart(Attempt attempt) {
                        attempt.correlationId().ifPresent(correlationIds::add);
                    }
                })
                .build();
        structured = new StructuredJev(client);
    }

    @AfterEach
    void tearDown() {
        server.close();
        client.close();
    }

    static SystemOneRequest request(String state) {
        return SystemOneRequest.of(state, Map.of("q", Question.noul("q?")));
    }

    static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Test
    void requiresAClient() {
        assertThatNullPointerException().isThrownBy(() -> new StructuredJev(null));
    }

    @Nested
    class EvaluateAll {
        @Test
        void runsConcurrentlyAndKeepsRequestOrder() {
            var requests = List.of(request("delay=400;id=a"), request("delay=50;id=b"), request("delay=200;id=c"));
            long start = System.nanoTime();
            List<EvaluationResult> results = structured.evaluateAll(requests);
            assertThat(millisSince(start)).isLessThan(600);
            assertThat(results).hasSize(3).allSatisfy(r -> assertThat(r.noul("q").noul()).isEqualTo(0.8));
            assertThat(server.maxInFlight()).isEqualTo(3);
        }

        @Test
        void firstFailureCancelsTheRest() {
            var requests = List.of(request("delay=10000;id=slow"), request("status=401"), request("delay=10000;id=x"));
            long start = System.nanoTime();
            assertThatThrownBy(() -> structured.evaluateAll(requests)).isInstanceOf(JevAuthenticationException.class);
            assertThat(millisSince(start)).isLessThan(5000);
        }

        @Test
        void deadlineCancelsEverything() {
            var requests = List.of(request("delay=10000"), request("delay=0"));
            assertThatThrownBy(() -> structured.evaluateAll(requests, Duration.ofMillis(200)))
                    .isInstanceOfSatisfying(JevTimeoutException.class, e -> {
                        assertThat(e.timeout()).isEqualTo(Duration.ofMillis(200));
                        assertThat(e).hasMessage("jev-evaluate-all exceeded its deadline of 200 ms");
                    });
        }

        @Test
        void emptyBatch() {
            assertThat(structured.evaluateAll(List.of())).isEmpty();
        }

        @Test
        void scopedValuesAreInheritedBySubtasks() throws Exception {
            ScopedValue.where(JevContext.CORRELATION_ID, "batch-9")
                    .call(() -> structured.evaluateAll(List.of(request("a=1"), request("a=2"))));
            assertThat(correlationIds).containsExactly("batch-9", "batch-9");
        }

        @Test
        void validatesArguments() {
            assertThatNullPointerException().isThrownBy(() -> structured.evaluateAll(null));
            assertThatNullPointerException().isThrownBy(() -> structured.evaluateAll(Arrays.asList(request("a"), null)));
            assertThatIllegalArgumentException().isThrownBy(() -> structured.evaluateAll(List.of(), Duration.ZERO));
            assertThatIllegalArgumentException().isThrownBy(() -> structured.evaluateAll(List.of(), Duration.ofSeconds(-1)));
        }

        @Test
        void ownerInterruptionCancelsTheScope() throws Exception {
            var failure = new AtomicReference<Throwable>();
            var flag = new AtomicReference<Boolean>();
            Thread owner = Thread.ofVirtual().start(() -> {
                try {
                    structured.evaluateAll(List.of(request("delay=10000")));
                } catch (Throwable t) {
                    failure.set(t);
                    flag.set(Thread.currentThread().isInterrupted());
                }
            });
            while (server.states().isEmpty()) {
                Thread.sleep(10);
            }
            owner.interrupt();
            owner.join(Duration.ofSeconds(10));
            assertThat(failure.get()).isInstanceOf(JevInterruptedException.class)
                    .hasMessage("jev-evaluate-all was interrupted");
            assertThat(flag.get()).isTrue();
        }
    }

    @Nested
    class Settled {
        @Test
        void reportsEveryOutcomeInOrder() {
            var requests = List.of(request("id=ok"), request("status=401"), request("delay=10000"));
            List<Outcome<EvaluationResult>> outcomes = structured.evaluateAllSettled(requests, Duration.ofMillis(500));
            assertThat(outcomes).hasSize(3);
            assertThat(outcomes.get(0).isSuccess()).isTrue();
            assertThat(outcomes.get(0).orElseThrow().noul("q").noul()).isEqualTo(0.8);
            assertThat(outcomes.get(1).isSuccess()).isFalse();
            assertThat(outcomes.get(1)).isInstanceOfSatisfying(Outcome.Failure.class,
                    f -> assertThat(f.error()).isInstanceOf(JevAuthenticationException.class));
            assertThatThrownBy(outcomes.get(1)::orElseThrow).isInstanceOf(JevAuthenticationException.class);
            assertThat(outcomes.get(2)).isInstanceOfSatisfying(Outcome.Failure.class,
                    f -> assertThat(f.error()).isInstanceOf(JevTimeoutException.class)
                            .hasMessage("request did not finish within the deadline of 500 ms"));
        }

        @Test
        void withoutDeadlineWaitsForAll() {
            var outcomes = structured.evaluateAllSettled(List.of(request("delay=100"), request("status=500")), null);
            assertThat(outcomes).extracting(Outcome::isSuccess).containsExactly(true, false);
        }

        @Test
        void validatesArguments() {
            assertThatNullPointerException().isThrownBy(() -> structured.evaluateAllSettled(null, null));
        }
    }

    @Nested
    class Hedged {
        @Test
        void hedgeWinsWhenThePrimaryIsSlow() {
            long start = System.nanoTime();
            EvaluationResult result = structured.hedged(request("slowFirst=10000;id=hedge"), Duration.ofMillis(100));
            assertThat(result.noul("q").noul()).isEqualTo(0.8);
            assertThat(millisSince(start)).isLessThan(5000);
            assertThat(server.states()).hasSize(2);
        }

        @Test
        void fastPrimaryCancelsTheHedge() throws InterruptedException {
            structured.hedged(request("id=fast"), Duration.ofMillis(500));
            Thread.sleep(700);
            assertThat(server.states()).hasSize(1);
        }

        @Test
        void nonRetryableFailuresFailFastWithoutHedging() {
            long start = System.nanoTime();
            assertThatThrownBy(() -> structured.hedged(request("status=401"), Duration.ofSeconds(10)))
                    .isInstanceOf(JevAuthenticationException.class);
            assertThat(millisSince(start)).isLessThan(5000);
            assertThat(server.states()).hasSize(1);
        }

        @Test
        void retryableFailuresWaitForTheHedge() {
            assertThatThrownBy(() -> structured.hedged(request("status=503"), Duration.ZERO))
                    .isInstanceOf(JevServerException.class);
            assertThat(server.states()).hasSize(2);
        }

        @Test
        void nonJevFailuresAreFatal() {
            record Exploding(String value) {
                @Override
                public String value() {
                    throw new IllegalStateException("boom");
                }
            }
            var request = SystemOneRequest.of(new Exploding("x"), Map.of("q", Question.noul("q")));
            assertThatIllegalArgumentException().isThrownBy(() -> structured.hedged(request, Duration.ofSeconds(10)));
        }

        @Test
        void validatesArguments() {
            assertThatNullPointerException().isThrownBy(() -> structured.hedged(null, Duration.ZERO));
            assertThatNullPointerException().isThrownBy(() -> structured.hedged(request("a"), null));
            assertThatIllegalArgumentException().isThrownBy(() -> structured.hedged(request("a"), Duration.ofMillis(-1)));
        }
    }

    @Nested
    class Chunked {
        @Test
        void splitsMergesAndKeepsQuestionOrder() {
            var questions = new LinkedHashMap<String, Question>();
            questions.put("n1", Question.noul("one?"));
            questions.put("c1", Question.choice("which?", "alpha", "beta"));
            questions.put("s1", Question.score("how much?", "low", "mid", "high"));
            questions.put("n2", Question.noul("two?"));
            questions.put("n3", Question.noul("three?"));
            EvaluationResult result = structured.evaluateChunked("requestId=r", questions, 2);
            assertThat(server.states()).hasSize(3);
            assertThat(result.answers().keySet()).containsExactly("n1", "c1", "s1", "n2", "n3");
            assertThat(result.choice("c1").choice()).isEqualTo("alpha");
            assertThat(result.score("s1").levelDescription()).isEqualTo("mid");
            assertThat(result.usage()).isEqualTo(Usage.of(30, 6));
            assertThat(result.requestId()).contains("r,r,r");
            assertThat(result.model()).isEqualTo("jev-1.13.0");
        }

        @Test
        void exactMultipleProducesNoEmptyChunk() {
            var questions = Map.<String, Question>of("a", Question.noul("a"), "b", Question.noul("b"));
            EvaluationResult result = structured.evaluateChunked("plain", questions, 1);
            assertThat(server.states()).hasSize(2);
            assertThat(result.requestId()).isEmpty();
        }

        @Test
        void validatesArguments() {
            var questions = Map.<String, Question>of("a", Question.noul("a"));
            assertThatNullPointerException().isThrownBy(() -> structured.evaluateChunked(null, questions, 1));
            assertThatNullPointerException().isThrownBy(() -> structured.evaluateChunked("s", null, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> structured.evaluateChunked("s", questions, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> structured.evaluateChunked("s", Map.of(), 1));
        }

        @Test
        void mergeKeepsTheFirstModel() {
            var a = new EvaluationResult("m1", Map.of("x", new NoulAnswer(1)), Usage.EMPTY, Optional.of("1"));
            var b = new EvaluationResult("m2", Map.of("y", new NoulAnswer(0)), Usage.EMPTY, Optional.empty());
            EvaluationResult merged = StructuredJev.merge(new ArrayList<>(List.of(a, b)));
            assertThat(merged.model()).isEqualTo("m1");
            assertThat(merged.requestId()).contains("1");
            assertThat(merged.answers()).containsOnlyKeys("x", "y");
        }
    }

    @Nested
    class Propagation {
        @Test
        void uncheckedFailuresPassThrough() {
            var failure = new IllegalStateException("x");
            assertThat(StructuredJev.propagate(failure)).isSameAs(failure);
        }

        @Test
        void errorsAreRethrown() {
            var error = new AssertionError("fatal");
            assertThatThrownBy(() -> StructuredJev.propagate(error)).isSameAs(error);
        }

        @Test
        void checkedFailuresAreWrapped() {
            var checked = new Exception("checked");
            assertThat(StructuredJev.propagate(checked)).isInstanceOf(JevException.class).hasCause(checked)
                    .hasMessage("structured task failed: java.lang.Exception: checked");
        }

        @Test
        void outcomeOrElseThrowWrapsCheckedFailures() {
            Outcome<String> failure = new Outcome.Failure<>(new IOException("io"));
            assertThatThrownBy(failure::orElseThrow).isInstanceOf(JevException.class).hasCauseInstanceOf(IOException.class);
            assertThat(new Outcome.Success<>("v").orElseThrow()).isEqualTo("v");
            assertThatNullPointerException().isThrownBy(() -> new Outcome.Failure<String>(null));
        }
    }
}
