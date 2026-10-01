package io.github.dmishra2022.jev.supplychain.config;

import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.RetryPolicy;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration(proxyBeanMethods = false)
class JevConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(JevConfiguration.class);

    /** {@code supplychain.business-date} pins "today", for replaying past notices or deterministic tests. */
    @Bean
    Clock clock(@Value("${supplychain.business-date:}") String businessDate,
                @Value("${supplychain.zone:UTC}") ZoneId zone) {
        if (businessDate.isBlank()) {
            return Clock.system(zone);
        }
        return Clock.fixed(LocalDate.parse(businessDate).atStartOfDay(zone).toInstant(), zone);
    }

    @Bean(destroyMethod = "close")
    @Conditional(OfflineMode.class)
    OfflineTypeSafeServer offlineTypeSafeServer() throws IOException {
        var server = new OfflineTypeSafeServer();
        LOG.warn("No Jev API key configured: using the offline heuristic stand-in at {}. "
                + "Set TYPESAFE_API_KEY to use the real model.", server.baseUri());
        return server;
    }

    @Bean(destroyMethod = "close")
    JevClient jevClient(JevProperties properties, ObjectProvider<OfflineTypeSafeServer> offline,
                        MicrometerJevListener metrics) {
        JevClient.Builder builder = JevClient.builder().listener(metrics);
        OfflineTypeSafeServer stub = offline.getIfAvailable();
        if (stub != null) {
            builder.apiKey("offline").baseUrl(stub.baseUri().toString()).defaultModel(OfflineTypeSafeServer.MODEL);
        } else {
            if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                builder.apiKey(properties.apiKey());
            }
            if (properties.baseUrl() != null) {
                builder.baseUrl(properties.baseUrl());
            }
            if (properties.model() != null) {
                builder.defaultModel(properties.model());
            }
        }
        if (properties.connectTimeout() != null) {
            builder.connectTimeout(properties.connectTimeout());
        }
        if (properties.requestTimeout() != null) {
            builder.requestTimeout(properties.requestTimeout());
        }
        if (properties.maxConcurrentRequests() != null) {
            builder.maxConcurrentRequests(properties.maxConcurrentRequests());
        }
        if (properties.maxRetries() != null || properties.totalTimeout() != null) {
            var policy = RetryPolicy.builder();
            if (properties.maxRetries() != null) {
                policy.maxRetries(properties.maxRetries());
            }
            if (properties.totalTimeout() != null) {
                policy.totalTimeout(properties.totalTimeout());
            }
            builder.retryPolicy(policy.build());
        }
        JevClient client = builder.build();
        LOG.info("Jev client ready: {}", client.config());
        return client;
    }

    /** Matches when {@code jev.mode} is {@code offline}, or {@code auto} without an API key. */
    static final class OfflineMode implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            String mode = env.getProperty("jev.mode", "auto").trim().toLowerCase(java.util.Locale.ROOT);
            String key = env.getProperty("jev.api-key", "");
            return mode.equals("offline") || (mode.equals("auto") && key.isBlank());
        }
    }
}
