package io.github.dmishra2022.jev.supplychain.config;

import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.JevListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Publishes SDK attempts as {@code jev.client.attempts} (timer, tagged by path, HTTP status and outcome) and
 * retries as {@code jev.client.retries}. Visible at {@code /actuator/metrics} and in any Micrometer backend.
 */
@Component
public class MicrometerJevListener implements JevListener {

    private final MeterRegistry registry;

    public MicrometerJevListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
        Timer.builder("jev.client.attempts")
                .description("Jev API attempts, including retries")
                .tag("path", path(attempt.endpoint()))
                .tag("status", statusCode < 0 ? "IO_ERROR" : String.valueOf(statusCode))
                .tag("outcome", failure == null ? "SUCCESS" : failure.getClass().getSimpleName())
                .register(registry)
                .record(latency);
    }

    @Override
    public void onRetryScheduled(Attempt attempt, Duration delay, JevException cause) {
        Counter.builder("jev.client.retries")
                .tag("path", path(attempt.endpoint()))
                .tag("cause", cause.getClass().getSimpleName())
                .register(registry)
                .increment();
    }

    /** {@code "POST https://host/v1/systemone"} becomes {@code /v1/systemone} (bounded tag cardinality). */
    static String path(String endpoint) {
        int space = endpoint.indexOf(' ');
        return URI.create(endpoint.substring(space + 1)).getPath();
    }
}
