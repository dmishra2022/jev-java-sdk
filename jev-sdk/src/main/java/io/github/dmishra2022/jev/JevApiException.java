package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** The server returned an unsuccessful HTTP response. Subclasses identify the common statuses. */
public class JevApiException extends JevException {

    private static final long serialVersionUID = 1L;

    /** Response header carrying TypeSafe's request id. */
    public static final String REQUEST_ID_HEADER = "x-typesafe-request-id";

    private final int status;
    private final String body;
    private final transient HttpHeaders headers;
    private final String endpoint;
    private final transient Duration retryAfter;

    public JevApiException(String message, int status, String body, HttpHeaders headers, String endpoint,
                           Duration retryAfter) {
        super(message);
        this.status = status;
        this.body = Objects.requireNonNull(body, "body");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.retryAfter = retryAfter;
    }

    /** The HTTP status code. */
    public int status() {
        return status;
    }

    /** The raw response body (empty when the server sent none). */
    public String body() {
        return body;
    }

    /** The response headers. */
    public HttpHeaders headers() {
        return headers;
    }

    /** The request method and URL, without credentials. */
    public String endpoint() {
        return endpoint;
    }

    /** The {@code x-typesafe-request-id} response header; quote it when contacting support. */
    public Optional<String> requestId() {
        return headers.firstValue(REQUEST_ID_HEADER);
    }

    /** The server-requested wait from {@code retry-after-ms} / {@code Retry-After}, if any. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
