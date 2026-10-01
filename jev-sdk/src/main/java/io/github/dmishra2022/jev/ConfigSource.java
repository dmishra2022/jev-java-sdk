package io.github.dmishra2022.jev;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks settings up in a {@link Properties} object, then Java system properties, then environment variables.
 * Blank values are ignored.
 */
final class ConfigSource {

    /** A configurable setting with its property key ({@code typesafe.*}) and env var ({@code TYPESAFE_*}). */
    enum Setting {
        API_KEY("api-key"),
        BASE_URL("base-url"),
        DEFAULT_MODEL("default-model"),
        CONNECT_TIMEOUT("connect-timeout"),
        REQUEST_TIMEOUT("request-timeout"),
        MAX_RETRIES("max-retries"),
        TOTAL_TIMEOUT("total-timeout"),
        MAX_CONCURRENT_REQUESTS("max-concurrent-requests"),
        HTTP_VERSION("http-version");

        final String propertyKey;
        final String envVar;

        Setting(String suffix) {
            this.propertyKey = "typesafe." + suffix;
            this.envVar = "TYPESAFE_" + name();
        }
    }

    /** A raw value and where it came from, for error messages. */
    record Value(String raw, String origin) {
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)?");

    private final Properties properties;
    private final Function<String, String> systemProperties;
    private final Function<String, String> environment;

    ConfigSource(Properties properties, Function<String, String> systemProperties,
                 Function<String, String> environment) {
        this.properties = properties;
        this.systemProperties = systemProperties;
        this.environment = environment;
    }

    Optional<Value> lookup(Setting setting) {
        String value = properties.getProperty(setting.propertyKey);
        if (isSet(value)) {
            return Optional.of(new Value(value.strip(), "property " + setting.propertyKey));
        }
        value = systemProperties.apply(setting.propertyKey);
        if (isSet(value)) {
            return Optional.of(new Value(value.strip(), "system property " + setting.propertyKey));
        }
        value = environment.apply(setting.envVar);
        if (isSet(value)) {
            return Optional.of(new Value(value.strip(), "environment variable " + setting.envVar));
        }
        return Optional.empty();
    }

    Optional<String> string(Setting setting) {
        return lookup(setting).map(Value::raw);
    }

    Optional<Duration> duration(Setting setting) {
        return lookup(setting).map(ConfigSource::parseDuration);
    }

    Optional<Integer> integer(Setting setting) {
        return lookup(setting).map(ConfigSource::parseInteger);
    }

    Optional<HttpClient.Version> httpVersion(Setting setting) {
        return lookup(setting).map(ConfigSource::parseHttpVersion);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Accepts {@code 500ms}, {@code 10s}, {@code 2m}, {@code 1h}, plain seconds ({@code 2.5}) and ISO-8601. */
    static Duration parseDuration(Value value) {
        String text = value.raw().toLowerCase(Locale.ROOT);
        try {
            if (text.startsWith("p")) {
                return Duration.parse(text.toUpperCase(Locale.ROOT));
            }
            Matcher matcher = DURATION.matcher(text);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("unrecognized duration");
            }
            String unit = matcher.group(2) == null ? "s" : matcher.group(2);
            long nanosPerUnit = switch (unit) {
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                default -> 3_600_000_000_000L;
            };
            BigDecimal nanos = new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(nanosPerUnit));
            return Duration.ofNanos(nanos.toBigInteger().longValueExact());
        } catch (RuntimeException e) {
            throw new JevConfigurationException("invalid duration '" + value.raw() + "' in " + value.origin()
                    + "; use e.g. 500ms, 10s, 2m, 1h, 2.5 (seconds) or PT10S", e);
        }
    }

    static int parseInteger(Value value) {
        try {
            return Integer.parseInt(value.raw());
        } catch (NumberFormatException e) {
            throw new JevConfigurationException("invalid integer '" + value.raw() + "' in " + value.origin(), e);
        }
    }

    /** Accepts {@code HTTP_1_1}/{@code 1.1}, {@code HTTP_2}/{@code 2}, {@code HTTP_3}/{@code 3}. */
    static HttpClient.Version parseHttpVersion(Value value) {
        String normalized = value.raw().toUpperCase(Locale.ROOT)
                .replace("HTTP/", "").replace("HTTP_", "").replace('.', '_');
        return switch (normalized) {
            case "1_1" -> HttpClient.Version.HTTP_1_1;
            case "2" -> HttpClient.Version.HTTP_2;
            case "3" -> HttpClient.Version.HTTP_3;
            default -> throw new JevConfigurationException("invalid HTTP version '" + value.raw() + "' in "
                    + value.origin() + "; use HTTP_1_1, HTTP_2 or HTTP_3");
        };
    }
}
