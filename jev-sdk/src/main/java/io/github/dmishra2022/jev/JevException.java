package io.github.dmishra2022.jev;

/** Base class of every failure raised by the SDK. Unchecked, like the rest of the JDK HTTP stack's wrappers. */
public class JevException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JevException(String message) {
        super(message);
    }

    public JevException(String message, Throwable cause) {
        super(message, cause);
    }
}
