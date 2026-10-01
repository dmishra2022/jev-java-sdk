package io.github.dmishra2022.jev;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * How failed calls are retried. Defaults match the official TypeSafe SDKs: two retries, exponential backoff
 * from 500 ms capped at 5 s with 25% jitter, retry on 408/429/5xx and on connection errors and timeouts,
 * honor {@code Retry-After}, and a 30 s total budget per call.
 *
 * @param maxRetries             retries after the initial attempt; {@code 0} disables retries
 * @param initialBackoff         first backoff delay, doubled per retry; zero disables backoff
 * @param maxBackoff             backoff cap; zero disables backoff
 * @param jitter                 fraction of each backoff delay randomly subtracted, in [0, 1]
 * @param retryableStatuses      HTTP statuses that are retried
 * @param respectRetryAfter      whether to wait as long as {@code retry-after-ms} / {@code Retry-After} ask
 * @param maxRetryAfter          server-requested waits longer than this fall back to computed backoff
 * @param retryOnConnectionError whether connection failures are retried
 * @param retryOnTimeout         whether timeouts are retried
 * @param retryPredicate         extra rule; returning {@code true} retries in addition to the built-in rules
 * @param totalTimeout           budget for the whole call including delays; empty means unlimited. A retry
 *                               whose delay would reach the budget is not attempted
 */
