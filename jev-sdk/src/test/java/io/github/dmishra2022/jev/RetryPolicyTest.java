package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {

    static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (a, b) -> true);

    static JevApiException api(int status, Duration retryAfter) {
        return new JevApiException("x", status, "", NO_HEADERS, "POST /", retryAfter);
    }

    @Test
    void defaultsMatchOfficialSdks() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.maxRetries()).isEqualTo(2);
        assertThat(policy.initialBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.maxBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.jitter()).isEqualTo(0.25);
        assertThat(policy.retryableStatuses()).contains(408, 429, 500, 529, 599).doesNotContain(400, 401, 422, 600);
        assertThat(policy.respectRetryAfter()).isTrue();
        assertThat(policy.totalTimeout()).contains(Duration.ofSeconds(30));
        assertThat(RetryPolicy.disabled().maxRetries()).isZero();
    }

    @Test
    void toBuilderRoundTrips() {
        RetryPolicy custom = RetryPolicy.builder()
                .maxRetries(5)
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(80))
                .jitter(0)
                .retryableStatuses(Set.of(503))
                .respectRetryAfter(false)
                .maxRetryAfter(Duration.ofSeconds(1))
                .retryOnConnectionError(false)
                .retryOnTimeout(false)
                .retryPredicate(e -> true)
                .totalTimeout(null)
                .build();
        assertThat(custom.toBuilder().build()).isEqualTo(custom);
        assertThat(custom.totalTimeout()).isEmpty();
        assertThat(RetryPolicy.defaults().toBuilder().build()).isEqualTo(RetryPolicy.defaults());
    }

    @Test
    void builtInRules() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.shouldRetry(api(429, null))).isTrue();
        assertThat(policy.shouldRetry(api(529, null))).isTrue();
        assertThat(policy.shouldRetry(api(401, null))).isFalse();
        assertThat(policy.shouldRetry(new JevTimeoutException("t", "e", Duration.ofSeconds(1), null))).isTrue();
        assertThat(policy.shouldRetry(new JevConnectionException("c", "e", null))).isTrue();
        assertThat(policy.shouldRetry(new JevResponseValidationException("v", 200, "", NO_HEADERS, "e", "$")))
                .isFalse();
        assertThat(policy.shouldRetry(new JevInterruptedException("i", null))).isFalse();
        assertThat(policy.shouldRetry(new JevException("other"))).isFalse();
        assertThatNullPointerException().isThrownBy(() -> policy.shouldRetry(null));
    }

    @Test
    void connectionAndTimeoutRetriesCanBeDisabledIndependently() {
        RetryPolicy policy = RetryPolicy.builder().retryOnTimeout(false).build();
        assertThat(policy.shouldRetry(new JevTimeoutException("t", "e", Duration.ofSeconds(1), null))).isFalse();
        assertThat(policy.shouldRetry(new JevConnectionException("c", "e", null))).isTrue();
        RetryPolicy noConnection = RetryPolicy.builder().retryOnConnectionError(false).build();
        assertThat(noConnection.shouldRetry(new JevConnectionException("c", "e", null))).isFalse();
        assertThat(noConnection.shouldRetry(new JevTimeoutException("t", "e", Duration.ofSeconds(1), null))).isTrue();
    }

    @Test
    void predicateAddsRetriesButNeverForInterrupts() {
        RetryPolicy policy = RetryPolicy.builder().retryPredicate(e -> e.getMessage().equals("flaky")).build();
        assertThat(policy.shouldRetry(new JevException("flaky"))).isTrue();
        assertThat(policy.shouldRetry(new JevException("solid"))).isFalse();
        RetryPolicy always = RetryPolicy.builder().retryPredicate(e -> true).build();
        assertThat(always.shouldRetry(new JevInterruptedException("i", null))).isFalse();
    }

    @Test
    void toStringIsCompactAndReadable() {
        assertThat(RetryPolicy.defaults()).hasToString("RetryPolicy[maxRetries=2, backoff=PT0.5S..PT5S, jitter=0.25,"
                + " retryableStatuses=[408, 429, 500-599], respectRetryAfter=true, maxRetryAfter=PT1M,"
                + " retryOnConnectionError=true, retryOnTimeout=true, customPredicate=false, totalTimeout=PT30S]");
        assertThat(RetryPolicy.builder().retryableStatuses(Set.of()).retryPredicate(e -> true).totalTimeout(null)
                .build().toString())
                .contains("retryableStatuses=[]", "customPredicate=true", "totalTimeout=none");
        assertThat(RetryPolicy.ranges(Set.of(503, 502, 504, 429, 599))).isEqualTo("[429, 502-504, 599]");
    }

    @Test
    void exponentialBackoffWithCapAndJitter() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.backoff(1, 0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.backoff(2, 0)).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.backoff(4, 0)).isEqualTo(Duration.ofMillis(4000));
        assertThat(policy.backoff(5, 0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoff(500, 0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoff(1, 1)).isEqualTo(Duration.ofMillis(375));
        assertThat(policy.backoff(1, 0.5)).isEqualTo(Duration.ofNanos(437_500_000));
    }

    @Test
    void zeroBackoffDisablesWaiting() {
        assertThat(RetryPolicy.builder().initialBackoff(Duration.ZERO).build().backoff(3, 0.3)).isZero();
        assertThat(RetryPolicy.builder().maxBackoff(Duration.ZERO).build().backoff(3, 0.3)).isZero();
    }

    @Test
    void backoffValidatesArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> RetryPolicy.defaults().backoff(0, 0))
                .withMessage("retryNumber starts at 1 but was 0");
        assertThatIllegalArgumentException().isThrownBy(() -> RetryPolicy.defaults().backoff(1, 1.5));
    }

    @Test
    void retryAfterIsHonoredWithinLimit() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.delayFor(1, api(429, Duration.ofSeconds(2)), 0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayFor(1, api(429, Duration.ofSeconds(60)), 0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayFor(1, api(429, Duration.ofSeconds(61)), 0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayFor(1, api(429, null), 0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayFor(1, new JevConnectionException("c", "e", null), 0)).isEqualTo(Duration.ofMillis(500));
        RetryPolicy ignoring = RetryPolicy.builder().respectRetryAfter(false).build();
        assertThat(ignoring.delayFor(1, api(429, Duration.ofSeconds(2)), 0)).isEqualTo(Duration.ofMillis(500));
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 600, -1})
    void rejectsNonHttpStatuses(int status) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> RetryPolicy.builder().retryableStatuses(Set.of(status)).build());
    }

    @Test
    void validatesComponents() {
        assertThat(RetryPolicy.builder().retryableStatuses(Set.of(100, 599)).build().retryableStatuses())
                .containsExactlyInAnyOrder(100, 599);
        assertThatIllegalArgumentException().isThrownBy(() -> RetryPolicy.builder().maxRetries(-1).build());
        assertThatIllegalArgumentException().isThrownBy(
                () -> RetryPolicy.builder().initialBackoff(Duration.ofMillis(-1)).build());
        assertThatIllegalArgumentException().isThrownBy(() -> RetryPolicy.builder().jitter(1.1).build());
        assertThatIllegalArgumentException().isThrownBy(
                () -> RetryPolicy.builder().totalTimeout(Duration.ZERO).build());
        assertThatNullPointerException().isThrownBy(() -> RetryPolicy.builder().retryableStatuses(null).build());
        assertThatNullPointerException().isThrownBy(() -> RetryPolicy.builder().retryPredicate(null).build());
        assertThatNullPointerException().isThrownBy(() -> RetryPolicy.builder().maxRetryAfter(null).build());
        var policy = RetryPolicy.defaults();
        assertThatNullPointerException().isThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 0,
                Set.of(), true, Duration.ZERO, true, true, e -> false, null));
        assertThat(List.of(policy)).hasSize(1);
    }
}
