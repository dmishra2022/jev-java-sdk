package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** The resource or model was not found (404). */
public class JevNotFoundException extends JevApiException {

    private static final long serialVersionUID = 1L;

    public JevNotFoundException(String message, int status, String body, HttpHeaders headers, String endpoint, Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }
}
