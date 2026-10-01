package io.github.dmishra2022.jev;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The fully resolved, validated client configuration. Build it through {@link JevClient#builder()}; its
 * {@link #toString()} never reveals the API key.
 *
 * @param apiKey                the bearer token
 * @param baseUri               API root without a trailing slash, e.g. {@code https://api.typesafe.ai}
 * @param defaultModel          model used when neither the request nor its options name one
 * @param connectTimeout        TCP/TLS connect timeout of the SDK-managed HTTP client
 * @param requestTimeout        per-attempt timeout (overridable per call)
 * @param retryPolicy           retry behavior (overridable per call)
 * @param maxConcurrentRequests bulkhead: maximum in-flight HTTP attempts across all threads
 * @param httpVersion           preferred HTTP version; {@code HTTP_3} upgrades via Alt-Svc discovery
 * @param defaultHeaders        headers added to every request
 * @param correlationIdHeader   header that carries {@link JevContext#CORRELATION_ID}
 */
public record JevConfig(String apiKey, URI baseUri, String defaultModel, Duration connectTimeout,
                        Duration requestTimeout, RetryPolicy retryPolicy, int maxConcurrentRequests,
                        HttpClient.Version httpVersion, Map<String, String> defaultHeaders,
                        String correlationIdHeader) {

    /** Default API root. */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    /** Default model alias: the latest stable Jev release. */
    public static final String DEFAULT_MODEL = "jev-latest";
    /** Default per-attempt timeout, matching the official SDKs. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Default connect timeout. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Default bulkhead size. */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 64;
    /** Default correlation header. */
    public static final String DEFAULT_CORRELATION_ID_HEADER = "X-Correlation-Id";

    public JevConfig {
        apiKey = validateApiKey(apiKey);
        baseUri = validateBaseUri(baseUri);
        Checks.requireNonBlank(defaultModel, "defaultModel");
        Checks.requirePositive(connectTimeout, "connectTimeout");
        Checks.requirePositive(requestTimeout, "requestTimeout");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must be at least 1 but was " + maxConcurrentRequests);
        }
        Objects.requireNonNull(httpVersion, "httpVersion");
        defaultHeaders = Checks.orderedCopy(defaultHeaders, "defaultHeaders");
        HeaderRules.validateUserHeaders(defaultHeaders);
        HeaderRules.validateUserHeader(Objects.requireNonNull(correlationIdHeader, "correlationIdHeader"), "");
    }

    /** {@code baseUri} joined with {@code path} (which starts with {@code /}). */
    public URI resolve(String path) {
        return URI.create(baseUri + path);
    }

    @Override
    public String toString() {
        return "JevConfig[apiKey=" + redact(apiKey) + ", baseUri=" + baseUri + ", defaultModel=" + defaultModel
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout
                + ", retryPolicy=" + retryPolicy + ", maxConcurrentRequests=" + maxConcurrentRequests
                + ", httpVersion=" + httpVersion + ", defaultHeaders=" + defaultHeaders.keySet()
                + ", correlationIdHeader=" + correlationIdHeader + "]";
    }

    static String redact(String apiKey) {
        return apiKey.length() <= 8 ? "****" : apiKey.substring(0, 4) + "****";
    }

    static String validateApiKey(String raw) {
        String key = raw == null ? "" : raw.strip();
        if (key.isEmpty()) {
            throw new JevConfigurationException("missing API key: call apiKey(...), set the property "
                    + ConfigSource.Setting.API_KEY.propertyKey + " or the environment variable "
                    + ConfigSource.Setting.API_KEY.envVar);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c <= ' ' || c > '~') {
                throw new JevConfigurationException(
                        "invalid API key: it must not contain whitespace, control or non-ASCII characters");
            }
        }
        return key;
    }

    /** Parses and validates a base URL string. */
    static URI parseBaseUrl(String raw) {
        Objects.requireNonNull(raw, "baseUrl");
        try {
            return validateBaseUri(new URI(raw.strip()));
        } catch (URISyntaxException e) {
            throw new JevConfigurationException("invalid base URL '" + raw + "': " + e.getMessage(), e);
        }
    }

    private static URI validateBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new JevConfigurationException("base URL must use http or https: " + uri);
        }
        if (uri.getHost() == null) {
            throw new JevConfigurationException("base URL needs a host: " + uri);
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new JevConfigurationException("base URL must not contain credentials, a query or a fragment");
        }
        String text = uri.toString();
        int end = text.length();
        while (text.charAt(end - 1) == '/') {
            end--;
        }
        return URI.create(text.substring(0, end));
    }
}
