package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** The request was invalid (400). */
public class JevBadRequestException extends JevApiException {

    private static final long serialVersionUID = 1L;

    public JevBadRequestException(String message, int status, String body, HttpHeaders headers, String endpoint, Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }
}
