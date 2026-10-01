package io.github.dmishra2022.jev.supplychain.config;

import io.github.dmishra2022.jev.JevApiException;
import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.ModelInfo;
import io.github.dmishra2022.jev.RequestOptions;
import io.github.dmishra2022.jev.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health/jev}: lists models (a cheap, read-only call) and caches the result so frequent probes do
 * not hammer the API. Credentials are checked; the key itself is never exposed.
 */
@Component("jev")
public class JevHealthIndicator implements HealthIndicator {

    private static final RequestOptions PROBE = RequestOptions.builder()
            .timeout(Duration.ofSeconds(3)).retryPolicy(RetryPolicy.disabled()).build();

    private record Snapshot(Health health, Instant at) {
    }

    private final JevClient client;
    private final JevProperties properties;
    private final Clock clock;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public JevHealthIndicator(JevClient client, JevProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Snapshot snapshot = cache.get();
        if (snapshot != null && snapshot.at().plus(properties.healthCacheTtl()).isAfter(now)) {
            return snapshot.health();
        }
        Health health = probe();
        cache.set(new Snapshot(health, now));
        return health;
    }

    private Health probe() {
        Health.Builder builder;
        try {
            List<ModelInfo> models = client.listModels(PROBE);
            builder = Health.up().withDetail("models", models.stream().map(ModelInfo::name).toList());
        } catch (JevApiException e) {
            builder = Health.down().withDetail("status", e.status())
                    .withDetail("requestId", e.requestId().orElse("n/a"));
        } catch (JevException e) {
            builder = Health.down().withDetail("error", e.getClass().getSimpleName());
        }
        return builder.withDetail("mode", properties.offline() ? "offline" : "live")
                .withDetail("baseUrl", client.config().baseUri().toString())
                .withDetail("defaultModel", client.config().defaultModel())
                .build();
    }
}
