package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dmishra2022.jev.ConfigSource.Setting;
import io.github.dmishra2022.jev.ConfigSource.Value;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    /** A builder isolated from the machine's real environment and system properties. */
    static JevClient.Builder isolated(Map<String, String> env, Map<String, String> sysprops) {
        var builder = JevClient.builder();
        builder.environment = env::get;
        builder.systemProperties = sysprops::get;
        return builder;
    }

    static JevClient.Builder isolated() {
        return isolated(Map.of(), Map.of());
    }

    static Properties props(String... keyValues) {
        var properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return properties;
    }

    @Nested
    class Precedence {
        @Test
        void defaultsApplyWhenOnlyTheKeyIsGiven() {
            JevConfig config = isolated().apiKey("ts-test-key").resolveConfig();
            assertThat(config.apiKey()).isEqualTo("ts-test-key");
            assertThat(config.baseUri()).isEqualTo(URI.create("https://api.typesafe.ai"));
            assertThat(config.defaultModel()).isEqualTo("jev-latest");
            assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(config.retryPolicy()).isEqualTo(RetryPolicy.defaults());
            assertThat(config.maxConcurrentRequests()).isEqualTo(64);
            assertThat(config.httpVersion()).isEqualTo(HttpClient.Version.HTTP_2);
            assertThat(config.defaultHeaders()).isEmpty();
            assertThat(config.correlationIdHeader()).isEqualTo("X-Correlation-Id");
        }

        @Test
        void environmentVariablesAreRead() {
            var env = new HashMap<String, String>();
            env.put("TYPESAFE_API_KEY", "  env-key  ");
            env.put("TYPESAFE_BASE_URL", "http://localhost:8080/");
            env.put("TYPESAFE_DEFAULT_MODEL", "jev-preview");
            env.put("TYPESAFE_CONNECT_TIMEOUT", "2s");
            env.put("TYPESAFE_REQUEST_TIMEOUT", "1500ms");
            env.put("TYPESAFE_MAX_RETRIES", "5");
            env.put("TYPESAFE_TOTAL_TIMEOUT", "1m");
            env.put("TYPESAFE_MAX_CONCURRENT_REQUESTS", "8");
            env.put("TYPESAFE_HTTP_VERSION", "HTTP/1.1");
            JevConfig config = isolated(env, Map.of()).resolveConfig();
            assertThat(config.apiKey()).isEqualTo("env-key");
            assertThat(config.baseUri()).isEqualTo(URI.create("http://localhost:8080"));
            assertThat(config.defaultModel()).isEqualTo("jev-preview");
            assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(config.requestTimeout()).isEqualTo(Duration.ofMillis(1500));
            assertThat(config.retryPolicy().maxRetries()).isEqualTo(5);
            assertThat(config.retryPolicy().totalTimeout()).contains(Duration.ofMinutes(1));
            assertThat(config.maxConcurrentRequests()).isEqualTo(8);
            assertThat(config.httpVersion()).isEqualTo(HttpClient.Version.HTTP_1_1);
        }

        @Test
        void propertiesBeatSystemPropertiesBeatEnvironment() {
            var env = Map.of("TYPESAFE_API_KEY", "env", "TYPESAFE_DEFAULT_MODEL", "env-model",
                    "TYPESAFE_HTTP_VERSION", "3");
            var sys = Map.of("typesafe.api-key", "sys", "typesafe.default-model", "sys-model");
            JevConfig config = isolated(env, sys).properties(props("typesafe.api-key", "file")).resolveConfig();
            assertThat(config.apiKey()).isEqualTo("file");
            assertThat(config.defaultModel()).isEqualTo("sys-model");
            assertThat(config.httpVersion()).isEqualTo(HttpClient.Version.HTTP_3);
        }

        @Test
        void explicitValuesBeatEverything() {
            var env = Map.of("TYPESAFE_API_KEY", "env", "TYPESAFE_MAX_RETRIES", "9",
                    "TYPESAFE_MAX_CONCURRENT_REQUESTS", "3", "TYPESAFE_HTTP_VERSION", "3",
                    "TYPESAFE_CONNECT_TIMEOUT", "9s", "TYPESAFE_REQUEST_TIMEOUT", "9s",
                    "TYPESAFE_BASE_URL", "https://ignored.example", "TYPESAFE_DEFAULT_MODEL", "ignored");
            JevConfig config = isolated(env, Map.of())
                    .apiKey("explicit")
                    .baseUrl("https://proxy.example.com/typesafe")
                    .defaultModel("jev-1.13.0")
                    .connectTimeout(Duration.ofSeconds(1))
                    .requestTimeout(Duration.ofSeconds(2))
                    .retryPolicy(RetryPolicy.disabled())
                    .maxConcurrentRequests(1)
                    .httpVersion(HttpClient.Version.HTTP_1_1)
                    .header("X-Tenant", "acme")
                    .correlationIdHeader("X-Request-Tag")
                    .resolveConfig();
            assertThat(config.apiKey()).isEqualTo("explicit");
            assertThat(config.resolve("/v1/systemone"))
                    .isEqualTo(URI.create("https://proxy.example.com/typesafe/v1/systemone"));
            assertThat(config.defaultModel()).isEqualTo("jev-1.13.0");
            assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(config.retryPolicy()).isEqualTo(RetryPolicy.disabled());
            assertThat(config.maxConcurrentRequests()).isEqualTo(1);
            assertThat(config.httpVersion()).isEqualTo(HttpClient.Version.HTTP_1_1);
            assertThat(config.defaultHeaders()).containsEntry("X-Tenant", "acme");
            assertThat(config.correlationIdHeader()).isEqualTo("X-Request-Tag");
        }

        @Test
        void blankValuesAreIgnored() {
            JevConfig config = isolated(Map.of("TYPESAFE_API_KEY", "env", "TYPESAFE_DEFAULT_MODEL", " "),
                    Map.of("typesafe.api-key", "  ")).properties(props("typesafe.api-key", "")).resolveConfig();
            assertThat(config.apiKey()).isEqualTo("env");
            assertThat(config.defaultModel()).isEqualTo("jev-latest");
        }

        @Test
        void totalTimeoutCanBeDisabled() {
            JevConfig config = isolated().apiKey("k").properties(props("typesafe.total-timeout", "none"))
                    .resolveConfig();
            assertThat(config.retryPolicy().totalTimeout()).isEmpty();
        }

        @Test
        void sourcesAreTraced() {
            var source = new ConfigSource(props("typesafe.base-url", "p"), Map.of("typesafe.api-key", "s")::get,
                    Map.of("TYPESAFE_DEFAULT_MODEL", "e")::get);
            assertThat(source.lookup(Setting.BASE_URL)).contains(new Value("p", "property typesafe.base-url"));
            assertThat(source.lookup(Setting.API_KEY)).contains(new Value("s", "system property typesafe.api-key"));
            assertThat(source.lookup(Setting.DEFAULT_MODEL))
                    .contains(new Value("e", "environment variable TYPESAFE_DEFAULT_MODEL"));
            assertThat(source.lookup(Setting.HTTP_VERSION)).isEmpty();
            assertThat(Setting.MAX_CONCURRENT_REQUESTS.propertyKey).isEqualTo("typesafe.max-concurrent-requests");
            assertThat(Setting.MAX_CONCURRENT_REQUESTS.envVar).isEqualTo("TYPESAFE_MAX_CONCURRENT_REQUESTS");
        }
    }

    @Nested
    class Parsing {
        @ParameterizedTest
        @CsvSource({"500ms, PT0.5S", "10s, PT10S", "2m, PT2M", "1h, PT1H", "2.5, PT2.5S", "1.5 s, PT1.5S",
                "PT3S, PT3S", "pt4s, PT4S", "0.0000000001s, PT0S"})
        void durations(String raw, Duration expected) {
            assertThat(ConfigSource.parseDuration(new Value(raw, "test"))).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"soon", "-1s", "10 parsecs", "P", "99999999999999999999h"})
        void invalidDurationsNameTheirOrigin(String raw) {
            assertThatThrownBy(() -> ConfigSource.parseDuration(new Value(raw, "property typesafe.x")))
                    .isInstanceOf(JevConfigurationException.class)
                    .hasMessageContaining("invalid duration '" + raw + "' in property typesafe.x");
        }

        @ParameterizedTest
        @CsvSource({"HTTP_1_1, HTTP_1_1", "1.1, HTTP_1_1", "http/2, HTTP_2", "2, HTTP_2", "HTTP_3, HTTP_3", "3, HTTP_3"})
        void httpVersions(String raw, HttpClient.Version expected) {
            assertThat(ConfigSource.parseHttpVersion(new Value(raw, "test"))).isEqualTo(expected);
        }

        @Test
        void invalidValues() {
            assertThatThrownBy(() -> ConfigSource.parseHttpVersion(new Value("4", "env X")))
                    .isInstanceOf(JevConfigurationException.class).hasMessageContaining("invalid HTTP version '4' in env X");
            assertThatThrownBy(() -> ConfigSource.parseInteger(new Value("ten", "env Y")))
                    .isInstanceOf(JevConfigurationException.class).hasMessage("invalid integer 'ten' in env Y");
        }

        @Test
        void invalidSettingsSurfaceAsConfigurationErrors() {
            assertThatThrownBy(() -> isolated().apiKey("k").properties(props("typesafe.max-retries", "-1")).resolveConfig())
                    .isInstanceOf(JevConfigurationException.class).hasMessageContaining("maxRetries");
            assertThatThrownBy(() -> isolated().apiKey("k").maxConcurrentRequests(0).resolveConfig())
                    .isInstanceOf(JevConfigurationException.class).hasMessageContaining("maxConcurrentRequests");
            assertThatThrownBy(() -> isolated().apiKey("k").properties(props("typesafe.request-timeout", "0s"))
                    .resolveConfig()).isInstanceOf(JevConfigurationException.class);
            assertThatNullPointerException().isThrownBy(() -> isolated().properties(null));
        }
    }

    @Nested
    class Validation {
        @Test
        void missingKeyExplainsAllSources() {
            assertThatThrownBy(() -> isolated().resolveConfig()).isInstanceOf(JevConfigurationException.class)
                    .hasMessageContaining("typesafe.api-key").hasMessageContaining("TYPESAFE_API_KEY");
            assertThatThrownBy(() -> isolated().apiKey("   ").resolveConfig())
                    .isInstanceOf(JevConfigurationException.class).hasMessageStartingWith("missing API key");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ab cd", "ab\tcd", "ab\u0001cd", "clé", "ab\u007fcd"})
        void malformedKeysAreRejectedWithoutEchoingThem(String key) {
            assertThatThrownBy(() -> isolated().apiKey(key).resolveConfig())
                    .isInstanceOf(JevConfigurationException.class)
                    .hasMessageStartingWith("invalid API key")
                    .message().doesNotContain(key);
        }

        @Test
        void printableAsciiBoundariesAreAccepted() {
            assertThat(isolated().apiKey("!ts_key~").resolveConfig().apiKey()).isEqualTo("!ts_key~");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ftp://api.typesafe.ai", "api.typesafe.ai", "https://user:pw@api.typesafe.ai",
                "https://api.typesafe.ai?x=1", "https://api.typesafe.ai#f", "https:///path", "http://[bad"})
        void badBaseUrls(String url) {
            assertThatThrownBy(() -> isolated().apiKey("k").baseUrl(url).resolveConfig())
                    .isInstanceOf(JevConfigurationException.class);
        }

        @Test
        void baseUrlIsNormalized() {
            assertThat(JevConfig.parseBaseUrl(" HTTPS://api.typesafe.ai/// ")).isEqualTo(URI.create("HTTPS://api.typesafe.ai"));
            assertThatNullPointerException().isThrownBy(() -> JevConfig.parseBaseUrl(null));
        }

        @Test
        void headersAreValidated() {
            assertThatThrownBy(() -> isolated().apiKey("k").header("Authorization", "x").resolveConfig())
                    .isInstanceOf(JevConfigurationException.class);
            assertThatThrownBy(() -> isolated().apiKey("k").correlationIdHeader("Host").resolveConfig())
                    .isInstanceOf(JevConfigurationException.class);
            assertThatThrownBy(() -> isolated().apiKey("k").correlationIdHeader("bad header").resolveConfig())
                    .isInstanceOf(JevConfigurationException.class);
        }

        @Test
        void toStringRedactsTheKey() {
            JevConfig config = isolated().apiKey("ts_live_0123456789abcdef").resolveConfig();
            assertThat(config.toString()).contains("apiKey=ts_l****").doesNotContain("0123456789abcdef")
                    .contains("baseUri=https://api.typesafe.ai");
            assertThat(JevConfig.redact("short")).isEqualTo("****");
            assertThat(JevConfig.redact("exactly8")).isEqualTo("****");
        }

        @Test
        void recordConstructorValidatesToo() {
            var policy = RetryPolicy.defaults();
            var uri = URI.create("https://api.typesafe.ai");
            assertThatNullPointerException().isThrownBy(() -> new JevConfig("k", null, "m", Duration.ofSeconds(1),
                    Duration.ofSeconds(1), policy, 1, HttpClient.Version.HTTP_2, Map.of(), "X-C"));
            assertThatNullPointerException().isThrownBy(() -> new JevConfig("k", uri, "m", Duration.ofSeconds(1),
                    Duration.ofSeconds(1), null, 1, HttpClient.Version.HTTP_2, Map.of(), "X-C"));
            assertThatNullPointerException().isThrownBy(() -> new JevConfig("k", uri, "m", Duration.ofSeconds(1),
                    Duration.ofSeconds(1), policy, 1, null, Map.of(), "X-C"));
            assertThatNullPointerException().isThrownBy(() -> new JevConfig("k", uri, "m", Duration.ofSeconds(1),
                    Duration.ofSeconds(1), policy, 1, HttpClient.Version.HTTP_2, Map.of(), null));
            assertThat(new JevConfig("k", URI.create("https://h/"), "m", Duration.ofSeconds(1), Duration.ofSeconds(1),
                    policy, 1, HttpClient.Version.HTTP_2, Map.of(), "X-C").baseUri()).isEqualTo(URI.create("https://h"));
        }
    }
}
