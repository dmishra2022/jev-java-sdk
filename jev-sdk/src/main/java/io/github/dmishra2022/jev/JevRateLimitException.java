package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** The rate limit was exceeded (429); see {@link #retryAfter()}. */
public class JevRateLimitException extends JevApiException {

    private static final long serialVersionUID = 1L;

    public JevRateLimitException(String message, int status, String body, HttpHeaders headers, String endpoint, Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }
}
