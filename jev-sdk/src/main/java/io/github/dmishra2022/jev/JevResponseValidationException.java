package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;

/** A successful HTTP response whose body was missing required data or did not match the request. */
public class JevResponseValidationException extends JevApiException {

    private static final long serialVersionUID = 1L;

    private final String fieldPath;

    public JevResponseValidationException(String message, int status, String body, HttpHeaders headers,
                                          String endpoint, String fieldPath) {
        super(message, status, body, headers, endpoint, null);
        this.fieldPath = fieldPath;
    }

    /** Dotted path to the offending field, such as {@code answers.tone.confidence}; {@code $} is the root. */
    public String fieldPath() {
        return fieldPath;
    }
}
