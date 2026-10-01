package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** The server failed to process the request (5xx), including {@code 529 Overloaded}. */
public class JevServerException extends JevApiException {

    private static final long serialVersionUID = 1L;

    /** TypeSafe's "temporarily overloaded" status. */
    public static final int OVERLOADED = 529;

    public JevServerException(String message, int status, String body, HttpHeaders headers, String endpoint,
                              Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }

    /** Whether the server reported {@code 529 Overloaded}. */
    public boolean isOverloaded() {
        return status() == OVERLOADED;
    }
}
