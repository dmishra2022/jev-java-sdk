package io.github.dmishra2022.jev;

/** A request failed without an HTTP response (DNS, TLS, refused or reset connection, ...). */
public class JevConnectionException extends JevException {

    private static final long serialVersionUID = 1L;

    private final String endpoint;

    public JevConnectionException(String message, String endpoint, Throwable cause) {
        super(message, cause);
        this.endpoint = endpoint;
    }

    /** The request method and URL, without credentials. */
    public String endpoint() {
        return endpoint;
    }
}