public record RetryPolicy(int maxRetries, Duration initialBackoff, Duration maxBackoff, double jitter,
                          Set<Integer> retryableStatuses, boolean respectRetryAfter, Duration maxRetryAfter,
                          boolean retryOnConnectionError, boolean retryOnTimeout,
                          Predicate<? super JevException> retryPredicate, Optional<Duration> totalTimeout) {

    private static final Predicate<JevException> NEVER = failure -> false;

    public RetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative but was " + maxRetries);
        }
        Checks.requireNonNegative(initialBackoff, "initialBackoff");
        Checks.requireNonNegative(maxBackoff, "maxBackoff");
        Checks.requireUnitInterval(jitter, "jitter");
        retryableStatuses = Set.copyOf(Objects.requireNonNull(retryableStatuses, "retryableStatuses"));
        for (int status : retryableStatuses) {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("not an HTTP status: " + status);
            }
        }
        Checks.requireNonNegative(maxRetryAfter, "maxRetryAfter");
        Objects.requireNonNull(retryPredicate, "retryPredicate");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        totalTimeout.ifPresent(budget -> Checks.requirePositive(budget, "totalTimeout"));
    }

    /** The default policy. */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    /** A policy that never retries. */
    public static RetryPolicy disabled() {
        return builder().maxRetries(0).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-populated with this policy's values. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Whether {@code failure} should be retried (ignoring attempt counts and budgets). */
    public boolean shouldRetry(JevException failure) {
        if (Objects.requireNonNull(failure, "failure") instanceof JevInterruptedException) {
            return false;
        }
        boolean builtIn = switch (failure) {
            case JevResponseValidationException _ -> false;
            case JevApiException api -> retryableStatuses.contains(api.status());
            case JevTimeoutException _ -> retryOnTimeout;
            case JevConnectionException _ -> retryOnConnectionError;
            default -> false;
        };
        return builtIn || retryPredicate.test(failure);
    }

    /**
     * The delay before retry number {@code retryNumber} (1-based): the server's {@code Retry-After} when
     * honored, otherwise {@link #backoff(int, double)}.
     *
     * @param randomUnit a uniformly distributed value in [0, 1] driving the jitter
     */
    public Duration delayFor(int retryNumber, JevException failure, double randomUnit) {
        if (respectRetryAfter && failure instanceof JevApiException api) {
            Optional<Duration> requested = api.retryAfter();
            if (requested.isPresent() && requested.get().compareTo(maxRetryAfter) <= 0) {
                return requested.get();
            }
        }
        return backoff(retryNumber, randomUnit);
    }

    /** Exponential backoff with jitter for retry number {@code retryNumber} (1-based). */
    public Duration backoff(int retryNumber, double randomUnit) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber starts at 1 but was " + retryNumber);
        }
        Checks.requireUnitInterval(randomUnit, "randomUnit");
        if (initialBackoff.isZero() || maxBackoff.isZero()) {
            return Duration.ZERO;
        }
        double exponential = initialBackoff.toNanos() * Math.pow(2, retryNumber - 1);
        double capped = Math.min(exponential, maxBackoff.toNanos());
        return Duration.ofNanos((long) (capped * (1.0 - jitter * randomUnit)));
    }

    @Override
    public String toString() {
        return "RetryPolicy[maxRetries=" + maxRetries + ", backoff=" + initialBackoff + ".." + maxBackoff
                + ", jitter=" + jitter + ", retryableStatuses=" + ranges(retryableStatuses)
                + ", respectRetryAfter=" + respectRetryAfter + ", maxRetryAfter=" + maxRetryAfter
                + ", retryOnConnectionError=" + retryOnConnectionError + ", retryOnTimeout=" + retryOnTimeout
                + ", customPredicate=" + (retryPredicate != NEVER)
                + ", totalTimeout=" + totalTimeout.map(Duration::toString).orElse("none") + "]";
    }

    /** Renders statuses compactly, e.g. {@code [408, 429, 500-599]}. */
    static String ranges(Set<Integer> statuses) {
        var out = new StringJoiner(", ", "[", "]");
        int start = -1;
        int previous = -1;
        for (int status : new TreeSet<>(statuses)) {
            if (status != previous + 1) {
                if (start >= 0) {
                    out.add(range(start, previous));
                }
                start = status;
            }
            previous = status;
        }
        if (start >= 0) {
            out.add(range(start, previous));
        }
        return out.toString();
    }

    private static String range(int from, int to) {
        return from == to ? String.valueOf(from) : from + "-" + to;
    }

    /** Assembles a {@link RetryPolicy}. */
    public static final class Builder {
        private int maxRetries = 2;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(5);
        private double jitter = 0.25;
        private Set<Integer> retryableStatuses = defaultStatuses();
        private boolean respectRetryAfter = true;
        private Duration maxRetryAfter = Duration.ofSeconds(60);
        private boolean retryOnConnectionError = true;
        private boolean retryOnTimeout = true;
        private Predicate<? super JevException> retryPredicate = NEVER;
        private Duration totalTimeout = Duration.ofSeconds(30);

        private Builder() {
        }

        private Builder(RetryPolicy policy) {
            maxRetries = policy.maxRetries;
            initialBackoff = policy.initialBackoff;
            maxBackoff = policy.maxBackoff;
            jitter = policy.jitter;
            retryableStatuses = policy.retryableStatuses;
            respectRetryAfter = policy.respectRetryAfter;
            maxRetryAfter = policy.maxRetryAfter;
            retryOnConnectionError = policy.retryOnConnectionError;
            retryOnTimeout = policy.retryOnTimeout;
            retryPredicate = policy.retryPredicate;
            totalTimeout = policy.totalTimeout.orElse(null);
        }

        private static Set<Integer> defaultStatuses() {
            var statuses = new HashSet<Integer>();
            statuses.add(408);
            statuses.add(429);
            for (int status = 500; status < 600; status++) {
                statuses.add(status);
            }
            return statuses;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder jitter(double jitter) {
            this.jitter = jitter;
            return this;
        }

        public Builder retryableStatuses(Set<Integer> retryableStatuses) {
            this.retryableStatuses = retryableStatuses;
            return this;
        }

        public Builder respectRetryAfter(boolean respectRetryAfter) {
            this.respectRetryAfter = respectRetryAfter;
            return this;
        }

        public Builder maxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = maxRetryAfter;
            return this;
        }

        public Builder retryOnConnectionError(boolean retryOnConnectionError) {
            this.retryOnConnectionError = retryOnConnectionError;
            return this;
        }

        public Builder retryOnTimeout(boolean retryOnTimeout) {
            this.retryOnTimeout = retryOnTimeout;
            return this;
        }

        public Builder retryPredicate(Predicate<? super JevException> retryPredicate) {
            this.retryPredicate = retryPredicate;
            return this;
        }

        /** The budget for a whole call including retries and delays; {@code null} means unlimited. */
        public Builder totalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(maxRetries, initialBackoff, maxBackoff, jitter, retryableStatuses,
                    respectRetryAfter, maxRetryAfter, retryOnConnectionError, retryOnTimeout, retryPredicate,
                    Optional.ofNullable(totalTimeout));
        }
    }
}
