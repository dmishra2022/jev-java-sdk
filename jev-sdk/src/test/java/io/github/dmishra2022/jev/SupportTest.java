package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retry-After parsing, SDK version loading and exception accessors. */
class SupportTest {

    static HttpHeaders headers(String... nameValues) {
        var map = new java.util.LinkedHashMap<String, List<String>>();
        for (int i = 0; i < nameValues.length; i += 2) {
            map.put(nameValues[i], List.of(nameValues[i + 1]));
        }
        return HttpHeaders.of(map, (a, b) -> true);
    }

    @Nested
    class RetryAfterParsing {
        private final Instant now = Instant.parse("2026-10-01T12:00:00Z");

        @Test
        void millisecondsHeaderWins() {
            assertThat(RetryAfter.parse(headers("retry-after-ms", "1500", "retry-after", "9"), now))
                    .contains(Duration.ofMillis(1500));
            assertThat(RetryAfter.parse(headers("retry-after-ms", "12.5"), now)).contains(Duration.ofNanos(12_500_000));
            assertThat(RetryAfter.parse(headers("retry-after-ms", "0"), now)).contains(Duration.ZERO);
        }

        @Test
        void invalidMillisecondsFallBackToSeconds() {
            assertThat(RetryAfter.parse(headers("retry-after-ms", "soon", "retry-after", "2"), now))
                    .contains(Duration.ofSeconds(2));
        }

        @Test
        void secondsMayBeFractional() {
            assertThat(RetryAfter.parse(headers("Retry-After", " 0.25 "), now)).contains(Duration.ofMillis(250));
        }

        @Test
        void httpDates() {
            assertThat(RetryAfter.parse(headers("Retry-After", "Thu, 01 Oct 2026 12:00:30 GMT"), now))
                    .contains(Duration.ofSeconds(30));
            assertThat(RetryAfter.parse(headers("Retry-After", "Thu, 01 Oct 2026 11:00:00 GMT"), now))
                    .contains(Duration.ZERO);
            assertThat(RetryAfter.parse(headers("Retry-After", "Thu, 01 Oct 2026 12:00:00 GMT"), now))
                    .contains(Duration.ZERO);
        }

        @ParameterizedTest
        @ValueSource(strings = {"-1", "NaN", "Infinity", "tomorrow", ""})
        void garbageIsIgnored(String value) {
            assertThat(RetryAfter.parse(headers("Retry-After", value), now)).isEmpty();
            assertThat(RetryAfter.parse(headers("retry-after-ms", value), now)).isEmpty();
        }

        @Test
        void absentHeaders() {
            assertThat(RetryAfter.parse(headers(), now)).isEmpty();
        }
    }

    @Nested
    class Version {
        @Test
        void loadsFilteredResource() {
            assertThat(SdkVersion.load()).isNotBlank().doesNotContain("${");
            assertThat(JevClient.SDK_VERSION).isEqualTo(SdkVersion.load());
        }

        @Test
        void fallsBackToUnknown() {
            assertThat(SdkVersion.load(null)).isEqualTo(SdkVersion.UNKNOWN);
            assertThat(SdkVersion.load(new ByteArrayInputStream("other=1".getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(SdkVersion.UNKNOWN);
            assertThat(SdkVersion.load(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("boom");
                }
            })).isEqualTo(SdkVersion.UNKNOWN);
        }
    }

    @Nested
    class Exceptions {
        @Test
        void apiExceptionExposesResponse() {
            var e = new JevRateLimitException("slow down", 429, "{}", headers("x-typesafe-request-id", "r-1"),
                    "POST https://api.typesafe.ai/v1/systemone", Duration.ofSeconds(1));
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.body()).isEqualTo("{}");
            assertThat(e.headers().firstValue("X-TypeSafe-Request-Id")).contains("r-1");
            assertThat(e.endpoint()).startsWith("POST ");
            assertThat(e.requestId()).contains("r-1");
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));
            assertThat(e).hasMessage("slow down").isInstanceOf(JevApiException.class);
        }

        @Test
        void subclassesAndAccessors() {
            HttpHeaders none = headers();
            assertThat(new JevServerException("m", 529, "", none, "e", null).isOverloaded()).isTrue();
            assertThat(new JevServerException("m", 503, "", none, "e", null).isOverloaded()).isFalse();
            assertThat(new JevResponseValidationException("m", 200, "", none, "e", "answers.x").fieldPath())
                    .isEqualTo("answers.x");
            assertThat(new JevResponseValidationException("m", 200, "", none, "e", "$").retryAfter()).isEmpty();
            var timeout = new JevTimeoutException("t", "GET /", Duration.ofSeconds(2), null);
            assertThat(timeout.timeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(timeout.endpoint()).isEqualTo("GET /");
            var cause = new IllegalStateException();
            assertThat(new JevConfigurationException("c", cause)).hasCause(cause);
            assertThat(new JevException("j", cause)).hasCause(cause);
            assertThat(new JevApiException("m", 418, "", none, "e", null).requestId()).isEmpty();
            for (var e : List.of(new JevBadRequestException("m", 400, "", none, "e", null),
                    new JevAuthenticationException("m", 401, "", none, "e", null),
                    new JevPermissionDeniedException("m", 403, "", none, "e", null),
                    new JevNotFoundException("m", 404, "", none, "e", null),
                    new JevUnprocessableEntityException("m", 422, "", none, "e", null))) {
                assertThat(e).isInstanceOf(JevApiException.class);
            }
        }

        @Test
        void apiExceptionRequiresResponseParts() {
            HttpHeaders none = headers();
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new JevApiException("m", 500, null, none, "e", null));
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new JevApiException("m", 500, "", null, "e", null));
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new JevApiException("m", 500, "", none, null, null));
            assertThat(Map.of()).isEmpty();
        }
    }

    @Test
    void contextIsEmptyUnlessBound() throws Exception {
        assertThat(JevContext.correlationId()).isEmpty();
        assertThat(ScopedValue.where(JevContext.CORRELATION_ID, "abc").call(JevContext::correlationId)).contains("abc");
    }

    @Test
    void listenerDefaultsAreNoOps() {
        JevListener listener = new JevListener() {
        };
        var attempt = new JevListener.Attempt("GET /", 1, java.util.Optional.empty());
        listener.onAttemptStart(attempt);
        listener.onAttemptEnd(attempt, 200, Duration.ZERO, null);
        listener.onRetryScheduled(attempt, Duration.ZERO, new JevException("x"));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new JevListener.Attempt(null, 1, java.util.Optional.empty()));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new JevListener.Attempt("e", 1, null));
    }
}
