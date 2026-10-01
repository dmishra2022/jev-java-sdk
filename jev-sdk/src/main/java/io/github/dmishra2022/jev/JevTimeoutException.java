package io.github.dmishra2022.jev;

import java.time.Duration;

/** A request, a wait for a concurrency slot, or a structured fan-out exceeded its timeout. */
public class JevTimeoutException extends JevConnectionException {

    private static final long serialVersionUID = 1L;

    private final transient Duration timeout;

    public JevTimeoutException(String message, String endpoint, Duration timeout, Throwable cause) {
        super(message, endpoint, cause);
        this.timeout = timeout;
    }

    /** The timeout that expired. */
    public Duration timeout() {
        return timeout;
    }
}
