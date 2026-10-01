package io.github.dmishra2022.jev;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-call overrides of client settings.
 *
 * @param model        model override; wins over {@link SystemOneRequest#model()} and the client default
 * @param retryPolicy  retry policy for this call only
 * @param timeout      per-attempt HTTP timeout for this call only
 * @param extraHeaders additional request headers; authentication and SDK headers stay protected
 * @param extraBody    top-level body fields shallow-merged last, overriding {@code state}/{@code model}/
 *                     {@code questions} on collision
 */
public record RequestOptions(Optional<String> model, Optional<RetryPolicy> retryPolicy, Optional<Duration> timeout,
                             Map<String, String> extraHeaders, Map<String, Object> extraBody) {

    /** No overrides. */
    public static final RequestOptions DEFAULT = builder().build();

    public RequestOptions {
        Objects.requireNonNull(model, "model");
        model.ifPresent(name -> Checks.requireNonBlank(name, "model"));
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(timeout, "timeout");
        timeout.ifPresent(value -> Checks.requirePositive(value, "timeout"));
        extraHeaders = Checks.orderedCopy(extraHeaders, "extraHeaders");
        HeaderRules.validateUserHeaders(extraHeaders);
        extraBody = Checks.orderedCopy(extraBody, "extraBody");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Assembles {@link RequestOptions}. */
    public static final class Builder {
        private String model;
        private RetryPolicy retryPolicy;
        private Duration timeout;
        private final Map<String, String> extraHeaders = new LinkedHashMap<>();
        private final Map<String, Object> extraBody = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder header(String name, String value) {
            extraHeaders.put(name, value);
            return this;
        }

        public Builder bodyField(String name, Object value) {
            extraBody.put(name, value);
            return this;
        }

        public RequestOptions build() {
            return new RequestOptions(Optional.ofNullable(model), Optional.ofNullable(retryPolicy),
                    Optional.ofNullable(timeout), extraHeaders, extraBody);
        }
    }
}
