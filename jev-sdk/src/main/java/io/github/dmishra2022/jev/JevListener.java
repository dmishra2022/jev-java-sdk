package io.github.dmishra2022.jev;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Observability hooks, invoked synchronously on the calling thread. Use them to feed metrics or tracing
 * (Micrometer, OpenTelemetry, ...) without the SDK depending on either. Exceptions thrown by a listener are
 * logged and swallowed; they never fail the call.
 */
public interface JevListener {

    /**
     * Identifies one HTTP attempt.
     *
     * @param endpoint      request method and URL
     * @param attempt       1 for the initial attempt, 2 for the first retry, ...
     * @param correlationId the {@link JevContext#CORRELATION_ID} bound when the call started, if any
     */
    record Attempt(String endpoint, int attempt, Optional<String> correlationId) {
        public Attempt {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /** Called before an attempt is sent (after a concurrency slot was acquired). */
    default void onAttemptStart(Attempt attempt) {
    }

    /**
     * Called after an attempt finished.
     *
     * @param statusCode the HTTP status, or {@code -1} when no response was received
     * @param failure    the failure, or {@code null} on success
     */
    default void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
    }

    /** Called when a failed attempt will be retried after {@code delay}. */
    default void onRetryScheduled(Attempt attempt, Duration delay, JevException cause) {
    }
}
