package io.github.dmishra2022.jev;

import static io.github.dmishra2022.jev.MockServer.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JevClientTest {

    static final String NOUL_RESPONSE = """
            {"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.9}},"usage":{"input_tokens":5,"output_tokens":1}}""";

    static final SystemOneRequest NOUL_REQUEST = SystemOneRequest.of("I was charged twice.",
            Map.of("q", Question.noul("Is this about billing?")));

    /** Deterministic time: only sleeping advances the clock. */
    static final class FakeTime {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final List<Duration> sleeps = new CopyOnWriteArrayList<>();
        volatile Duration oversleep = Duration.ZERO;

        JevClient.Seams seams() {
            return new JevClient.Seams(delay -> {
                sleeps.add(delay);
                now.addAndGet(delay.plus(oversleep).toNanos());
            }, () -> 0.0, now::get, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        }
    }

    /** Records every listener callback as a readable line. */
    static final class RecordingListener implements JevListener {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onAttemptStart(Attempt attempt) {
            events.add("start#" + attempt.attempt() + attempt.correlationId().map(id -> "@" + id).orElse(""));
        }

        @Override
        public void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
            events.add("end#" + attempt.attempt() + ":" + statusCode + (failure == null ? "" : ":" + failure.getClass().getSimpleName()));
        }

        @Override
        public void onRetryScheduled(Attempt attempt, Duration delay, JevException cause) {
            events.add("retry#" + attempt.attempt() + ":" + delay.toMillis() + "ms");
        }
    }

    static final class ThrowingListener implements JevListener {
        @Override
        public void onAttemptStart(Attempt attempt) {
            throw new IllegalStateException("start");
        }

        @Override
        public void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
            throw new IllegalStateException("end");
        }

        @Override
        public void onRetryScheduled(Attempt attempt, Duration delay, JevException cause) {
            throw new IllegalStateException("retry");
        }
    }

    MockServer server;
    FakeTime time;
    final List<JevClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() {
        server = MockServer.start();
        time = new FakeTime();
    }

    @AfterEach
    void stopServer() {
        server.close();
        clients.forEach(JevClient::close);
    }

    JevClient.Builder builder() {
        JevClient.Builder builder = ConfigTest.isolated()
                .apiKey("test-key")
                .baseUrl(server.baseUrl())
                .httpVersion(HttpClient.Version.HTTP_1_1);
        builder.seams = time.seams();
        return builder;
    }

    JevClient client(JevClient.Builder builder) {
        JevClient client = builder.build();
        clients.add(client);
        return client;
    }

    JevClient client() {
        return client(builder());
    }

    @Nested
    class Evaluate {
        @Test
        void sendsTheDocumentedRequestAndParsesAnswers() {
            server.enqueue(200, CodecTest.MIXED_RESPONSE, "x-typesafe-request-id", "req-1");
            EvaluationResult result = client().evaluate(CodecTest.MIXED);

            assertThat(result.model()).isEqualTo("jev-1.13.0");
            assertThat(result.requestId()).contains("req-1");
            assertThat(result.noul("is_urgent").isTrue(0.9)).isTrue();
            assertThat(result.choice("department").choice()).isEqualTo("billing");
            assertThat(result.score("frustration").level()).isEqualTo(1);

            MockServer.Recorded request = server.lastRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/systemone");
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
            assertThat(request.header("Content-Type")).isEqualTo("application/json");
            assertThat(request.header("Accept")).isEqualTo("application/json");
            assertThat(request.header("User-Agent")).startsWith("jev-java-sdk/").contains("Java/26");
            assertThat(request.body()).startsWith("{\"state\":\"Help! My payouts")
                    .contains("\"model\":\"jev-latest\"")
                    .contains("\"is_urgent\":{\"type\":\"noul\",\"instructions\":\"Does this convey urgency?\"}");
        }

        @Test
        void modelPrecedenceIsOptionsThenRequestThenClient() {
            server.otherwise(json(200, NOUL_RESPONSE));
            JevClient client = client(builder().defaultModel("client-model"));
            client.evaluate(NOUL_REQUEST);
            assertThat(server.lastRequest().body()).contains("\"model\":\"client-model\"");
            var pinned = new SystemOneRequest(NOUL_REQUEST.state(), NOUL_REQUEST.questions(),
                    java.util.Optional.of("request-model"));
            client.evaluate(pinned);
            assertThat(server.lastRequest().body()).contains("\"model\":\"request-model\"");
            client.evaluate(pinned, RequestOptions.builder().model("options-model").build());
            assertThat(server.lastRequest().body()).contains("\"model\":\"options-model\"");
        }

        @Test
        void convenienceOverload() {
            server.enqueue(200, NOUL_RESPONSE);
            assertThat(client().evaluate("state", Map.of("q", Question.noul("q?"))).noul("q").noul()).isEqualTo(0.9);
        }

        @Test
        void headersFromClientOptionsAndScopedCorrelationId() throws Exception {
            server.enqueue(200, NOUL_RESPONSE);
            JevClient client = client(builder().header("X-Tenant", "acme").header("X-Env", "default")
                    .correlationIdHeader("X-Request-Tag"));
            var options = RequestOptions.builder().header("X-Env", "override").bodyField("debug", true).build();
            ScopedValue.where(JevContext.CORRELATION_ID, "ticket-7").call(() -> client.evaluate(NOUL_REQUEST, options));
            MockServer.Recorded request = server.lastRequest();
            assertThat(request.header("X-Tenant")).isEqualTo("acme");
            assertThat(request.header("X-Env")).isEqualTo("override");
            assertThat(request.header("X-Request-Tag")).isEqualTo("ticket-7");
            assertThat(request.body()).contains("\"debug\":true");
        }

        @Test
        void nullArgumentsAreRejected() {
            JevClient client = client();
            assertThatNullPointerException().isThrownBy(() -> client.evaluate((SystemOneRequest) null, RequestOptions.DEFAULT));
            assertThatNullPointerException().isThrownBy(() -> client.evaluate(NOUL_REQUEST, (RequestOptions) null));
            assertThatNullPointerException().isThrownBy(() -> client.listModels(null));
            assertThatNullPointerException().isThrownBy(() -> client.evaluateAsync(null));
            assertThatNullPointerException().isThrownBy(() -> client.evaluateAsync(NOUL_REQUEST, null));
        }

        @Test
        void customJsonMapperIsUsed() {
            server.enqueue(200, NOUL_RESPONSE);
            JevClient client = client(builder().jsonMapper(JsonMapper.builder().build()));
            assertThat(client.evaluate(NOUL_REQUEST).noul("q").noul()).isEqualTo(0.9);
        }
    }

    @Nested
    class Models {
        @Test
        void listsModelsWithGet() {
            server.enqueue(200, "{\"models\":[{\"name\":\"jev-latest\",\"description\":\"Flagship\",\"release_date\":\"2026-05-01\"}]}");
            List<ModelInfo> models = client().listModels();
            assertThat(models).extracting(ModelInfo::name).containsExactly("jev-latest");
            assertThat(server.lastRequest().method()).isEqualTo("GET");
            assertThat(server.lastRequest().path()).isEqualTo("/v1/models");
            assertThat(server.lastRequest().header("Content-Type")).isNull();
        }
    }

    @Nested
    class Failures {
        @Test
        void authenticationErrorsAreNotRetried() {
            server.enqueue(401, "{\"detail\":\"Invalid API key\"}", "x-typesafe-request-id", "r-401");
            assertThatThrownBy(() -> client().evaluate(NOUL_REQUEST))
                    .isInstanceOfSatisfying(JevAuthenticationException.class, e -> {
                        assertThat(e.status()).isEqualTo(401);
                        assertThat(e.requestId()).contains("r-401");
                        assertThat(e.endpoint()).isEqualTo("POST " + server.baseUrl() + "/v1/systemone");
                        assertThat(e.getMessage()).contains("Invalid API key").doesNotContain("test-key");
                    });
            assertThat(server.requests()).hasSize(1);
            assertThat(time.sleeps).isEmpty();
        }

        @Test
        void redirectsAreNotFollowed() {
            server.enqueue(302, "", "Location", "https://evil.example/steal");
            assertThatThrownBy(() -> client().evaluate(NOUL_REQUEST))
                    .isExactlyInstanceOf(JevApiException.class)
                    .extracting(e -> ((JevApiException) e).status()).isEqualTo(302);
        }

        @Test
        void invalidSuccessfulResponsesAreNotRetried() {
            server.otherwise(json(200, "{\"model\":\"m\",\"answers\":{}}"));
            var listener = new RecordingListener();
            assertThatThrownBy(() -> client(builder().listener(listener)).evaluate(NOUL_REQUEST))
                    .isInstanceOfSatisfying(JevResponseValidationException.class,
                            e -> assertThat(e.fieldPath()).isEqualTo("answers.q"));
            assertThat(server.requests()).hasSize(1);
            assertThat(listener.events).containsExactly("start#1", "end#1:200:JevResponseValidationException");
        }

        @Test
        void connectionRefused() {
            String deadUrl = server.baseUrl();
            server.close();
            JevClient client = client(builder().baseUrl(deadUrl)
                    .retryPolicy(RetryPolicy.builder().maxRetries(1).build()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST))
                    .isExactlyInstanceOf(JevConnectionException.class)
                    .hasMessageContaining("connection failure for POST " + deadUrl);
            assertThat(time.sleeps).containsExactly(Duration.ofMillis(500));
        }

        @Test
        void droppedConnection() {
            server.otherwise(MockServer.drop());
            JevClient client = client(builder().retryPolicy(RetryPolicy.disabled()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST)).isExactlyInstanceOf(JevConnectionException.class);
        }

        @Test
        void slowResponsesTimeOut() {
            server.otherwise(server.hang());
            JevClient client = client(builder().requestTimeout(Duration.ofMillis(200))
                    .retryPolicy(RetryPolicy.builder().retryOnTimeout(false).build()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST))
                    .isInstanceOfSatisfying(JevTimeoutException.class, e -> {
                        assertThat(e.timeout()).isEqualTo(Duration.ofMillis(200));
                        assertThat(e).hasMessageStartingWith("request timed out after 200 ms");
                    });
            assertThat(server.requests()).hasSize(1);
        }

        @Test
        void perCallTimeoutOverridesClientTimeout() {
            server.otherwise(server.hang());
            var options = RequestOptions.builder().timeout(Duration.ofMillis(100))
                    .retryPolicy(RetryPolicy.disabled()).build();
            assertThatThrownBy(() -> client().evaluate(NOUL_REQUEST, options))
                    .isInstanceOfSatisfying(JevTimeoutException.class,
                            e -> assertThat(e.timeout()).isEqualTo(Duration.ofMillis(100)));
        }
    }

    @Nested
    class Retries {
        @Test
        void retriesWithExponentialBackoffUntilExhausted() {
            server.otherwise(json(503, "{\"detail\":\"unavailable\"}"));
            var listener = new RecordingListener();
            assertThatThrownBy(() -> client(builder().listener(listener)).evaluate(NOUL_REQUEST))
                    .isInstanceOf(JevServerException.class);
            assertThat(server.requests()).hasSize(3);
            assertThat(time.sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
            assertThat(listener.events).containsExactly(
                    "start#1", "end#1:503:JevServerException", "retry#1:500ms",
                    "start#2", "end#2:503:JevServerException", "retry#2:1000ms",
                    "start#3", "end#3:503:JevServerException");
        }

        @Test
        void recoversAfterTransientFailures() {
            server.enqueue(529, "{\"detail\":\"Overloaded\"}").enqueue(200, NOUL_RESPONSE);
            assertThat(client().evaluate(NOUL_REQUEST).noul("q").noul()).isEqualTo(0.9);
            assertThat(server.requests()).hasSize(2);
        }

        @Test
        void honorsRetryAfter() {
            server.enqueue(429, "{}", "retry-after-ms", "1200").enqueue(200, NOUL_RESPONSE);
            client().evaluate(NOUL_REQUEST);
            assertThat(time.sleeps).containsExactly(Duration.ofMillis(1200));
        }

        @Test
        void stopsWhenTheNextDelayWouldExceedTheBudget() {
            server.otherwise(json(500, ""));
            JevClient client = client(builder().retryPolicy(
                    RetryPolicy.builder().maxRetries(10).totalTimeout(Duration.ofSeconds(1)).build()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST)).isInstanceOf(JevServerException.class);
            assertThat(time.sleeps).containsExactly(Duration.ofMillis(500));
            assertThat(server.requests()).hasSize(2);
        }

        @Test
        void aDelayThatExactlyConsumesTheBudgetIsNotAttempted() {
            server.enqueue(503, "", "retry-after-ms", "1000").enqueue(200, NOUL_RESPONSE);
            JevClient client = client(builder().retryPolicy(
                    RetryPolicy.builder().totalTimeout(Duration.ofSeconds(1)).build()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST)).isInstanceOf(JevServerException.class);
            assertThat(server.requests()).hasSize(1);
        }

        @Test
        void listenersReceiveTheMeasuredLatencyOfEachAttempt() {
            server.enqueue(503, "").enqueue(200, NOUL_RESPONSE);
            var latencies = new CopyOnWriteArrayList<Duration>();
            JevClient.Builder builder = builder().listener(new JevListener() {
                @Override
                public void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
                    latencies.add(latency);
                }
            });
            var ticks = new AtomicLong();
            builder.seams = new JevClient.Seams(delay -> { }, () -> 0.0, () -> ticks.addAndGet(5_000_000),
                    Clock.systemUTC());
            client(builder).evaluate(NOUL_REQUEST);
            assertThat(latencies).containsExactly(Duration.ofMillis(5), Duration.ofMillis(5));
        }

        @Test
        void attemptTimeoutIsCappedByTheRemainingBudget() {
            server.otherwise(server.hang());
            JevClient client = client(builder().requestTimeout(Duration.ofSeconds(10))
                    .retryPolicy(RetryPolicy.builder().totalTimeout(Duration.ofMillis(300)).build()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST))
                    .isInstanceOfSatisfying(JevTimeoutException.class,
                            e -> assertThat(e.timeout()).isEqualTo(Duration.ofMillis(300)));
            assertThat(time.sleeps).isEmpty();
        }

        @Test
        void exhaustedBudgetStillMakesAMinimalAttempt() {
            server.enqueue(500, "").otherwise(server.hang());
            time.oversleep = Duration.ofSeconds(5);
            JevClient client = client(builder().retryPolicy(
                    RetryPolicy.builder().totalTimeout(Duration.ofSeconds(2)).build()));
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST))
                    .isInstanceOfSatisfying(JevTimeoutException.class,
                            e -> assertThat(e.timeout()).isEqualTo(Duration.ofMillis(1)));
            assertThat(time.sleeps).hasSize(1);
        }

        @Test
        void unlimitedBudgetUsesThePerAttemptTimeout() {
            server.enqueue(500, "").enqueue(200, NOUL_RESPONSE);
            JevClient client = client(builder().retryPolicy(RetryPolicy.builder().totalTimeout(null).build()));
            client.evaluate(NOUL_REQUEST);
            assertThat(server.requests()).hasSize(2);
        }

        @Test
        void perCallPolicyOverridesClientPolicy() {
            server.otherwise(json(500, ""));
            var options = RequestOptions.builder().retryPolicy(RetryPolicy.disabled()).build();
            assertThatThrownBy(() -> client().evaluate(NOUL_REQUEST, options)).isInstanceOf(JevServerException.class);
            assertThat(server.requests()).hasSize(1);
        }

        @Test
        void listenerFailuresNeverBreakCalls() {
            server.enqueue(500, "").enqueue(200, NOUL_RESPONSE);
            var recording = new RecordingListener();
            JevClient client = client(builder().listener(new ThrowingListener()).listener(recording));
            assertThat(client.evaluate(NOUL_REQUEST).noul("q").noul()).isEqualTo(0.9);
            assertThat(recording.events).containsExactly("start#1", "end#1:500:JevServerException",
                    "retry#1:500ms", "start#2", "end#2:200");
            assertThatNullPointerException().isThrownBy(() -> builder().listener(null));
        }

        @Test
        void realSleeperAndRandomSourceWork() {
            server.enqueue(500, "").enqueue(200, NOUL_RESPONSE);
            JevClient.Builder builder = builder().retryPolicy(RetryPolicy.builder()
                    .initialBackoff(Duration.ofMillis(1)).maxBackoff(Duration.ofMillis(2)).build());
            builder.seams = JevClient.Seams.SYSTEM;
            assertThat(client(builder).evaluate(NOUL_REQUEST).noul("q").noul()).isEqualTo(0.9);
        }
    }

    @Nested
    class Interruption {
        @Test
        void interruptDuringBackoffSleep() {
            server.otherwise(json(503, ""));
            JevClient.Builder builder = builder();
            builder.seams = new JevClient.Seams(delay -> {
                throw new InterruptedException("stop");
            }, () -> 0.0, System::nanoTime, Clock.systemUTC());
            JevClient client = client(builder);
            try {
                assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST))
                        .isInstanceOfSatisfying(JevInterruptedException.class, e -> {
                            assertThat(e).hasMessageStartingWith("interrupted while waiting to retry");
                            assertThat(e.getSuppressed()).singleElement().isInstanceOf(JevServerException.class);
                        });
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        void interruptDuringHttpExchange() throws Exception {
            server.otherwise(server.hang());
            JevClient client = client();
            var failure = new AtomicReference<Throwable>();
            var interruptedFlag = new AtomicReference<Boolean>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    client.evaluate(NOUL_REQUEST);
                } catch (Throwable t) {
                    failure.set(t);
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            await().until(() -> server.requests().size() == 1);
            caller.interrupt();
            caller.join(Duration.ofSeconds(10));
            assertThat(failure.get()).isInstanceOf(JevInterruptedException.class)
                    .hasMessageStartingWith("interrupted during POST");
            assertThat(interruptedFlag.get()).isTrue();
        }

        @Test
        void interruptWhileWaitingForAConcurrencySlot() throws Exception {
            server.otherwise(server.hang());
            JevClient client = client(builder().maxConcurrentRequests(1));
            Thread.ofVirtual().start(() -> {
                try {
                    client.evaluate(NOUL_REQUEST);
                } catch (JevException ignored) {
                    // released when the server closes
                }
            });
            await().until(() -> server.requests().size() == 1);
            var failure = new AtomicReference<Throwable>();
            var interruptedFlag = new AtomicReference<Boolean>();
            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    client.evaluate(NOUL_REQUEST);
                } catch (Throwable t) {
                    failure.set(t);
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            await().until(() -> waiter.getState() == Thread.State.TIMED_WAITING || waiter.getState() == Thread.State.WAITING);
            waiter.interrupt();
            waiter.join(Duration.ofSeconds(10));
            assertThat(failure.get()).isInstanceOf(JevInterruptedException.class)
                    .hasMessageContaining("concurrency slot");
            assertThat(interruptedFlag.get()).isTrue();
        }
    }

    @Nested
    class Bulkhead {
        @Test
        void limitsInFlightRequests() throws Exception {
            server.otherwise(server.delayed(Duration.ofMillis(100), json(200, NOUL_RESPONSE)));
            JevClient client = client(builder().maxConcurrentRequests(2));
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<EvaluationResult>>();
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> client.evaluate(NOUL_REQUEST)));
                }
                for (var future : futures) {
                    assertThat(future.get().noul("q").noul()).isEqualTo(0.9);
                }
            }
            assertThat(server.maxInFlight()).isEqualTo(2);
        }

        @Test
        void waitingForASlotTimesOut() {
            server.otherwise(server.hang());
            JevClient client = client(builder().maxConcurrentRequests(1).requestTimeout(Duration.ofMillis(300)));
            Thread.ofVirtual().start(() -> {
                try {
                    client.evaluate(NOUL_REQUEST, RequestOptions.builder().timeout(Duration.ofSeconds(20)).build());
                } catch (JevException ignored) {
                    // released when the server closes
                }
            });
            await().until(() -> server.requests().size() == 1);
            var options = RequestOptions.builder().retryPolicy(RetryPolicy.disabled()).build();
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST, options))
                    .isInstanceOf(JevTimeoutException.class)
                    .hasMessageContaining("waiting for one of 1 concurrency slots");
        }
    }

    @Nested
    class Async {
        @Test
        void completesOnAVirtualThread() throws Exception {
            server.enqueue(200, NOUL_RESPONSE);
            assertThat(client().evaluateAsync(NOUL_REQUEST).get(10, TimeUnit.SECONDS).noul("q").noul()).isEqualTo(0.9);
        }

        @Test
        void failuresCompleteExceptionally() {
            server.enqueue(401, "{}");
            var future = client().evaluateAsync(NOUL_REQUEST);
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(JevAuthenticationException.class);
        }

        @Test
        void carriesTheCorrelationIdAcrossThreads() throws Exception {
            server.enqueue(200, NOUL_RESPONSE);
            var listener = new RecordingListener();
            JevClient client = client(builder().listener(listener));
            EvaluationResult result = ScopedValue.where(JevContext.CORRELATION_ID, "async-1")
                    .call(() -> client.evaluateAsync(NOUL_REQUEST).get(10, TimeUnit.SECONDS));
            assertThat(result.noul("q").noul()).isEqualTo(0.9);
            assertThat(server.lastRequest().header("X-Correlation-Id")).isEqualTo("async-1");
            assertThat(listener.events).contains("start#1@async-1");
        }

        @Test
        void cancellationInterruptsTheCall() {
            server.otherwise(server.hang());
            var listener = new RecordingListener();
            var future = client(builder().listener(listener)).evaluateAsync(NOUL_REQUEST);
            await().until(() -> server.requests().size() == 1);
            assertThat(future.cancel(true)).isTrue();
            assertThatThrownBy(future::join).isInstanceOf(CancellationException.class);
            await().until(() -> listener.events.contains("end#1:-1:JevInterruptedException"));
        }

        @Test
        void cancellingACompletedFutureIsANoOp() throws Exception {
            server.enqueue(200, NOUL_RESPONSE);
            var future = client().evaluateAsync(NOUL_REQUEST);
            future.get(10, TimeUnit.SECONDS);
            assertThat(future.cancel(true)).isFalse();
            assertThat(future.isCancelled()).isFalse();
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void closeIsIdempotentAndRejectsFurtherCalls() {
            JevClient client = builder().build();
            client.close();
            client.close();
            assertThatThrownBy(() -> client.evaluate(NOUL_REQUEST)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("JevClient is closed");
            assertThatThrownBy(() -> client.evaluateAsync(NOUL_REQUEST)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(client::listModels).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void closeTerminatesTheOwnedHttpClientAndExecutor() {
            JevClient client = builder().build();
            HttpClient http = client.httpClient();
            var executor = (java.util.concurrent.ExecutorService) http.executor().orElseThrow();
            client.close();
            assertThat(http.isTerminated()).isTrue();
            assertThat(executor.isTerminated()).isTrue();
        }

        @Test
        void suppliedHttpClientIsNotClosed() {
            server.otherwise(json(200, NOUL_RESPONSE));
            try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                JevClient first = builder().httpClient(http).build();
                first.evaluate(NOUL_REQUEST);
                first.close();
                assertThat(http.isTerminated()).isFalse();
                try (JevClient second = builder().httpClient(http).build()) {
                    assertThat(second.evaluate(NOUL_REQUEST).noul("q").noul()).isEqualTo(0.9);
                }
            }
        }

        @Test
        void httpClientIsExclusiveWithProxyAndTls() throws Exception {
            HttpClient http = HttpClient.newHttpClient();
            assertThatThrownBy(() -> builder().httpClient(http).proxy(ProxySelector.getDefault()).build())
                    .isInstanceOf(JevConfigurationException.class);
            assertThatThrownBy(() -> builder().httpClient(http).sslContext(SSLContext.getDefault()).build())
                    .isInstanceOf(JevConfigurationException.class);
            http.close();
        }

        @Test
        void proxyAndTlsAreApplied() throws Exception {
            try (JevClient client = builder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 9)))
                    .sslContext(SSLContext.getDefault()).build()) {
                assertThat(client.config().apiKey()).isEqualTo("test-key");
            }
        }

        @Test
        void http2AndHttp3PreferencesFallBackOnCleartext() {
            server.otherwise(json(200, NOUL_RESPONSE));
            for (HttpClient.Version version : List.of(HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_3)) {
                try (JevClient client = builder().httpVersion(version).build()) {
                    assertThat(client.evaluate(NOUL_REQUEST).noul("q").noul()).isEqualTo(0.9);
                    assertThat(client.config().httpVersion()).isEqualTo(version);
                }
            }
        }

        @Test
        void createReadsSystemProperties() {
            System.setProperty("typesafe.api-key", "from-system-property");
            try (JevClient client = JevClient.create()) {
                assertThat(client.config().apiKey()).isEqualTo("from-system-property");
            } finally {
                System.clearProperty("typesafe.api-key");
            }
        }
    }
}
