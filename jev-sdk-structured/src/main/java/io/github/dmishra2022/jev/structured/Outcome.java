package io.github.dmishra2022.jev.structured;

import java.util.Objects;

/**
 * The settled result of one task in {@link StructuredJev#evaluateAllSettled}: either a value or the failure.
 *
 * @param <T> the value type
 */
public sealed interface Outcome<T> permits Outcome.Success, Outcome.Failure {

    /**
     * A task that completed with a value.
     *
     * @param value the value
     * @param <T>   the value type
     */
    record Success<T>(T value) implements Outcome<T> {
    }

    /**
     * A task that failed, or did not finish before the deadline ({@code JevTimeoutException}).
     *
     * @param error the failure
     * @param <T>   the value type
     */
    record Failure<T>(Throwable error) implements Outcome<T> {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }

    /** Whether this is a {@link Success}. */
    default boolean isSuccess() {
        return this instanceof Success;
    }

    /** The value, or the failure rethrown (checked failures wrapped in a {@code JevException}). */
    default T orElseThrow() {
        return switch (this) {
            case Success<T> success -> success.value();
            case Failure<T> failure -> throw StructuredJev.propagate(failure.error());
        };
    }
}
