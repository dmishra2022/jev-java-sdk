package io.github.dmishra2022.jev.structured;

import io.github.dmishra2022.jev.Answer;
import io.github.dmishra2022.jev.EvaluationResult;
import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.JevInterruptedException;
import io.github.dmishra2022.jev.JevTimeoutException;
import io.github.dmishra2022.jev.Question;
import io.github.dmishra2022.jev.SystemOneRequest;
import io.github.dmishra2022.jev.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Structured-concurrency patterns over a {@link JevClient}, built on JDK 26's {@link StructuredTaskScope}
 * (a preview API: run with {@code --enable-preview}).
 *
 * <p>Every method forks one virtual thread per request inside a scope owned by the calling thread, so:
 * <ul>
 *   <li>no request outlives the call: when the method returns or throws, all subtasks are finished;</li>
 *   <li>failure or cancellation of the owner interrupts every subtask, aborting their HTTP exchanges;</li>
 *   <li>{@link ScopedValue}s such as {@code JevContext.CORRELATION_ID} are inherited by every subtask.</li>
 * </ul>
 * Concurrency is still bounded by the client's {@code maxConcurrentRequests} bulkhead.
 */
public final class StructuredJev {

    private final JevClient client;

    public StructuredJev(JevClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** {@link #evaluateAll(List, Duration)} without a deadline. */
    public List<EvaluationResult> evaluateAll(List<SystemOneRequest> requests) {
        return evaluateAll(requests, null);
    }

    /**
     * Evaluates all requests concurrently and returns their results in request order. Fails fast: the first
     * failure cancels the remaining requests and is rethrown.
     *
     * @param deadline overall deadline, or {@code null} for none
     * @throws JevTimeoutException     the deadline expired first
     * @throws JevInterruptedException the calling thread was interrupted
     */
    public List<EvaluationResult> evaluateAll(List<SystemOneRequest> requests, Duration deadline) {
        List<SystemOneRequest> batch = List.copyOf(Objects.requireNonNull(requests, "requests"));
        return run("jev-evaluate-all", deadline, Joiner.allSuccessfulOrThrow(), scope -> forkAll(scope, batch));
    }

    /**
     * Evaluates all requests concurrently and waits for every one of them (or the deadline). Never fails
     * because of a request: each position holds a {@link Outcome.Success} or {@link Outcome.Failure}; requests
     * still running at the deadline are cancelled and reported as failures with a {@link JevTimeoutException}.
     *
     * @param deadline overall deadline, or {@code null} for none
     * @throws JevInterruptedException the calling thread was interrupted
     */
    public List<Outcome<EvaluationResult>> evaluateAllSettled(List<SystemOneRequest> requests, Duration deadline) {
        List<SystemOneRequest> batch = List.copyOf(Objects.requireNonNull(requests, "requests"));
        return run("jev-evaluate-all-settled", deadline, new SettledJoiner(deadline), scope -> forkAll(scope, batch));
    }

    /**
     * Hedged request for tail latency: sends {@code request}, and if no answer arrived after {@code hedgeAfter},
     * sends an identical second request; the first success wins and the other is cancelled. A non-retryable
     * failure (per the client's retry policy, e.g. 401 or 422) fails immediately without hedging.
     *
     * <p>Each request is billed, so hedge only latency-critical calls.
     */
    public EvaluationResult hedged(SystemOneRequest request, Duration hedgeAfter) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(hedgeAfter, "hedgeAfter");
        if (hedgeAfter.isNegative()) {
            throw new IllegalArgumentException("hedgeAfter must not be negative but was " + hedgeAfter);
        }
        Predicate<Throwable> fatal = failure -> !(failure instanceof JevException jev)
                || !client.config().retryPolicy().shouldRetry(jev);
        return run("jev-hedged", null, new FirstSuccessJoiner(fatal), scope -> {
            scope.fork(() -> client.evaluate(request));
            scope.fork(() -> {
                Thread.sleep(hedgeAfter);
                return client.evaluate(request);
            });
        });
    }

    /**
     * Splits a large question set into requests of at most {@code maxQuestionsPerRequest} questions against the
     * same state, evaluates them concurrently (fail-fast) and merges the answers back in question order. Use it
     * when the questions exceed the model's per-request context budget. Usage is summed and request ids are
     * comma-joined; the state is billed once per request.
     */
    public EvaluationResult evaluateChunked(Object state, Map<String, ? extends Question> questions,
                                            int maxQuestionsPerRequest) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(questions, "questions");
        if (maxQuestionsPerRequest < 1) {
            throw new IllegalArgumentException("maxQuestionsPerRequest must be at least 1 but was " + maxQuestionsPerRequest);
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("at least one question is required");
        }
        var chunks = new ArrayList<SystemOneRequest>();
        var current = new LinkedHashMap<String, Question>();
        for (var entry : questions.entrySet()) {
            current.put(entry.getKey(), entry.getValue());
            if (current.size() == maxQuestionsPerRequest) {
                chunks.add(SystemOneRequest.of(state, current));
                current = new LinkedHashMap<>();
            }
        }
        if (!current.isEmpty()) {
            chunks.add(SystemOneRequest.of(state, current));
        }
        return merge(evaluateAll(chunks));
    }

