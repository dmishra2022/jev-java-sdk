package io.github.dmishra2022.jev;

/** The client could not be configured: missing API key, malformed base URL or setting, and so on. */
public class JevConfigurationException extends JevException {

    private static final long serialVersionUID = 1L;

    public JevConfigurationException(String message) {
        super(message);
    }

    public JevConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
