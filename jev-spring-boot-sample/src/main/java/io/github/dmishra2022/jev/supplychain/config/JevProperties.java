package io.github.dmishra2022.jev.supplychain.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code jev.*} settings. Anything left unset falls back to the SDK's own resolution ({@code typesafe.*} system
 * properties, {@code TYPESAFE_*} environment variables, then SDK defaults).
 *
 * @param mode {@code auto} uses the real API when an API key is configured and the offline stand-in otherwise
 */
@ConfigurationProperties("jev")
public record JevProperties(
        @DefaultValue("auto") Mode mode,
        String apiKey,
        String baseUrl,
        String model,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration totalTimeout,
        Integer maxRetries,
        Integer maxConcurrentRequests,
        @DefaultValue("30s") Duration healthCacheTtl) {

    public enum Mode { AUTO, LIVE, OFFLINE }

    public boolean offline() {
        return mode == Mode.OFFLINE || (mode == Mode.AUTO && (apiKey == null || apiKey.isBlank()));
    }
}