    static EvaluationResult merge(List<EvaluationResult> parts) {
        var answers = new LinkedHashMap<String, Answer>();
        Usage usage = Usage.EMPTY;
        var requestIds = new StringJoiner(",");
        for (EvaluationResult part : parts) {
            answers.putAll(part.answers());
            usage = usage.plus(part.usage());
            part.requestId().ifPresent(requestIds::add);
        }
        Optional<String> requestId = requestIds.length() == 0 ? Optional.empty() : Optional.of(requestIds.toString());
        return new EvaluationResult(parts.getFirst().model(), answers, usage, requestId);
    }

    // ---------------------------------------------------------------- scope plumbing

    private void forkAll(StructuredTaskScope<EvaluationResult, ?> scope, List<SystemOneRequest> batch) {
        for (SystemOneRequest request : batch) {
            scope.fork(() -> client.evaluate(request));
        }
    }

    private static <R> R run(String name, Duration deadline, Joiner<EvaluationResult, R> joiner,
                             Consumer<StructuredTaskScope<EvaluationResult, R>> forker) {
        if (deadline != null && (deadline.isNegative() || deadline.isZero())) {
            throw new IllegalArgumentException("deadline must be positive but was " + deadline);
        }
        try (var scope = StructuredTaskScope.open(joiner, configure(name, deadline))) {
            forker.accept(scope);
            return scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw propagate(e.getCause());
        } catch (StructuredTaskScope.TimeoutException e) {
            throw new JevTimeoutException(name + " exceeded its deadline of " + deadline.toMillis() + " ms",
                    name, deadline, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevInterruptedException(name + " was interrupted", e);
        }
    }

    private static UnaryOperator<StructuredTaskScope.Configuration> configure(String name, Duration deadline) {
        return configuration -> {
            var named = configuration.withName(name);
            return deadline == null ? named : named.withTimeout(deadline);
        };
    }

    /** Rethrows unchecked failures as they are; wraps checked ones. */
    static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new JevException("structured task failed: " + failure, failure);
    }

    /** Waits for all subtasks; on deadline reports unfinished ones as timeouts instead of throwing. */
    static final class SettledJoiner implements Joiner<EvaluationResult, List<Outcome<EvaluationResult>>> {
        private final Duration deadline;
        private final List<Subtask<EvaluationResult>> subtasks = new ArrayList<>();

        SettledJoiner(Duration deadline) {
            this.deadline = deadline;
        }

        @Override
        public boolean onFork(Subtask<EvaluationResult> subtask) {
            subtasks.add(subtask);
            return false;
        }

        @Override
        public void onTimeout() {
            // Report unfinished subtasks as timeouts from result() rather than failing the whole join.
        }

        @Override
        public List<Outcome<EvaluationResult>> result() {
            var outcomes = new ArrayList<Outcome<EvaluationResult>>(subtasks.size());
            for (Subtask<EvaluationResult> subtask : subtasks) {
                outcomes.add(switch (subtask.state()) {
                    case SUCCESS -> new Outcome.Success<>(subtask.get());
                    case FAILED -> new Outcome.Failure<>(subtask.exception());
                    case UNAVAILABLE -> new Outcome.Failure<>(new JevTimeoutException(
                            "request did not finish within the deadline of " + deadline.toMillis() + " ms",
                            "jev-evaluate-all-settled", deadline, null));
                });
            }
            return List.copyOf(outcomes);
        }
    }

    /** Completes with the first success; stops early on a failure {@code fatal} accepts. */
    static final class FirstSuccessJoiner implements Joiner<EvaluationResult, EvaluationResult> {
        private final Predicate<Throwable> fatal;
        private final AtomicReference<EvaluationResult> success = new AtomicReference<>();
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        FirstSuccessJoiner(Predicate<Throwable> fatal) {
            this.fatal = fatal;
        }

        @Override
        public boolean onComplete(Subtask<EvaluationResult> subtask) {
            if (subtask.state() == Subtask.State.SUCCESS) {
                success.compareAndSet(null, subtask.get());
                return true;
            }
            Throwable failure = subtask.exception();
            firstFailure.compareAndSet(null, failure);
            return fatal.test(failure);
        }

        @Override
        public EvaluationResult result() throws Throwable {
            EvaluationResult value = success.get();
            if (value != null) {
                return value;
            }
            throw firstFailure.get();
        }
    }
}
