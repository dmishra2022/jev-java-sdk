package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** The API key may not access this resource (403). */
public class JevPermissionDeniedException extends JevApiException {

    private static final long serialVersionUID = 1L;

    public JevPermissionDeniedException(String message, int status, String body, HttpHeaders headers, String endpoint, Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }
}
