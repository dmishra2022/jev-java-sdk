package io.github.dmishra2022.jev;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpOption;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thread-safe client for the TypeSafe System One API. Create one per application and share it; it is cheap to
 * call from any number of (virtual) threads.
 *
 * <pre>{@code
 * try (var client = JevClient.builder().build()) {          // reads TYPESAFE_API_KEY
 *     EvaluationResult result = client.evaluate(SystemOneRequest.builder()
 *             .state("Help! My payouts have been failing for 3 days.")
 *             .question("urgent", Question.noul("Does this convey urgency?"))
 *             .question("team", Question.choice("Which team should handle this?", "billing", "technical"))
 *             .build());
 *     if (result.noul("urgent").isTrue(0.8)) { ... }
 * }
 * }</pre>
 *
 * <p>Calls block the calling thread, which is the right model on virtual threads; use
 * {@link #evaluateAsync(SystemOneRequest)} for a {@link CompletableFuture}. Every call is bounded by the
 * {@link JevConfig#maxConcurrentRequests() bulkhead}, retried per {@link RetryPolicy}, and fails with a
 * {@link JevException} subclass.
 */
public final class JevClient implements AutoCloseable {

    static final String EVALUATE_PATH = "/v1/systemone";
    static final String MODELS_PATH = "/v1/models";
    static final String SDK_VERSION = SdkVersion.load();

    private static final System.Logger LOG = System.getLogger(JevClient.class.getName());
    private static final JsonMapper DEFAULT_MAPPER = JsonMapper.builder().build();
    private static final Duration MIN_ATTEMPT_TIMEOUT = Duration.ofMillis(1);
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    /** Sleeps between retries; replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Time, randomness and sleeping, injectable so retry logic can be tested deterministically. */
    record Seams(Sleeper sleeper, DoubleSupplier random, LongSupplier nanoTime, Clock clock) {
        static final Seams SYSTEM = new Seams(Thread::sleep, Math::random, System::nanoTime, Clock.systemUTC());
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(Codec.Raw raw);
    }

    private final JevConfig config;
    private final HttpClient http;
    private final ExecutorService ownedExecutor;
    private final Codec codec;
    private final Semaphore permits;
    private final JevListener listener;
    private final Seams seams;
    private final String userAgent;
    private final ThreadFactory asyncThreads = Thread.ofVirtual().name("jev-async-", 0).factory();
    private final AtomicBoolean closed = new AtomicBoolean();

    private JevClient(Builder builder, JevConfig config) {
        this.config = config;
        this.seams = builder.seams;
        this.codec = new Codec(builder.jsonMapper == null ? DEFAULT_MAPPER : builder.jsonMapper);
        this.permits = new Semaphore(config.maxConcurrentRequests());
        this.listener = new SafeListener(List.copyOf(builder.listeners));
        this.userAgent = "jev-java-sdk/" + SDK_VERSION + " Java/" + Runtime.version().feature();
        if (builder.httpClient != null) {
            this.http = builder.httpClient;
            this.ownedExecutor = null;
        } else {
            this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            var http = HttpClient.newBuilder()
                    .version(config.httpVersion())
                    .connectTimeout(config.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(ownedExecutor);
            if (builder.proxy != null) {
                http.proxy(builder.proxy);
            }
            if (builder.sslContext != null) {
                http.sslContext(builder.sslContext);
            }
            this.http = http.build();
        }
    }

    /** A builder that falls back to {@code typesafe.*} properties and {@code TYPESAFE_*} environment variables. */
    public static Builder builder() {
        return new Builder();
    }

    /** A client configured entirely from system properties / environment variables. */
    public static JevClient create() {
        return builder().build();
    }

    /** The resolved configuration. */
    public JevConfig config() {
        return config;
    }

    HttpClient httpClient() {
        return http;
    }

    /** Evaluates a request with the client defaults. */
    public EvaluationResult evaluate(SystemOneRequest request) {
        return evaluate(request, RequestOptions.DEFAULT);
    }

    /** Evaluates {@code questions} against {@code state} with the client defaults. */
    public EvaluationResult evaluate(Object state, Map<String, ? extends Question> questions) {
        return evaluate(SystemOneRequest.of(state, questions));
    }

    /**
     * Evaluates a request, blocking the calling thread until all answers arrive.
     *
     * @throws JevApiException        the server returned an error after any retries
     * @throws JevConnectionException the server could not be reached, or timed out, after any retries
     * @throws JevInterruptedException the calling thread was interrupted
     * @throws IllegalStateException  the client is closed
     */
    public EvaluationResult evaluate(SystemOneRequest request, RequestOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        String model = options.model().or(request::model).orElse(config.defaultModel());
        byte[] body = codec.encodeEvaluation(request, model, options.extraBody());
        return execute(EVALUATE_PATH, body, options, raw -> codec.decodeEvaluation(request, raw));
    }

    /** {@link #evaluate(SystemOneRequest)} on a new virtual thread. */
    public CompletableFuture<EvaluationResult> evaluateAsync(SystemOneRequest request) {
        return evaluateAsync(request, RequestOptions.DEFAULT);
    }

    /**
     * {@link #evaluate(SystemOneRequest, RequestOptions)} on a new virtual thread. The bound
     * {@link JevContext#CORRELATION_ID} is carried over. Cancelling the returned future interrupts the call,
     * aborting the in-flight HTTP exchange (futures derived from it with {@code thenApply} and friends do not
     * propagate cancellation upstream).
     */
    public CompletableFuture<EvaluationResult> evaluateAsync(SystemOneRequest request, RequestOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(options, "options");
        ensureOpen();
        Optional<String> correlationId = JevContext.correlationId();
        var future = new InterruptibleFuture<EvaluationResult>();
        Thread worker = asyncThreads.newThread(() -> {
            try {
                future.complete(withCorrelationId(correlationId, () -> evaluate(request, options)));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        future.worker = worker;
        worker.start();
        return future;
    }

    /** Lists the models available to the account. */
    public List<ModelInfo> listModels() {
        return listModels(RequestOptions.DEFAULT);
    }

    /** Lists the models available to the account, with per-call overrides (model and body overrides are ignored). */
    public List<ModelInfo> listModels(RequestOptions options) {
        Objects.requireNonNull(options, "options");
        return execute(MODELS_PATH, null, options, codec::decodeModels);
    }

    /**
     * Closes the client. With an SDK-managed HTTP client this waits for in-flight calls and releases its
     * threads; a client passed to {@link Builder#httpClient(HttpClient)} is left open. Idempotent.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownedExecutor != null) {
            http.close();
            ownedExecutor.close();
        }
    }

    // ---------------------------------------------------------------- pipeline

    private <T> T execute(String path, byte[] body, RequestOptions options, Decoder<T> decoder) {
        ensureOpen();
        RetryPolicy policy = options.retryPolicy().orElse(config.retryPolicy());
        Duration perAttempt = options.timeout().orElse(config.requestTimeout());
        URI uri = config.resolve(path);
        String endpoint = (body == null ? "GET " : "POST ") + uri;
        Optional<String> correlationId = JevContext.correlationId();
        long start = seams.nanoTime().getAsLong();
        long deadline = policy.totalTimeout().isPresent() ? start + policy.totalTimeout().get().toNanos() : NO_DEADLINE;
        for (int attempt = 1; ; attempt++) {
            var context = new JevListener.Attempt(endpoint, attempt, correlationId);
            Duration timeout = attemptTimeout(perAttempt, deadline);
            HttpRequest request = buildRequest(uri, body, timeout, options, correlationId);
            try {
                return attempt(context, request, timeout, decoder);
            } catch (JevException failure) {
                Duration delay = retryDelay(policy, attempt, failure, deadline);
                if (delay == null) {
                    throw failure;
                }
                listener.onRetryScheduled(context, delay, failure);
                LOG.log(System.Logger.Level.DEBUG, "Retrying {0} in {1} ms after attempt {2} failed: {3}",
                        endpoint, delay.toMillis(), attempt, failure.getMessage());
                sleepBeforeRetry(delay, failure, endpoint);
            }
        }
    }

    private Duration attemptTimeout(Duration perAttempt, long deadline) {
        if (deadline == NO_DEADLINE) {
            return perAttempt;
        }
        long remaining = deadline - seams.nanoTime().getAsLong();
        long nanos = Math.max(Math.min(perAttempt.toNanos(), remaining), MIN_ATTEMPT_TIMEOUT.toNanos());
        return Duration.ofNanos(nanos);
    }

    private Duration retryDelay(RetryPolicy policy, int attempt, JevException failure, long deadline) {
        if (attempt > policy.maxRetries() || !policy.shouldRetry(failure)) {
            return null;
        }
        Duration delay = policy.delayFor(attempt, failure, seams.random().getAsDouble());
        if (deadline != NO_DEADLINE && delay.toNanos() >= deadline - seams.nanoTime().getAsLong()) {
            return null;
        }
        return delay;
    }

    private void sleepBeforeRetry(Duration delay, JevException failure, String endpoint) {
        try {
            seams.sleeper().sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var interrupted = new JevInterruptedException("interrupted while waiting to retry " + endpoint, e);
            interrupted.addSuppressed(failure);
            throw interrupted;
        }
    }

    private HttpRequest buildRequest(URI uri, byte[] body, Duration timeout, RequestOptions options,
                                     Optional<String> correlationId) {
        var request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
        config.defaultHeaders().forEach(request::setHeader);
        options.extraHeaders().forEach(request::setHeader);
        correlationId.ifPresent(id -> request.setHeader(config.correlationIdHeader(), id));
        if (config.httpVersion() == HttpClient.Version.HTTP_3) {
            request.setOption(HttpOption.H3_DISCOVERY, HttpOption.Http3DiscoveryMode.ALT_SVC);
        }
        if (body == null) {
            return request.GET().build();
        }
        return request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private <T> T attempt(JevListener.Attempt context, HttpRequest request, Duration timeout, Decoder<T> decoder) {
        acquirePermit(context.endpoint(), timeout);
        try {
            listener.onAttemptStart(context);
            long started = seams.nanoTime().getAsLong();
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpTimeoutException e) {
                throw ended(context, -1, started, new JevTimeoutException(
                        "request timed out after " + timeout.toMillis() + " ms: " + context.endpoint(),
                        context.endpoint(), timeout, e));
            } catch (IOException e) {
                throw ended(context, -1, started, new JevConnectionException(
                        "connection failure for " + context.endpoint() + ": " + e, context.endpoint(), e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ended(context, -1, started,
                        new JevInterruptedException("interrupted during " + context.endpoint(), e));
            }
            var raw = new Codec.Raw(response.statusCode(), response.body(), response.headers(), context.endpoint());
            if (raw.status() / 100 != 2) {
                throw ended(context, raw.status(), started, codec.toApiException(raw, seams.clock().instant()));
            }
            T value;
            try {
                value = decoder.decode(raw);
            } catch (JevResponseValidationException e) {
                throw ended(context, raw.status(), started, e);
            }
            listener.onAttemptEnd(context, raw.status(), elapsedSince(started), null);
            return value;
        } finally {
            permits.release();
        }
    }

    private void acquirePermit(String endpoint, Duration timeout) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevInterruptedException("interrupted while waiting for a concurrency slot for " + endpoint, e);
        }
        if (!acquired) {
            throw new JevTimeoutException("timed out after " + timeout.toMillis() + " ms waiting for one of "
                    + config.maxConcurrentRequests() + " concurrency slots: " + endpoint, endpoint, timeout, null);
        }
    }

    private JevException ended(JevListener.Attempt context, int status, long started, JevException failure) {
        listener.onAttemptEnd(context, status, elapsedSince(started), failure);
        return failure;
    }

    private Duration elapsedSince(long started) {
        return Duration.ofNanos(seams.nanoTime().getAsLong() - started);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("JevClient is closed");
        }
    }

    private static <T> T withCorrelationId(Optional<String> correlationId, Supplier<T> action) {
        if (correlationId.isEmpty()) {
            return action.get();
        }
        return ScopedValue.where(JevContext.CORRELATION_ID, correlationId.get()).call(action::get);
    }

    /** A future whose cancellation interrupts the virtual thread running the call. */
    static final class InterruptibleFuture<T> extends CompletableFuture<T> {
        volatile Thread worker;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                worker.interrupt();
            }
            return cancelled;
        }
    }

    /** Fans events out to listeners, isolating the call from listener failures. */
    static final class SafeListener implements JevListener {
        private final List<JevListener> delegates;

        SafeListener(List<JevListener> delegates) {
            this.delegates = delegates;
        }

        @Override
        public void onAttemptStart(Attempt attempt) {
            for (JevListener delegate : delegates) {
                try {
                    delegate.onAttemptStart(attempt);
                } catch (RuntimeException e) {
                    logListenerFailure(e);
                }
            }
        }

        @Override
        public void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
            for (JevListener delegate : delegates) {
                try {
                    delegate.onAttemptEnd(attempt, statusCode, latency, failure);
                } catch (RuntimeException e) {
                    logListenerFailure(e);
                }
            }
        }

        @Override
        public void onRetryScheduled(Attempt attempt, Duration delay, JevException cause) {
            for (JevListener delegate : delegates) {
                try {
                    delegate.onRetryScheduled(attempt, delay, cause);
                } catch (RuntimeException e) {
                    logListenerFailure(e);
                }
            }
        }

        private static void logListenerFailure(RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "JevListener threw; ignoring", e);
        }
    }

    /**
     * Configures a {@link JevClient}. Each setting resolves, in order, from the explicit builder call, the
     * {@link #properties(Properties) properties} ({@code typesafe.api-key}, ...), Java system properties of the
     * same name, environment variables ({@code TYPESAFE_API_KEY}, ...) and finally the SDK default.
     */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private Duration connectTimeout;
        private Duration requestTimeout;
        private RetryPolicy retryPolicy;
        private Integer maxConcurrentRequests;
        private HttpClient.Version httpVersion;
        private String correlationIdHeader;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<JevListener> listeners = new ArrayList<>();
        private HttpClient httpClient;
        private ProxySelector proxy;
        private SSLContext sslContext;
        private JsonMapper jsonMapper;
        private Properties properties = new Properties();

        Function<String, String> environment = System::getenv;
        Function<String, String> systemProperties = System::getProperty;
        Seams seams = Seams.SYSTEM;

        private Builder() {
        }

        /** The API key; falls back to {@code typesafe.api-key} / {@code TYPESAFE_API_KEY}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The API root; falls back to {@code typesafe.base-url} / {@code TYPESAFE_BASE_URL}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** The default model; falls back to {@code typesafe.default-model} / {@code TYPESAFE_DEFAULT_MODEL}. */
        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Per-attempt timeout. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Overrides the retry policy entirely (and the {@code max-retries} / {@code total-timeout} settings). */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        /** Preferred HTTP version (default {@code HTTP_2}); {@code HTTP_3} uses JDK 26's QUIC support. */
        public Builder httpVersion(HttpClient.Version httpVersion) {
            this.httpVersion = httpVersion;
            return this;
        }

        /** Adds a header to every request. */
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder correlationIdHeader(String correlationIdHeader) {
            this.correlationIdHeader = correlationIdHeader;
            return this;
        }

        /** Adds a listener; listeners run in registration order. */
        public Builder listener(JevListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /**
         * Uses a caller-managed HTTP client (proxy, TLS, authenticator, executor are then its business). It
         * is not closed by {@link JevClient#close()}; its redirect policy should be {@code NEVER}.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder proxy(ProxySelector proxy) {
            this.proxy = proxy;
            return this;
        }

        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /** Mapper used to serialize states and criteria (e.g. with custom modules). */
        public Builder jsonMapper(JsonMapper jsonMapper) {
            this.jsonMapper = jsonMapper;
            return this;
        }

        /** Settings source with {@code typesafe.*} keys, e.g. loaded from a {@code jev.properties} file. */
        public Builder properties(Properties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
            return this;
        }

        /** Resolves and validates the configuration without creating a client. */
        public JevConfig resolveConfig() {
            var source = new ConfigSource(properties, systemProperties, environment);
            try {
                return new JevConfig(
                        apiKey != null ? apiKey : source.string(ConfigSource.Setting.API_KEY).orElse(null),
                        JevConfig.parseBaseUrl(baseUrl != null ? baseUrl
                                : source.string(ConfigSource.Setting.BASE_URL).orElse(JevConfig.DEFAULT_BASE_URL)),
                        defaultModel != null ? defaultModel
                                : source.string(ConfigSource.Setting.DEFAULT_MODEL).orElse(JevConfig.DEFAULT_MODEL),
                        connectTimeout != null ? connectTimeout
                                : source.duration(ConfigSource.Setting.CONNECT_TIMEOUT)
                                        .orElse(JevConfig.DEFAULT_CONNECT_TIMEOUT),
                        requestTimeout != null ? requestTimeout
                                : source.duration(ConfigSource.Setting.REQUEST_TIMEOUT)
                                        .orElse(JevConfig.DEFAULT_REQUEST_TIMEOUT),
                        retryPolicy != null ? retryPolicy : retryPolicyFrom(source),
                        maxConcurrentRequests != null ? maxConcurrentRequests
                                : source.integer(ConfigSource.Setting.MAX_CONCURRENT_REQUESTS)
                                        .orElse(JevConfig.DEFAULT_MAX_CONCURRENT_REQUESTS),
                        httpVersion != null ? httpVersion
                                : source.httpVersion(ConfigSource.Setting.HTTP_VERSION).orElse(HttpClient.Version.HTTP_2),
                        headers,
                        correlationIdHeader != null ? correlationIdHeader : JevConfig.DEFAULT_CORRELATION_ID_HEADER);
            } catch (IllegalArgumentException e) {
                throw new JevConfigurationException(e.getMessage(), e);
            }
        }

        private static RetryPolicy retryPolicyFrom(ConfigSource source) {
            var policy = RetryPolicy.builder();
            source.integer(ConfigSource.Setting.MAX_RETRIES).ifPresent(policy::maxRetries);
            source.lookup(ConfigSource.Setting.TOTAL_TIMEOUT).ifPresent(value -> policy.totalTimeout(
                    value.raw().equalsIgnoreCase("none") ? null : ConfigSource.parseDuration(value)));
            return policy.build();
        }

        /**
         * Builds the client.
         *
         * @throws JevConfigurationException the configuration is missing or invalid
         */
        public JevClient build() {
            JevConfig config = resolveConfig();
            if (httpClient != null && (proxy != null || sslContext != null)) {
                throw new JevConfigurationException(
                        "httpClient cannot be combined with proxy or sslContext; configure them on that client");
            }
            return new JevClient(this, config);
        }
    }
}
