package io.github.dmishra2022.jev;

import java.util.Optional;

/**
 * Call-scoped context carried by {@link ScopedValue}s. Values bound around a call are sent as headers,
 * reported to {@link JevListener}s, propagated to {@link JevClient#evaluateAsync} threads, and inherited by
 * subtasks forked in a {@code StructuredTaskScope}.
 *
 * <pre>{@code
 * ScopedValue.where(JevContext.CORRELATION_ID, ticketId).call(() -> client.evaluate(request));
 * }</pre>
 */
public final class JevContext {

    /** Sent as the configured correlation header (default {@code X-Correlation-Id}). */
    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    private JevContext() {
    }

    /** The bound correlation id, if any. */
    public static Optional<String> correlationId() {
        return CORRELATION_ID.isBound() ? Optional.of(CORRELATION_ID.get()) : Optional.empty();
    }
}
