package io.github.dmishra2022.jev;

/**
 * The calling thread was interrupted (for example because a structured scope or a future was cancelled).
 * The thread's interrupt status is restored before this is thrown, and it is never retried.
 */
public class JevInterruptedException extends JevException {

    private static final long serialVersionUID = 1L;

    public JevInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
